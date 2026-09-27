#!/usr/bin/env python3
"""Build places.db: where to eat, drink and stay, worldwide, for questions like "the best vegan
restaurants in Lisbon" (which Wikipedia cannot answer and Wikivoyage covers only thinly).

Sources, merged into one SQLite file the app opens read-only:

  Overture Maps places (theme=places), the food_and_drink and lodging categories: name,
      category, address, phone, website, existence confidence. CDLA-Permissive-2.0 (some records
      Apache-2.0 from Foursquare, CC0 from AllThePlaces).
  OpenStreetMap places with diet tags (fetch_osm_diet.py's JSON): vegan/vegetarian/gluten-free/halal/
      kosher, cuisine and opening hours, merged into the Overture place they match (same name
      within 120 m) or added. ODbL 1.0, so places.db as a whole is offered under the ODbL.
  GeoNames cities1000 (with country and region names): resolves the city a question names. CC BY 4.0.
  Wikivoyage (voyage.db): Eat, Drink, Sleep, See, Do and Buy listings matched to places by name
      near the guide's city, so a place the travel guide recommends ranks first. Only the listing's
      name and where it is are stored; its text stays in voyage.db (CC BY-SA).
  Wikipedia (wiki.db, --wiki): a place whose own name is an article title (or redirect) gets that
      article's monthly views as its fame ("the best museums in Madrid": the Prado), when the name
      is not a chain's, not a city's and not shared by many places.

    python scripts/build_places.py --overture DIR --osm osm-diet.json --geonames DIR --voyage voyage.db --wiki wiki.db \
        --work DIR --out places.db [--bbox S,W,N,E]

--bbox keeps only the places in one box (small test databases); cities are kept worldwide.
Needs duckdb and zstandard. The full build reads ~11 GB of Overture parquet; --work holds
DuckDB's intermediate tables and spill files.
"""
import argparse
import glob
import json
import math
import os
import re
import sqlite3
import sys
import time
import unicodedata

import duckdb
import zstandard

# ---- shared with the query side (scripts/places.py and Places.kt): keep in step -------------

CELL_DEG = 0.05  # grid cell for spatial lookup, about 5.5 km north-south
CELL_COLS = 7200  # 360 / CELL_DEG


def cell_of(lat, lon):
    return int(math.floor((lat + 90.0) * 20.0)) * CELL_COLS + int(math.floor((lon + 180.0) * 20.0))


# diet bits (OpenStreetMap diet:* tags)
VEGAN_ONLY, VEGAN_YES, VEGAN_LIMITED, VEGAN_NO = 1, 2, 4, 8
VEGETARIAN_ONLY, VEGETARIAN_YES, VEGETARIAN_LIMITED = 16, 32, 64
GLUTEN_FREE, HALAL, KOSHER = 128, 256, 512
DIET_BITS = {
    "diet:vegan": {"only": VEGAN_ONLY, "yes": VEGAN_YES, "limited": VEGAN_LIMITED, "no": VEGAN_NO},
    "diet:vegetarian": {"only": VEGETARIAN_ONLY, "yes": VEGETARIAN_YES, "limited": VEGETARIAN_LIMITED},
    "diet:gluten_free": {"only": GLUTEN_FREE, "yes": GLUTEN_FREE},
    "diet:halal": {"only": HALAL, "yes": HALAL},
    "diet:kosher": {"only": KOSHER, "yes": KOSHER},
}

# source bits
SRC_OVERTURE, SRC_OSM, SRC_GUIDE = 1, 2, 4

# letters NFKD does not decompose
FOLD = str.maketrans({"ß": "ss", "ø": "o", "Ø": "o", "æ": "ae", "Æ": "ae", "œ": "oe", "Œ": "oe",
                      "ł": "l", "Ł": "l", "đ": "d", "Đ": "d", "ı": "i", "þ": "th", "ð": "d"})


def norm_key(s):
    """Lower-case ASCII words: accents dropped, anything else a space. The key of a city name."""
    s = unicodedata.normalize("NFKD", s.translate(FOLD))
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return s.strip()


# ---- build-only ------------------------------------------------------------------------------

TOP = ("food_and_drink", "lodging")
# what else a traveller looks for (a category and everything under it); these need an existence
# confidence of MIN_CONF_OTHER, there being far more of them
TRAVEL = ("pharmacy_and_drug_store", "hospital", "emergency_or_urgent_care_facility", "primary_care_or_general_clinic",
          "dental_clinic", "atm", "bank_or_credit_union", "currency_exchange", "grocery_store", "convenience_store",
          "shopping_mall", "market", "mobile_phone_store", "telecommunications_company", "laundromat", "laundry_service",
          "coworking_space", "shared_office_space", "post_office", "police_station", "embassy", "museum", "art_gallery",
          "zoo", "aquarium", "amusement_park", "theatre_venue", "monument", "historic_site", "castle", "palace", "fort",
          "religious_landmark", "botanical_garden", "park", "national_park", "beach", "public_plaza", "hiking_trail",
          "gym", "train_station", "bus_station", "metro_station", "airport", "car_rental_service", "bike_rental",
          "scooter_rental", "ferry_service")
MIN_CONF = 0.3
MIN_CONF_OTHER = 0.5
MATCH_M = 120  # OSM place to Overture place
GENERIC = ("restaurant restaurante ristorante restaurace ravintola cafe caffe coffee bar the and "
           "y e et und de del da do das dos di du la le les el il lo los las shop house kitchen "
           "museum museo musee museu hotel hostel").split()
FOOD_AMENITY = {"restaurant": "restaurant", "cafe": "cafe", "fast_food": "fast_food_restaurant",
                "bar": "bar", "pub": "pub", "biergarten": "beer_garden", "ice_cream": "ice_cream_shop",
                "food_court": "food_court"}
FOOD_SHOP = {"bakery": "bakery", "pastry": "patisserie", "confectionery": "candy_store",
             "deli": "delicatessen", "ice_cream": "ice_cream_shop"}
# OpenStreetMap's own coverage of these is better than Overture's (ATMs), or has opening hours (pharmacies)
UTIL_AMENITY = {"pharmacy": "pharmacy", "atm": "atm", "bureau_de_change": "currency_exchange"}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def sql_norm(col):
    """DuckDB expression: the name for matching (lower-case ASCII words, generic words dropped)."""
    words = "|".join(GENERIC)
    e = f"regexp_replace(lower(strip_accents({col})), '[^a-z0-9]+', ' ', 'g')"
    e = f"regexp_replace(' ' || {e} || ' ', ' (({words}) )+', ' ', 'g')"
    return f"trim(regexp_replace({e}, ' +', ' ', 'g'))"


def sql_full(col):
    """DuckDB expression: the whole name as lower-case ASCII words."""
    e = f"regexp_replace(lower(strip_accents({col})), '[^a-z0-9]+', ' ', 'g')"
    return f"trim({e})"


def load_overture(con, pattern, bbox):
    where = ""
    if bbox:
        s, w, n, e = bbox
        where = f"and bbox.ymin between {s} and {n} and bbox.xmin between {w} and {e}"
    con.execute(f"""
        create or replace table ov as
        select id as oid,
               (bbox.ymin + bbox.ymax) / 2 as lat, (bbox.xmin + bbox.xmax) / 2 as lon,
               names.primary as name, taxonomy.primary as cat, taxonomy.hierarchy as hier,
               taxonomy.alternates as alts, confidence as conf, operating_status as status,
               addresses[1].freeform as street, addresses[1].locality as locality,
               addresses[1].country as country, websites[1] as website, phones[1] as phone,
               brand.names.primary as brand
        from read_parquet('{pattern}')
        where ((taxonomy.hierarchy[1] in {TOP} and confidence >= {MIN_CONF})
               or (list_has_any(taxonomy.hierarchy, {list(TRAVEL)}) and confidence >= {MIN_CONF_OTHER}))
          and coalesce(operating_status, '') <> 'permanently_closed'
          and names.primary is not null and length(trim(names.primary)) > 1 {where}""")
    log("overture:", con.execute("select count(*) from ov").fetchone()[0], "places")


def osm_kind(tags, known):
    cuisines = [c.strip().lower() for c in tags.get("cuisine", "").replace(",", ";").split(";") if c.strip()]
    am, shop = tags.get("amenity"), tags.get("shop")
    if am in UTIL_AMENITY:
        return UTIL_AMENITY[am] if UTIL_AMENITY[am] in known else None
    if am in FOOD_AMENITY:
        base = FOOD_AMENITY[am]
    elif shop in FOOD_SHOP:
        base = FOOD_SHOP[shop]
    else:
        return None
    if am in ("restaurant", "fast_food", "cafe"):
        if "vegan" in cuisines or tags.get("diet:vegan") == "only":
            return "vegan_restaurant"
        if "vegetarian" in cuisines or tags.get("diet:vegetarian") == "only":
            return "vegetarian_restaurant"
        if am == "restaurant":
            for c in cuisines:
                if f"{c}_restaurant" in known:
                    return f"{c}_restaurant"
    return base if base in known else "restaurant" if am else "food_and_drink"


WKT_NUM = re.compile(r"-?\d+(?:\.\d+)?")


def wkt_point(wkt):
    """A POINT's coordinates, or the mean of the first ring's for a way or relation."""
    nums = WKT_NUM.findall(wkt.split("),")[0])
    xs, ys = nums[0::2], nums[1::2]
    if not xs or len(xs) != len(ys):
        return None
    n = len(xs) - 1 if len(xs) > 2 and xs[0] == xs[-1] and ys[0] == ys[-1] else len(xs)
    return sum(float(y) for y in ys[:n]) / n, sum(float(x) for x in xs[:n]) / n


def load_osm(con, osm_json, bbox, known):
    rows, seen = [], set()
    bindings = json.load(open(osm_json))["results"]["bindings"] if osm_json else []
    for b in bindings:
        t = {k: v["value"] for k, v in b.items()}
        uri = t["s"]
        kind_, num = uri.rsplit("/", 2)[-2:]
        osm_id = kind_[0] + num
        if osm_id in seen:
            continue
        seen.add(osm_id)
        name = t.get("name") or t.get("name_en")
        if not name and t.get("amenity") in UTIL_AMENITY:
            # an ATM or a pharmacy is often tagged by its operator or brand only
            who = t.get("brand") or t.get("operator")
            name = (who + " " if who else "") + {"atm": "ATM", "pharmacy": "pharmacy", "bureau_de_change": "money exchange"}[t["amenity"]]
        pt = wkt_point(t["wkt"])
        if not name or pt is None:
            continue
        lat, lon = pt
        if bbox and not (bbox[0] <= lat <= bbox[2] and bbox[1] <= lon <= bbox[3]):
            continue
        tags = {"amenity": t.get("amenity"), "shop": t.get("shop"), "cuisine": t.get("cuisine", ""),
                "diet:vegan": t.get("vegan"), "diet:vegetarian": t.get("vegetarian")}
        kind = osm_kind({k: v for k, v in tags.items() if v is not None}, known)
        if kind is None:
            continue
        diet = 0
        for key, var in (("diet:vegan", "vegan"), ("diet:vegetarian", "vegetarian"), ("diet:gluten_free", "gluten_free"),
                         ("diet:halal", "halal"), ("diet:kosher", "kosher")):
            diet |= DIET_BITS[key].get((t.get(var) or "").lower(), 0)
        street = " ".join(x for x in (t.get("street"), t.get("housenumber")) if x) or None
        rows.append((osm_id, lat, lon, name, kind, diet, t.get("cuisine"), t.get("hours"),
                     t.get("website") or t.get("contact_website"), t.get("phone") or t.get("contact_phone"),
                     street, t.get("city")))
    con.execute("""create or replace table osm(osm_id varchar, lat double, lon double, name varchar,
        kind varchar, diet integer, cuisine varchar, hours varchar, website varchar, phone varchar,
        street varchar, locality varchar)""")
    if rows:
        con.executemany("insert into osm values (?,?,?,?,?,?,?,?,?,?,?,?)", rows)
    log("osm:", len(rows), "food places with diet tags, of", len(bindings), "objects")


def match_osm(con):
    """Each OSM place to the Overture place with the most similar name within MATCH_M metres."""
    g = 500  # 0.002 degree grid for the join
    con.execute(f"""
        create or replace table ovn as select oid, lat, lon, {sql_norm('name')} as n,
            cast(floor(lat * {g}) as integer) as gy, cast(floor(lon * {g}) as integer) as gx from ov""")
    con.execute(f"""
        create or replace table osmn as select osm_id, lat, lon, {sql_norm('name')} as n,
            cast(floor(lat * {g}) as integer) as gy, cast(floor(lon * {g}) as integer) as gx from osm""")
    con.execute(f"""
        create or replace table pairs as
        with near as (
            select o.osm_id, v.oid, o.n as a, v.n as b,
                   sqrt(pow((v.lat - o.lat) * 110540, 2) + pow((v.lon - o.lon) * 111320 * cos(radians(o.lat)), 2)) as d
            from osmn o, (select unnest([-1, 0, 1]) as dy) y, (select unnest([-1, 0, 1]) as dx) x, ovn v
            where v.gy = o.gy + y.dy and v.gx = o.gx + x.dx
        ), scored as (
            select *, case when a = b then 1.0
                           when length(a) >= 4 and length(b) >= 4 and (contains(a, b) or contains(b, a)) then 0.95
                           else jaro_winkler_similarity(a, b) end as sim
            from near where d <= {MATCH_M} and a <> '' and b <> ''
        ), best as (
            select *, row_number() over (partition by osm_id order by sim desc, d) as r1 from scored where sim >= 0.9
        ), one as (
            select *, row_number() over (partition by oid order by sim desc, d) as r2 from best where r1 = 1
        )
        select osm_id, oid from one where r2 = 1""")
    log("osm matched to overture:", con.execute("select count(*) from pairs").fetchone()[0])


def load_geonames(gn_dir):
    countries, regions = {}, {}
    for line in open(os.path.join(gn_dir, "countryInfo.txt"), encoding="utf-8"):
        if line.startswith("#"):
            continue
        f = line.rstrip("\n").split("\t")
        countries[f[0]] = f[4]
    for line in open(os.path.join(gn_dir, "admin1CodesASCII.txt"), encoding="utf-8"):
        code, name, ascii_name, _ = line.rstrip("\n").split("\t")
        regions[code] = name
    cities = []
    for line in open(os.path.join(gn_dir, "cities1000.txt"), encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        gid, name, ascii_name, alt = int(f[0]), f[1], f[2], f[3]
        lat, lon, cc, a1, pop = float(f[4]), float(f[5]), f[8], f[10], int(f[14] or 0)
        cities.append((gid, name, ascii_name, alt, lat, lon, cc, regions.get(f"{cc}.{a1}"), pop, int(f[7] == "PPLC")))
    return countries, regions, cities


LATIN = re.compile(r"^[a-z0-9 ]+$")


def city_keys(name, ascii_name, alt, pop):
    keys = {norm_key(name), norm_key(ascii_name)}
    if pop >= 15000 and alt:
        for a in alt.split(","):
            # Latin-script names only (what an English question uses); CJK, Cyrillic etc. are dropped
            if not a or any(ord(c) > 0x24F for c in a):
                continue
            k = norm_key(a)
            if 2 <= len(k) <= 40 and LATIN.match(k):
                keys.add(k)
    keys.discard("")
    return keys


def city_radius_km(pop):
    """How far from its centre a city's places are looked for."""
    return max(3.0, min(25.0, 2.0 + 4.0 * math.log10(max(pop, 1000) / 1000.0)))


# ---- Wikivoyage listings -----------------------------------------------------------------------

LISTING = re.compile(r"^- - (.+?)(?::\s|$)(.*)")
SECTIONS = {"eat": "Eat", "drink": "Drink", "sleep": "Sleep", "see": "See", "do": "Do", "buy": "Buy"}


def voyage_articles(path):
    c = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    d = zstandard.ZstdDecompressor()
    cache = {}
    for title, bid, off, ln in c.execute("select title, block_id, off, len from articles order by block_id"):
        if bid not in cache:
            cache.clear()
            cache[bid] = d.decompress(c.execute("select zdata from blocks where id=?", (bid,)).fetchone()[0])
        # off and len are a byte range in the decompressed block
        yield title, cache[bid][off:off + ln].decode("utf-8")


def listings(text):
    """(section, tier, name, rest) for each listing line under Eat, Drink or Sleep."""
    section = tier = None
    for line in text.split("\n"):
        if line.startswith("## "):
            section = SECTIONS.get(line[3:].strip().lower())
            tier = None
        elif line.startswith("### ") and section:
            tier = line[4:].strip()
        elif section and line.startswith("- - "):
            m = LISTING.match(line)
            if m:
                name = m.group(1).strip().rstrip(".")
                if 2 <= len(name) <= 80:
                    yield section, tier, name, m.group(2)


def resolve_article_city(title, names, cities_by_id, region_keys):
    base = title.split("/")[0]
    hint = None
    m = re.match(r"^(.*?)\s*\((.+)\)\s*$", base)
    if m:
        base, hint = m.group(1), norm_key(m.group(2))
    cands = [cities_by_id[i] for i in names.get(norm_key(base), ())]
    if not cands:
        return None
    if hint:
        hinted = [c for c in cands if hint in region_keys.get((c[6], c[7]), ())]
        if not hinted:
            return None
        cands = hinted
    return max(cands, key=lambda c: c[8])


def match_guide(con, voyage_path, names, cities_by_id, region_keys, bbox):
    rows = []
    n_listings = 0
    for title, text in voyage_articles(voyage_path):
        ls = list(listings(text))
        if not ls:
            continue
        city = resolve_article_city(title, names, cities_by_id, region_keys)
        if city is None:
            continue
        lat, lon, pop = city[4], city[5], city[8]
        if bbox and not (bbox[0] - 0.5 <= lat <= bbox[2] + 0.5 and bbox[1] - 0.5 <= lon <= bbox[3] + 0.5):
            continue
        r = city_radius_km(pop) + (3.0 if "/" in title else 0.0)
        for section, tier, name, rest in ls:
            n_listings += 1
            # the house number, from the address that starts the listing (not its bus lines or prices)
            digits = " ".join(re.findall(r"\d+", rest.split(". ")[0][:60]))
            rows.append((title, section, tier, name, lat, lon, r, digits))
    con.execute("""create or replace table vl(article varchar, section varchar, tier varchar, listing varchar,
        clat double, clon double, r double, digits varchar)""")
    con.executemany("insert into vl values (?,?,?,?,?,?,?,?)", rows)
    log("guide listings:", n_listings, "in articles with a known city")
    g = 20  # 0.05 degree grid
    con.execute(f"""
        create or replace table vln as
        select *, row_number() over () as lid, {sql_norm('listing')} as n, {sql_full('listing')} as nf,
               cast(floor(clat * {g}) as integer) as gy, cast(floor(clon * {g}) as integer) as gx,
               cast(ceil(r / 5.5) as integer) as span from vl""")
    con.execute(f"""
        create or replace table pln as
        select pid, lat, lon, n, nf, street, top, left(n, 3) as p3,
               cast(floor(lat * {g}) as integer) as gy, cast(floor(lon * {g}) as integer) as gx
        from (select pid, lat, lon, {sql_norm('name')} as n, {sql_full('name')} as nf, street, top from merged) where n <> ''""")
    # each listing in every grid cell its city's radius reaches; a place is a candidate when it is
    # in one of them and its name starts with the same three letters (fuzzy matches included)
    con.execute("""
        create or replace table vlc as
        select lid, gy2, unnest(range(gx - span * 2, gx + span * 2 + 1)) as gx2, left(n, 3) as p3
        from (select lid, gx, span, n, unnest(range(gy - span, gy + span + 1)) as gy2 from vln where n <> '')""")
    con.execute(f"""
        create or replace table guide_match as
        with near as (
            select l.lid, p.pid, l.n as a, p.n as b, l.nf as nfa, p.nf as nfb, l.nf = p.nf as same, l.digits, p.street,
                   sqrt(pow((p.lat - l.clat) * 110.54, 2) + pow((p.lon - l.clon) * 111.32 * cos(radians(l.clat)), 2)) as km, l.r,
                   jaro_winkler_similarity(p.n, l.n) as jw
            from vlc c join pln p on p.gy = c.gy2 and p.gx = c.gx2 and p.p3 = c.p3
            join vln l on l.lid = c.lid
            -- Eat and Drink listings are food places, Sleep listings lodging, See, Do and Buy the rest
            where (case when l.section = 'Sleep' then p.top = 'lodging'
                        when l.section in ('Eat', 'Drink') then p.top = 'food_and_drink'
                        else p.top not in ('lodging', 'food_and_drink') end)
              and (p.nf = l.nf or p.n = l.n or jaro_winkler_similarity(p.n, l.n) >= 0.88
                   or list_has_all(string_split(p.nf, ' '), string_split(l.nf, ' '))
                   or list_has_all(string_split(l.nf, ' '), string_split(p.nf, ' ')))
        ), scored as (
            select *, (digits <> '' and street is not null and
                       list_has_any(string_split(digits, ' '), regexp_extract_all(street, '\\d+')))::int as num
            from near where km <= r and a <> ''
        ), ok as (
            -- the full name, or the name without generic words when it is long enough to be
            -- distinctive, or a near-identical long name, or a similar one at the same house number
            select *, (len(string_split(nfa, ' ')) >= 2 and len(string_split(nfb, ' ')) >= 2
                       and (list_has_all(string_split(nfb, ' '), string_split(nfa, ' '))
                            or list_has_all(string_split(nfa, ' '), string_split(nfb, ' '))))::int as subset
            from scored
            where same or (a = b and length(a) >= 4) or (jw >= 0.95 and length(a) >= 6) or (num = 1 and jw >= 0.88)
               -- every word of the one name in the other ("Museo del Prado", "Museo Nacional del Prado")
               or (len(string_split(nfa, ' ')) >= 2 and len(string_split(nfb, ' ')) >= 2
                   and (list_has_all(string_split(nfb, ' '), string_split(nfa, ' '))
                        or list_has_all(string_split(nfa, ' '), string_split(nfb, ' '))))
        ), best as (
            -- names that agree as well: the nearer (another "British Museum" 16 km out of town)
            select *, row_number() over (partition by lid order by (same or a = b) desc, num desc, subset desc, jw desc, km) as rk,
                   count(*) over (partition by lid) as nc
            from ok
        )
        select v.article, v.section, v.tier, v.listing, b.pid
        from best b join vln v using (lid)
        where rk = 1 and (nc = 1 or num = 1 or same or (a = b and length(a) >= 6) or subset = 1)""")
    log("guide listings matched to places:", con.execute("select count(*) from guide_match").fetchone()[0])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--overture", required=True, help="directory of Overture places parquet files")
    ap.add_argument("--osm", help="fetch_osm_diet.py's JSON")
    ap.add_argument("--geonames", required=True)
    ap.add_argument("--voyage")
    ap.add_argument("--wiki", help="wiki.db: fame from article views")
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--bbox")
    a = ap.parse_args()
    bbox = [float(x) for x in a.bbox.split(",")] if a.bbox else None
    os.makedirs(a.work, exist_ok=True)
    con = duckdb.connect(os.path.join(a.work, "build.duckdb"))
    con.execute(f"set temp_directory='{os.path.join(a.work, 'spill')}'; set preserve_insertion_order=false; "
                "set memory_limit='7GB'")

    # the loads take minutes; a rebuild with the same inputs reuses the tables of the last one
    def cached(name, key, load):
        con.execute("create table if not exists build_cache(name varchar primary key, key varchar)")
        row = con.execute("select key from build_cache where name = ?", (name,)).fetchone()
        exists = con.execute("select count(*) from information_schema.tables where table_name = ?", (name,)).fetchone()[0]
        if row and row[0] == key and exists:
            log(f"{name}: reusing the table of the last build")
            return
        load()
        con.execute("insert or replace into build_cache values (?, ?)", (name, key))

    def stamp(path):
        return f"{path}:{os.path.getsize(path)}:{int(os.path.getmtime(path))}" if path and os.path.exists(path) else str(path)

    pattern = os.path.join(a.overture, "*.parquet")
    cached("ov", f"{pattern}:{bbox}:{TOP}:{TRAVEL}:{MIN_CONF}:{MIN_CONF_OTHER}:{len(glob.glob(pattern))}",
           lambda: load_overture(con, pattern, bbox))
    known = {r[0] for r in con.execute("select distinct cat from ov where cat is not null").fetchall()}
    cached("osm", f"{stamp(a.osm)}:{bbox}:{UTIL_AMENITY}:{FOOD_AMENITY}:{FOOD_SHOP}", lambda: load_osm(con, a.osm, bbox, known))
    match_osm(con)

    # one table of places: Overture, with the matched OSM place's diet, hours and cuisine; plus
    # the OSM places no Overture place matched
    con.execute(f"""
        create or replace table merged as
        select row_number() over () as pid, * from (
            select v.lat, v.lon, v.name, coalesce(v.cat, v.hier[-1], 'food_and_drink') as cat, v.hier[1] as top,
                   array_to_string(v.alts, ',') as alt,
                   coalesce(o.diet, 0) as diet,
                   {SRC_OVERTURE} | (case when o.osm_id is null then 0 else {SRC_OSM} end) as src,
                   cast(round(v.conf * 100) as integer) as conf, (v.brand is not null)::int as chain,
                   coalesce(v.street, o.street) as street, v.locality, coalesce(v.phone, o.phone) as phone,
                   coalesce(v.website, o.website) as website, o.hours, o.cuisine
            from ov v left join pairs p on p.oid = v.oid left join osm o on o.osm_id = p.osm_id
            union all
            select o.lat, o.lon, o.name, o.kind, coalesce(k.top, 'food_and_drink'), null, o.diet, {SRC_OSM}, 60, 0, o.street, o.locality,
                   o.phone, o.website, o.hours, o.cuisine
            from osm o anti join pairs p on p.osm_id = o.osm_id
            left join (select cat, any_value(hier[1]) as top from ov group by cat) k on k.cat = o.kind
        )""")
    log("merged:", con.execute("select count(*) from merged").fetchone()[0], "places")
    # Overture has several records of many places (the Louvre has five): one per name and kind within
    # about 300 m, the most certain, with the others' sources, diet tags and details
    con.execute("""
        create or replace table merged as
        with g as (
            select *, lower(regexp_replace(trim(name), '\\s+', ' ', 'g')) as nk,
                   cast(round(lat * 300) as bigint) as gy, cast(round(lon * 300) as bigint) as gx
            from merged
        ), r as (
            select *, row_number() over (partition by nk, gy, gx, top order by conf desc, pid) as rn,
                   bit_or(src) over w as src_all, bit_or(diet) over w as diet_all,
                   max(hours) over w as hours_any, max(cuisine) over w as cuisine_any, max(website) over w as web_any,
                   max(phone) over w as phone_any, max(street) over w as street_any
            from g
            window w as (partition by nk, gy, gx, top)
        )
        select pid, lat, lon, name, cat, top, alt, diet_all as diet, src_all as src, conf, chain,
               coalesce(street, street_any) as street, locality, coalesce(phone, phone_any) as phone,
               coalesce(website, web_any) as website, coalesce(hours, hours_any) as hours,
               coalesce(cuisine, cuisine_any) as cuisine
        from r where rn = 1""")
    log("merged, duplicates folded:", con.execute("select count(*) from merged").fetchone()[0], "places")

    countries, regions, cities = load_geonames(a.geonames)
    names = {}
    for c in cities:
        for k in city_keys(c[1], c[2], c[3], c[8]):
            names.setdefault(k, []).append(c[0])
    cities_by_id = {c[0]: c for c in cities}
    region_keys = {}  # (country, admin1 name) -> keys a question or a guide title may use for them
    for c in cities:
        key = (c[6], c[7])
        if key not in region_keys:
            ks = {norm_key(countries.get(c[6], ""))}
            if c[7]:
                ks.add(norm_key(c[7]))
            region_keys[key] = ks
    log("cities:", len(cities), "names:", sum(len(v) for v in names.values()))

    if a.voyage:
        match_guide(con, a.voyage, names, cities_by_id, region_keys, bbox)
    else:
        con.execute("create or replace table guide_match(article varchar, section varchar, tier varchar, listing varchar, pid bigint)")

    if a.wiki:
        big = {norm_key(c[1]) for c in cities if c[8] >= 15000} | {c[1].lower() for c in cities if c[8] >= 15000}
        match_fame(con, a.wiki, sorted(big))
    else:
        con.execute("create or replace table fame(pid bigint, views bigint, title varchar)")

    write_sqlite(con, a.out, countries, regions, cities, names)


def fame_bonus(views):
    """places.py's score for a place with its own Wikipedia article of [views] monthly views."""
    return min(3.0, math.log10(1 + views / 100.0)) if views else 0.0


def static_rank(src, conf, chain, website, fame=0):
    """The part of places.py's score that does not depend on the question, times 100: a lookup
    without a diet takes its candidates in this order."""
    return (conf + (150 if src & SRC_GUIDE else 0) + (50 if src & SRC_OSM and src & SRC_OVERTURE else 0)
            + (20 if website else 0) - (100 if chain else 0) + round(100 * fame_bonus(fame)))


# Wikipedia title qualifiers that say the article is about a place of this kind
QUALIFIERS = {"restaurant", "cafe", "café", "bar", "pub", "hotel", "museum", "gallery", "art gallery", "hospital",
              "market", "bakery", "brewery", "building", "castle", "palace", "church", "cathedral", "temple",
              "mosque", "park", "zoo", "aquarium", "theatre", "theater", "monument", "shop", "store", "department store"}


# what an article about a place says it is, early on ("... is a sushi restaurant in Ginza", "... is
# an art museum in Madrid"); an article about a dish, a TV show or a city says something else
PLACE_TYPES = re.compile(
    r"\b(restaurant|museum|gallery|hotel|hostel|pizzeria|trattoria|bar|pub|caf[e\u00e9]|coffeehouse|tea ?house|"
    r"bakery|patisserie|brewery|winery|distillery|food hall|hospital|clinic|pharmacy|market|park|garden|palace|"
    r"castle|church|basilica|cathedral|chapel|temple|shrine|mosque|synagogue|monastery|abbey|convent|monument|"
    r"memorial|mausoleum|building|skyscraper|zoo|aquarium|theatre|theater|opera house|concert hall|stadium|arena|"
    r"square|plaza|bridge|tower|library|station|airport|shop|store|department store|mall|shopping (centre|center)|"
    r"nightclub|club|venue|landmark|ship|fort|fortress|citadel|ruins|archaeological site|beach|resort|spa|inn|"
    r"cemetery|observatory|planetarium|amusement park|theme park|coffee ?house|memorial|site|amphitheat(re|er)|"
    r"gate|arch|statue|sculpture|lighthouse|pier|fountain|waterfall|lake|canyon|cave|volcano|glacier|island)s?\b", re.I)
# what a subject is when it is not a place, though its description may name one ("a 2014 comedy
# film" about a hotel, "a professional football club", "a rock band")
NOT_PLACE = re.compile(
    r"\b(film|movie|band|duo|trio|series|sitcom|television|novel|book|album|song|video game|company|corporation|"
    r"brand|chain|franchise|league|competition|tournament|championship|musician|singer|actor|actress|rapper|"
    r"footballer|politician|character|magazine|newspaper|website|list of|club competition|dealer|historian|writer|"
    r"author|broadcaster|artist|chef|businessman|businesswoman|entrepreneur|architect|"
    r"(football|soccer|basketball|rugby|cricket|hockey|baseball|sports|athletic|multi-sport) (club|team|franchise))s?\b",
    re.I)
# a description that ends in a kind of town or region is about one ("a cathedral city", "a desert
# resort city"), not about a place to go to
ADMIN_END = re.compile(r"\b(city|town|village|state|province|county|municipality|borough|suburb|neighbou?rhood|"
                       r"region|country|nation|capital)s?\W*$", re.I)
# the words after the first "is"/"are" of the first sentence, up to where the description of what
# the subject is ends ("a sushi restaurant| in Ginza", "one of the oldest coffeehouses| in Paris";
# not at a comma: "a 102-story, Art Deco-style supertall skyscraper| in")
PREDICATE = re.compile(r"\b(is|are)\s+(.*)", re.I | re.S)
HEAD_END = re.compile(r"\s(?:in|on|at|from|located|based|near|that|which|who|whose|where|by|built|founded|opened|designed|"
                      r"owned|operated|formed|created|written|directed|released|established|spanning|covering|serving|"
                      r"bordering|comprising|consisting)\s|[;:]")


FOOD_TYPES = re.compile(r"\b(restaurant|pizzeria|trattoria|bar|pub|caf[e\u00e9]|coffeehouse|tea ?house|bakery|patisserie|"
                        r"brewery|winery|distillery|food hall|nightclub|coffee ?house)s?\b", re.I)
LODGING_TYPES = re.compile(r"\b(hotel|hostel|inn|resort|guest ?house|motel)s?\b", re.I)


def lead_kinds(text):
    """What the article's first sentence says the place is: a subset of {"food", "lodging",
    "other"} ("The British Museum is a public museum ..." -> other), empty when it is not a place
    ("The European Union is a supranational political and economic union ...", "... is an American
    luxury hotel chain"). An article starts with its infobox ("Key facts: ...") and then a
    "# Title" line; the lead follows that."""
    m = re.search(r"^# [^\n]*\n+", text, re.M)
    lead = text[m.end():] if m else text
    lead = lead[:1500]
    lines = lead.split("\n")
    while lines and "\u00b0" in lines[0] and len(lines[0]) < 80:  # a line of coordinates
        lines.pop(0)
    lead = "\n".join(lines)
    for _ in range(2):  # names in other scripts and pronunciations, which can hold an "is"
        lead = re.sub(r"\s*\([^()]*\)", "", lead)
    first = re.split(r"(?<=[a-z0-9)\]])\.(\s|$)", lead[:600], maxsplit=1)[0]
    if re.search(r"\b(chain|brand|franchise|company)\b", first):
        return set()
    p = PREDICATE.search(first)
    if not p:
        return set()
    # an article about a kind of thing, not one place: "A World Heritage Site is a landmark ...",
    # "World Heritage Sites are landmarks ..." (but "The Petronas Towers are twin skyscrapers")
    title = m.group(0)[2:].strip() if m else ""
    subject = first[:p.start()].strip()
    if re.match(r"(a|an)\s", subject, re.I) and not re.match(r"(a|an)\s", title, re.I):
        return set()
    if p.group(1).lower() == "are" and not re.match(r"the\s", subject, re.I):
        return set()
    head = HEAD_END.split(p.group(2), maxsplit=1)[0]
    if NOT_PLACE.search(head) or any(ADMIN_END.search(part) for part in re.split(r",|\sand\s", head)):
        return set()
    kinds = set()
    for m in PLACE_TYPES.finditer(head):
        w = m.group(0)
        kinds.add("food" if FOOD_TYPES.fullmatch(w) else "lodging" if LODGING_TYPES.fullmatch(w) else "other")
    return kinds


COORDS = re.compile(r"(\d+(?:\.\d+)?)\s*°\s*([NS])\W{1,6}(\d+(?:\.\d+)?)\s*°\s*([EW])")


def article_coords(text):
    """The decimal coordinates in an article's infobox ("35.6726611°N 139.7640389°E"), or None."""
    for m in COORDS.finditer(text):
        if "." not in m.group(1):
            continue  # degrees-minutes-seconds come first; the decimal pair follows
        lat = float(m.group(1)) * (1 if m.group(2) == "N" else -1)
        lon = float(m.group(3)) * (1 if m.group(4) == "E" else -1)
        return lat, lon
    return None


def match_fame(con, wiki_path, big_city_names):
    """fame(pid, views): places whose own name is a Wikipedia article about a place (or a
    redirect to one), with that article's monthly views. The article's title must be a proper
    name ("White pizza" is not), a redirect must share a distinctive word with it ("Las Vegan" is
    a misspelling of Las Vegas), and the article's first lines must name a kind of place (a TV show
    called "Sunday Brunch" is not a restaurant)."""
    con.execute("install sqlite; load sqlite")
    con.execute(f"attach '{wiki_path}' as w (type sqlite, read_only)")
    con.execute("""
        create or replace table wt as
        with t as (
            select a.title as t, a.id as aid, a.title as target from w.articles a
            union all
            select r.title, a.id, a.title from w.redirects r join w.articles a on a.id = r.article_id
        )
        select lower(t) as t, aid, lower(target) as target,
               len(regexp_extract_all(target, '(^|\\s)\\p{Lu}')) as caps
        from t""")
    con.execute("create or replace table wa as select id as aid, title, views, block_id, off, len from w.articles")
    con.execute("detach w")
    con.execute("create or replace temp table big_city(n varchar)")
    con.executemany("insert into big_city values (?)", [(n,) for n in big_city_names])
    quals = "(" + ",".join("'" + q.replace("'", "''") + "'" for q in QUALIFIERS) + ")"
    generic = "[" + ",".join("'" + g + "'" for g in GENERIC) + "]"
    words = "list_filter(string_split(regexp_replace({x}, '[^a-z0-9]+', ' ', 'g'), ' '), w -> length(w) >= 5 and not list_contains(" + generic + ", w))"
    con.execute(f"""
        create or replace table fame_cand as
        with names as (
            select pid, lower(trim(name)) as n, chain from merged
        ), counts as (
            -- in how many places around the world (0.1-degree cells) the name is found: Overture has
            -- several records of the Louvre, all in one place; a chain is in many
            select n, count(distinct cast(floor(lat * 10) as bigint) * 4000 + cast(floor(lon * 10) as bigint)) as c
            from (select lower(trim(name)) as n, lat, lon from merged) group by n
        ), cand as (
            select m.pid, m.n, c.c from names m join counts c using (n)
            where m.chain = 0 and c.c <= 5 and m.n not in (select n from big_city)
        ), plain as (
            -- the name is the article's title, a proper name of two words or more, or a redirect
            -- to one that shares a distinctive word with it
            select cand.pid, wt.aid from cand join wt on wt.t = cand.n
            where strpos(cand.n, ' ') > 0 and wt.caps >= least(2, len(string_split(wt.target, ' ')))
              and (wt.t = wt.target or list_has_any({words.format(x='wt.target')}, {words.format(x='cand.n')}))
        ), qualified as (
            -- "Noma (restaurant)"
            select cand.pid, wt.aid from cand join wt on wt.t like cand.n || ' (%)'
            where regexp_extract(wt.t, '\\(([^)]*)\\)$', 1) in {quals}
        )
        select distinct pid, aid from (select * from plain union all select * from qualified)""")
    aids = [r[0] for r in con.execute("select distinct aid from fame_cand").fetchall()]
    log("fame: candidate articles", len(aids))
    # the lead of each candidate article, block by block
    wiki = sqlite3.connect(f"file:{wiki_path}?mode=ro", uri=True)
    zd = zstandard.ZstdDecompressor()
    rows = con.execute(f"select aid, block_id, off, len from wa where aid in (select distinct aid from fame_cand) order by block_id").fetchall()
    good, block_id, raw = set(), None, None
    for aid, bid, off, ln in rows:
        if bid != block_id:
            raw = zd.decompress(wiki.execute("select zdata from blocks where id = ?", (bid,)).fetchone()[0])
            block_id = bid
        head = raw[off:off + min(ln, 4000)].decode("utf-8", "ignore")
        at = article_coords(head)
        for k in lead_kinds(head):
            good.add((aid, k, at[0] if at else None, at[1] if at else None))
    con.execute("create or replace temp table fame_good(aid bigint, kind varchar, alat double, alon double)")
    con.executemany("insert into fame_good values (?, ?, ?, ?)", sorted(good, key=lambda g: (g[0], g[1])))
    # the article is about this kind of place: a restaurant's about a restaurant (not "Amber
    # Palace", a restaurant, and Amber Fort), a hotel's about lodging, the rest about neither
    con.execute("""
        create or replace table fame as
        select c.pid, max(a.views) as views, arg_max(a.title, a.views) as title
        from fame_cand c join merged m using (pid) join fame_good g using (aid) join wa a using (aid)
        where g.kind = case m.top when 'food_and_drink' then 'food' when 'lodging' then 'lodging' else 'other' end
          -- where the article puts it: not a record of "Chateau de Versailles" in central Paris, nor a
          -- London hotel with the New York Waldorf Astoria's article
          and (g.alat is null or sqrt(pow((m.lat - g.alat) * 110.54, 2)
                                      + pow((m.lon - g.alon) * 111.32 * cos(radians(m.lat)), 2)) <= 10)
        group by c.pid""")
    log("places with a Wikipedia article of their own:", con.execute("select count(*) from fame").fetchone()[0],
        "of", len(aids), "candidate articles", len({g[0] for g in good}), "about a place")


def clean_website(w):
    if not w:
        return None
    w = re.sub(r"^https?://(www\.)?", "", w.strip()).rstrip("/")
    return w[:100] or None


def write_sqlite(con, out, countries, regions, cities, names):
    tmp = out + ".part"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript("""
        PRAGMA page_size = 4096;
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE kinds(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, parents TEXT NOT NULL);
        CREATE TABLE places(id INTEGER PRIMARY KEY, cell INTEGER NOT NULL, lat5 INTEGER NOT NULL,
            lon5 INTEGER NOT NULL, name TEXT NOT NULL, kind INTEGER NOT NULL, alt TEXT,
            diet INTEGER NOT NULL, src INTEGER NOT NULL, conf INTEGER NOT NULL, chain INTEGER NOT NULL,
            street TEXT, locality TEXT, phone TEXT, website TEXT, hours TEXT, cuisine TEXT,
            rank INTEGER NOT NULL, fame INTEGER NOT NULL, wiki TEXT);
        CREATE TABLE guide(place INTEGER NOT NULL, article TEXT NOT NULL, section TEXT NOT NULL,
            tier TEXT, listing TEXT NOT NULL);
        CREATE TABLE cities(id INTEGER PRIMARY KEY, name TEXT NOT NULL, country TEXT NOT NULL,
            admin1 TEXT, lat REAL NOT NULL, lon REAL NOT NULL, population INTEGER NOT NULL,
            capital INTEGER NOT NULL);
        CREATE TABLE city_names(key TEXT NOT NULL, city INTEGER NOT NULL, PRIMARY KEY(key, city)) WITHOUT ROWID;
        CREATE TABLE countries(code TEXT PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE region_names(key TEXT NOT NULL, country TEXT NOT NULL, admin1 TEXT NOT NULL,
            PRIMARY KEY(key, country, admin1)) WITHOUT ROWID;
    """)
    # kinds: every category with the categories above it (Overture's hierarchy)
    hier = {}
    for cat, h in con.execute("select cat, any_value(hier) from ov where cat is not null group by cat").fetchall():
        hier[cat] = [x for x in (h or []) if x and x != cat]
    for (cat,) in con.execute("select distinct cat from merged").fetchall():
        hier.setdefault(cat, [])
    kind_id = {}
    for i, cat in enumerate(sorted(hier), 1):
        kind_id[cat] = i
        db.execute("insert into kinds values (?,?,?)", (i, cat, ",".join(hier[cat])))
    # places in grid-cell order, so one city's places sit together in the file
    guide = {}
    for article, section, tier, listing, pid in con.execute("select article, section, tier, listing, pid from guide_match").fetchall():
        guide.setdefault(pid, []).append((article, section, tier, listing))
    cur = con.execute(f"""
        select pid, lat, lon, name, cat, alt, diet, src, conf, chain, street, locality, phone, website, hours, cuisine,
               coalesce(fame.views, 0) as fame, fame.title as wiki
        from merged left join fame using (pid)
        order by cast(floor((lat + 90.0) * 20.0) as bigint) * {CELL_COLS} + cast(floor((lon + 180.0) * 20.0) as bigint), pid""")
    n = 0
    new_id = 0
    while True:
        batch = cur.fetchmany(100_000)
        if not batch:
            break
        prow, grow = [], []
        for (pid, lat, lon, name, cat, alt, diet, src, conf, chain, street, locality, phone, website, hours, cuisine, fame, wiki) in batch:
            new_id += 1
            gl = guide.get(pid)
            if gl:
                src |= SRC_GUIDE
                grow.extend((new_id,) + g for g in gl)
            web = clean_website(website)
            prow.append((new_id, cell_of(lat, lon), round(lat * 1e5), round(lon * 1e5), name.strip(), kind_id[cat],
                         alt or None, diet, src, conf, chain, street, locality, phone, web,
                         hours, cuisine, static_rank(src, conf, chain, web, fame), fame, wiki))
        db.executemany("insert into places values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", prow)
        db.executemany("insert into guide values (?,?,?,?,?)", grow)
        n += len(prow)
        log(f"  wrote {n} places")
    db.execute("create index places_cell on places(cell)")
    db.execute("create index guide_place on guide(place)")
    db.execute("create index cities_region on cities(country, admin1, population)")
    db.executemany("insert into cities values (?,?,?,?,?,?,?,?)",
                   [(c[0], c[1], c[6], c[7], c[4], c[5], c[8], c[9]) for c in cities])
    db.executemany("insert or ignore into city_names values (?,?)", [(k, i) for k, ids in names.items() for i in ids])
    db.executemany("insert into countries values (?,?)", sorted(countries.items()))
    rn = set()
    for code, cname in countries.items():
        rn.add((norm_key(cname), code, ""))
    for code, rname in regions.items():
        cc, sub = code.split(".", 1)
        rn.add((norm_key(rname), cc, rname))
        # "Cambridge, MA", "Vancouver, BC", "Perth, WA": the state and province codes
        if cc in ("US", "CA", "AU") and sub.isalpha():
            rn.add((sub.lower(), cc, rname))
    for key, cc in ALIASES.items():
        rn.add((key, cc, ""))
    db.executemany("insert or ignore into region_names values (?,?,?)", [r for r in rn if r[0]])
    meta = {
        "format": "4",
        "built": time.strftime("%Y-%m-%d"),
        "cell_deg": str(CELL_DEG),
        "sources": "Overture Maps places 2026-09-23.1 (CDLA-Permissive-2.0; Foursquare records Apache-2.0; "
                   "AllThePlaces CC0-1.0); OpenStreetMap diet-tagged places (ODbL 1.0); GeoNames cities1000 "
                   "(CC BY 4.0); Wikivoyage listing names (voyage.db); English Wikipedia article titles and "
                   "Wikimedia pageviews (wiki.db)",
        "license": "ODbL-1.0",
        "attribution": "(c) OpenStreetMap contributors; Overture Maps Foundation; GeoNames; Wikivoyage",
    }
    db.executemany("insert into meta values (?,?)", sorted(meta.items()))
    db.commit()
    db.execute("vacuum")
    db.close()
    os.replace(tmp, out)
    log("wrote", out, os.path.getsize(out) // 1_000_000, "MB")


# what questions call countries beyond GeoNames' own names
ALIASES = {"usa": "US", "us": "US", "united states of america": "US", "america": "US",
           "uk": "GB", "britain": "GB", "great britain": "GB", "england": "GB", "scotland": "GB",
           "wales": "GB", "northern ireland": "GB", "czechia": "CZ", "holland": "NL",
           "the netherlands": "NL", "south korea": "KR", "korea": "KR", "north korea": "KP",
           "russia": "RU", "vietnam": "VN", "laos": "LA", "iran": "IR", "syria": "SY",
           "turkiye": "TR", "ivory coast": "CI", "uae": "AE", "emirates": "AE", "hong kong": "HK",
           "taiwan": "TW", "bolivia": "BO", "venezuela": "VE", "tanzania": "TZ", "moldova": "MD",
           "macedonia": "MK", "north macedonia": "MK", "palestine": "PS", "vatican": "VA"}


if __name__ == "__main__":
    main()
