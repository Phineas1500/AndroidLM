#!/usr/bin/env python3
"""Answering "where to eat, drink or stay" questions from places.db (build_places.py).

  parse(question)          -> PlaceAsk or None: what kind of place, which diet or cuisine, where
  locate(db, ask)          -> the city the question names (or None)
  find(db, ask, city, ...) -> the matching places, ranked
  context(places, ...)     -> the numbered list the model reads

A question takes this route only when it asks for places of a kind (restaurants, cafés, bars,
bakeries, hotels...) or for a diet or cuisine, somewhere this file can place (a city in GeoNames,
or the phone's own position for "near me"). Everything else goes to the Wikipedia pipeline.

The app runs the same logic in Kotlin (app-android/research/.../Places.kt); keep them in step.

    python scripts/places.py places.db "best vegan restaurants in Lisbon" [--voyage voyage.db]
"""
import argparse
import math
import re
import sqlite3
import sys
from dataclasses import dataclass, field

sys.path.insert(0, __import__("os").path.dirname(__file__))
from build_places import (CELL_COLS, GLUTEN_FREE, HALAL, KOSHER, SRC_GUIDE, SRC_OSM, SRC_OVERTURE,  # noqa: E402
                          VEGAN_LIMITED, VEGAN_NO, VEGAN_ONLY, VEGAN_YES, VEGETARIAN_LIMITED,
                          VEGETARIAN_ONLY, VEGETARIAN_YES, city_radius_km, norm_key)

# ---- the question -----------------------------------------------------------------------------

# what the question asks for: a group of categories (matched against a place's category and the
# categories above it)
GROUPS = {
    "eat": ("restaurant", "casual_eatery", "food_and_drink", "non_alcoholic_beverage_venue"),
    "cafe": ("cafe", "coffee_shop", "coffee_roastery", "non_alcoholic_beverage_venue", "tea_room",
             "bubble_tea_shop", "breakfast_and_brunch_restaurant"),
    "drink": ("alcoholic_beverage_venue",),
    "sweet": ("bakery", "patisserie", "dessert_shop", "ice_cream_shop", "donut_shop", "cupcake_shop",
              "candy_store", "chocolatier", "frozen_yoghurt_shop", "gelato"),
    "stay": ("lodging",),
}
# words -> group; a word ending in "s" also matches without it
KIND_WORDS = [
    ("places to eat", "eat"), ("place to eat", "eat"), ("where to eat", "eat"), ("food", "eat"),
    ("restaurant", "eat"), ("eatery", "eat"), ("eateries", "eat"), ("dinner", "eat"), ("lunch", "eat"),
    ("meal", "eat"), ("dining", "eat"), ("eat", "eat"), ("brunch", "cafe"), ("breakfast", "cafe"),
    ("cafe", "cafe"), ("café", "cafe"), ("coffee", "cafe"), ("tea house", "cafe"),
    ("bar", "drink"), ("pub", "drink"), ("beer", "drink"), ("brewery", "drink"), ("breweries", "drink"),
    ("cocktail", "drink"), ("wine", "drink"), ("nightlife", "drink"), ("drinks", "drink"),
    ("bakery", "sweet"), ("bakeries", "sweet"), ("pastry", "sweet"), ("pastries", "sweet"),
    ("dessert", "sweet"), ("ice cream", "sweet"), ("gelato", "sweet"), ("donut", "sweet"),
    ("hotel", "stay"), ("hostel", "stay"), ("guesthouse", "stay"), ("guest house", "stay"),
    ("accommodation", "stay"), ("place to stay", "stay"), ("places to stay", "stay"),
    ("where to stay", "stay"), ("where should i stay", "stay"), ("where can i stay", "stay"),
    ("where to sleep", "stay"), ("bed and breakfast", "stay"), ("b&b", "stay"),
]
DIET_WORDS = [
    ("plant based", "vegan"), ("plant-based", "vegan"), ("vegan", "vegan"),
    ("vegetarian", "vegetarian"), ("veggie", "vegetarian"),
    ("gluten free", "gluten_free"), ("gluten-free", "gluten_free"), ("celiac", "gluten_free"),
    ("coeliac", "gluten_free"), ("halal", "halal"), ("kosher", "kosher"),
]
# words for one kind of place within a group (a softer filter than the group: dropped when it
# leaves fewer than MIN_SUB places), and cuisine words whose category is not "<word>_restaurant"
SUB_WORDS = {"hostel": "hostel", "bed and breakfast": "bed_and_breakfast", "b&b": "bed_and_breakfast",
             "guest house": "guest_house", "guesthouse": "guest_house", "campsite": "campground",
             "camping": "campground", "hotel": "hotel", "bakery": "bakery", "bakeries": "bakery",
             "ice cream": "ice_cream_shop", "gelato": "ice_cream_shop", "donut": "donut_shop",
             "dessert": "dessert_shop", "brewery": "brewery", "breweries": "brewery", "pub": "pub",
             "brunch": "breakfast_and_brunch_restaurant", "breakfast": "breakfast_and_brunch_restaurant",
             "coffee": "coffee_shop", "steak": "steakhouse", "tapas": "tapas_bar",
             "dim sum": "dim_sum_restaurant", "noodle": "noodles_restaurant", "taco": "taco_restaurant",
             "burger": "burger_restaurant", "dumpling": "dumpling_restaurant", "curry": "indian_restaurant",
             "street food": "food_stand", "food truck": "food_truck_stand", "juice": "smoothie_juice_bar",
             "bubble tea": "bubble_tea_shop", "wine bar": "wine_bar", "cocktail bar": "cocktail_bar"}
RECOMMEND = re.compile(r"\b(best|good|great|top|recommend\w*|suggest\w*|where|find|any|list|options?|"
                       r"places?|spots?|cheap|affordable|nice|popular|famous|must|should i|can i|could i|"
                       r"favou?rite|near|nearby)\b")
# a question about a place's food or drink in general, not a request for places
GENERAL = re.compile(r"\b(what('s| is| are) (the )?\w+( \w+)? (like|scene)|typical|traditional|cuisine of|dishes|known for|famous for|specialit(y|ies)|must[- ]try)\b")
KNOWLEDGE = re.compile(r"\b(history|origins?|invented|why|who (founded|owns|opened)|when (was|did)|how (did|do|does|is|are) \w+ (made|cooked|prepared))\b")
HERE = re.compile(r"\b(near me|nearby|around me|around here|near here|close to me|close by|where i am|"
                  r"my location|this city|this town|city i am (currently )?in|city i'm (currently )?in|"
                  r"current city|my area|in my city)\b")
ANCHOR = re.compile(r"\b(in|near|around|at|of|for|across|throughout)\s+(?:the\s+)?", re.I)

MIN_SUB = 3
CANDIDATES = 400  # rows read for a lookup without a diet (by the static rank, build_places.py)
CHEAP = re.compile(r"\b(cheap|budget|affordable|inexpensive|low[- ]cost)\b")
FANCY = re.compile(r"\b(upscale|luxury|luxurious|fine dining|splurge|expensive|fancy|high[- ]end|michelin)\b")
# categories that are diets, not cuisines
DIET_CATS = {"vegan_restaurant", "vegetarian_restaurant", "gluten_free_restaurant", "halal_restaurant",
             "kosher_restaurant", "special_diet_restaurant", "health_food_restaurant", "live_and_raw_food_restaurant"}

DIET_MASK = {"vegan": 0, "vegetarian": 0, "gluten_free": GLUTEN_FREE, "halal": HALAL, "kosher": KOSHER}
DIET_KIND = {"vegan": "vegan_restaurant", "vegetarian": "vegetarian_restaurant", "gluten_free": "gluten_free_restaurant",
             "halal": "halal_restaurant", "kosher": "kosher_restaurant"}
VEGAN_NAME = re.compile(r"\b(vegan[oa]?s?|v[eé]gane?s?|plant[- ]based|vegana?s?)\b", re.I)
VEGETARIAN_NAME = re.compile(r"\b(vegetarian[oa]?s?|v[eé]g[eé]tarien(ne)?s?|veggie|vegetariano)\b", re.I)


@dataclass
class PlaceAsk:
    group: str                 # eat / cafe / drink / sweet / stay
    diet: str = None           # vegan / vegetarian / gluten_free / halal / kosher
    sub: str = None            # one category within the group, e.g. ramen_restaurant, hostel
    price: str = None          # "Budget" or "Splurge" (the travel guide's price tiers)
    here: bool = False         # "near me": the phone's position
    restaurant: bool = False   # the question says "restaurant(s)": prefer restaurants to cafes and shops
    question: str = ""


def _has(text, phrase):
    p = re.escape(phrase)
    return re.search(rf"(?<![a-z0-9]){p}s?(?![a-z0-9])", text) is not None


def parse(question, cuisines=()):
    """What the question asks for, or None when it does not ask for places. [cuisines]: the
    category names in places.db (to recognise "ramen", "Thai food"...)."""
    q = question.strip()
    low = q.lower()
    if KNOWLEDGE.search(low):
        return None
    diet = next((d for w, d in DIET_WORDS if _has(low, w)), None)
    group = next((g for w, g in KIND_WORDS if _has(low, w)), None)
    sub = None
    for w, cat in SUB_WORDS.items():
        if _has(low, w):
            sub = cat
            break
    if sub is None:
        words = set(re.findall(r"[a-z]+", low))
        for cat in cuisines:
            if cat.endswith("_restaurant") and cat not in DIET_CATS:
                stem = cat[: -len("_restaurant")].replace("_", " ")
                if stem and stem not in ("fast food", "casual", "family") and _has(low, stem) \
                        and (" " in stem or stem in words):
                    sub = cat
                    break
    if group is None and diet is None and sub is None:
        return None
    if group is None:
        group = "eat"
    # a request for places: says so (best, where, recommend, places...), or names places in the
    # plural, or asks for a diet or a kind of place; "what is the food like" is not one
    asks = RECOMMEND.search(low) or re.search(r"\b(restaurants|cafes|cafés|bars|pubs|hotels|hostels|bakeries)\b", low)
    if not asks and (GENERAL.search(low) or not (diet or sub)):
        return None
    here = HERE.search(low) is not None
    if not here and not place_candidates(q):
        return None
    price = "Budget" if CHEAP.search(low) else "Splurge" if FANCY.search(low) else None
    return PlaceAsk(group, diet, sub, price, here, _has(low, "restaurant"), q)


CAPITALISED = re.compile(r"[A-Z][\w'\u2019.-]*(?:\s+(?:[A-Z][\w'\u2019.-]*|de|da|do|del|la|le|el|of|upon|am|sur|an|on|y|i)(?=\s+[A-Z]))*(?:\s+[A-Z][\w'\u2019.-]*)?")
CLAUSE_END = re.compile(r"[?!;:()\[\]]|\s(?:that|which|with|for|open|serving|where|who|to|and|or|but|if|while|when|because|so|I)\s")
# capitalised words that start questions and are also names of small towns
NOT_PLACES = {"best", "tell", "where", "what", "which", "who", "how", "can", "could", "should", "would",
              "any", "list", "give", "show", "find", "recommend", "suggest", "please", "i", "is", "are",
              "top", "good", "great", "cheap", "vegan", "vegetarian", "halal", "kosher", "some", "the"}


def place_candidates(q):
    """Where the question may name a place, most likely first: the words after each "in / near /
    around ..." (the last one first), then each run of capitalised words. (text, anchored)"""
    out = []
    for m in reversed(list(ANCHOR.finditer(q))):
        rest = CLAUSE_END.split(q[m.end():])[0].strip(" .,'\"")
        if rest:
            out.append((rest, True))
    for m in CAPITALISED.finditer(q):
        out.append((m.group(0).strip(" .,'\""), False))
    return out


# ---- where ------------------------------------------------------------------------------------

@dataclass
class City:
    id: int
    name: str
    country: str
    admin1: str
    lat: float
    lon: float
    population: int
    country_name: str = ""

    @property
    def radius_km(self):
        return city_radius_km(self.population)

    def label(self):
        parts = [self.name]
        if self.admin1 and self.country in ("US", "CA", "AU", "IN", "BR", "MX") and self.admin1 != self.name:
            parts.append(self.admin1)
        parts.append(self.country_name or self.country)
        return ", ".join(parts)


def locate(db, ask):
    """The city the question names: for each candidate (place_candidates), the longest run of
    words at its start that is a city name, narrowed by a country or region named after it
    ("Paris, Texas"), else the most populous. A candidate after "in/near/around" is taken when
    it is capitalised or a city of 100,000 or more; a bare capitalised run only in the latter case."""
    for text, anchored in place_candidates(ask.question):
        words = norm_key(text).split()
        capital = text[:1].isupper()
        for n in range(min(len(words), 5), 0, -1):
            key = " ".join(words[:n])
            if not anchored and key in NOT_PLACES:
                continue
            ids = [r[0] for r in db.execute("select city from city_names where key = ?", (key,))]
            if not ids:
                continue
            cities = [City(*r) for r in db.execute(
                f"select id, name, country, admin1, lat, lon, population from cities where id in ({','.join('?' * len(ids))})", ids)]
            rest = words[n:]
            if rest:
                hinted = _hinted(db, cities, rest)
                if hinted:
                    cities = hinted
            c = max(cities, key=lambda c: (c.population, -c.id))
            if not ((anchored and capital) or c.population >= 100_000):
                continue
            row = db.execute("select name from countries where code = ?", (c.country,)).fetchone()
            c.country_name = row[0] if row else ""
            return c
    return None


def _hinted(db, cities, rest):
    """The cities in the country or region named by some run of the words after the city name."""
    for i in range(len(rest)):
        for j in range(len(rest), i, -1):
            key = " ".join(rest[i:j])
            regions = db.execute("select country, admin1 from region_names where key = ?", (key,)).fetchall()
            if regions:
                ok = [c for c in cities if any(c.country == cc and (a1 == "" or a1 == c.admin1) for cc, a1 in regions)]
                if ok:
                    return ok
    return None


# ---- which places -----------------------------------------------------------------------------

@dataclass
class Place:
    id: int
    name: str
    kind: str
    kinds: tuple          # the category and the ones above it
    alt: str
    diet: int
    src: int
    conf: int
    chain: int
    lat: float
    lon: float
    street: str
    locality: str
    phone: str
    website: str
    hours: str
    cuisine: str
    km: float = 0.0
    tier: int = 9
    score: float = 0.0
    sub: bool = False
    guide: list = field(default_factory=list)   # (article, section, tier, listing)
    why: list = field(default_factory=list)      # short reasons, for the list and the model


def cells_around(lat, lon, km):
    dlat = km / 110.54
    dlon = km / (111.32 * max(math.cos(math.radians(lat)), 0.01))
    r0, r1 = int(math.floor((lat - dlat + 90.0) * 20.0)), int(math.floor((lat + dlat + 90.0) * 20.0))
    c0, c1 = int(math.floor((lon - dlon + 180.0) * 20.0)), int(math.floor((lon + dlon + 180.0) * 20.0))
    cells = []
    for r in range(max(r0, 0), min(r1, 3599) + 1):
        for c in range(c0, c1 + 1):
            cells.append(r * CELL_COLS + (c % CELL_COLS))
    return cells


def distance_km(lat1, lon1, lat2, lon2):
    x = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2))
    y = math.radians(lat2 - lat1)
    return 6371.0 * math.hypot(x, y)


def _kinds(db):
    out = {}
    for i, name, parents in db.execute("select id, name, parents from kinds"):
        out[i] = (name, tuple(p for p in parents.split(",") if p) + (name,))
    return out


def in_group(ask, path):
    """A category (with the categories above it) belongs to what the question asks for."""
    if "lodging" in path:
        return ask.group == "stay"
    if ask.group == "eat":
        # drinks-only venues only when a diet or a kind of place narrows the list, and the
        # question does not ask for restaurants
        return "food_and_drink" in path and (
            "alcoholic_beverage_venue" not in path or (bool(ask.diet or ask.sub) and not ask.restaurant))
    return any(g in path for g in GROUPS[ask.group])


DIET_KINDS = {"vegan": ("vegan_restaurant", "vegetarian_restaurant"), "vegetarian": ("vegan_restaurant", "vegetarian_restaurant"),
              "gluten_free": ("gluten_free_restaurant",), "halal": ("halal_restaurant",), "kosher": ("kosher_restaurant",)}
DIET_BITS_ANY = {"vegan": VEGAN_ONLY | VEGAN_YES | VEGAN_LIMITED | VEGETARIAN_ONLY,
                 "vegetarian": VEGETARIAN_ONLY | VEGETARIAN_YES | VEGETARIAN_LIMITED | VEGAN_ONLY | VEGAN_YES | VEGAN_LIMITED,
                 "gluten_free": GLUTEN_FREE, "halal": HALAL, "kosher": KOSHER}
DIET_NAME_LIKE = {"vegan": ["%vegan%", "%végan%", "%plant%based%", "%vegetarian%", "%végétarien%", "%veggie%"],
                  "vegetarian": ["%vegetarian%", "%végétarien%", "%veggie%", "%vegan%", "%végan%"],
                  "gluten_free": ["%gluten%free%", "%sin gluten%", "%sans gluten%", "%glutenfrei%", "%senza glutine%", "%sin tacc%"],
                  "halal": ["%halal%"], "kosher": ["%kosher%"]}


def diet_tier(p, diet):
    """0: the place is all about the diet; 1: vegetarian place, for a vegan question; 2: serves it;
    3: limited options; None: not known to serve it."""
    names = (p.kind,) + tuple(a for a in (p.alt or "").split(",") if a)
    if diet == "vegan":
        if p.diet & VEGAN_ONLY or "vegan_restaurant" in names or VEGAN_NAME.search(p.name):
            return 0
        if p.diet & VEGAN_NO:
            return None
        if p.diet & VEGETARIAN_ONLY or "vegetarian_restaurant" in names or VEGETARIAN_NAME.search(p.name):
            return 1
        if p.diet & VEGAN_YES:
            return 2
        if p.diet & VEGAN_LIMITED:
            return 3
        return None
    if diet == "vegetarian":
        if p.diet & (VEGETARIAN_ONLY | VEGAN_ONLY) or {"vegetarian_restaurant", "vegan_restaurant"} & set(names) \
                or VEGETARIAN_NAME.search(p.name) or VEGAN_NAME.search(p.name):
            return 0
        if p.diet & (VEGETARIAN_YES | VEGAN_YES):
            return 2
        if p.diet & (VEGETARIAN_LIMITED | VEGAN_LIMITED):
            return 3
        return None
    if DIET_KIND[diet] in names or any(_like(p.name, pat) for pat in DIET_NAME_LIKE[diet]):
        return 0
    if p.diet & DIET_MASK[diet]:
        return 2
    return None


def _like(text, pattern):
    """SQL LIKE (case-insensitive for ASCII) in Python."""
    rx = "".join(".*" if ch == "%" else "." if ch == "_" else re.escape(ch) for ch in pattern)
    return re.fullmatch(rx, text, re.I | re.S) is not None


DIET_LABEL = {
    ("vegan", 0): "vegan", ("vegan", 1): "vegetarian", ("vegan", 2): "vegan options", ("vegan", 3): "a few vegan options",
    ("vegetarian", 0): "vegetarian", ("vegetarian", 2): "vegetarian options", ("vegetarian", 3): "a few vegetarian options",
}


def find(db, ask, lat, lon, radius_km, limit=12):
    """The places around (lat, lon) that answer [ask], best first."""
    kinds = _kinds(db)
    group = GROUPS[ask.group]
    cells = cells_around(lat, lon, radius_km)
    wanted = [i for i, (name, path) in kinds.items() if in_group(ask, path)]
    sql = (f"select id, name, kind, alt, diet, src, conf, chain, lat5, lon5, street, locality, phone, website, hours, cuisine "
           f"from places where cell in ({','.join('?' * len(cells))}) and kind in ({','.join('?' * len(wanted))})")
    args = cells + wanted
    if ask.diet:
        # a cheap first cut; diet_tier decides
        dk = [i for i, (name, path) in kinds.items() if set(DIET_KINDS[ask.diet]) & set(path)]
        sql += (f" and (diet & ? != 0 or kind in ({','.join('?' * len(dk))}) or alt like ? or alt like ?"
                + "".join(" or name like ?" for _ in DIET_NAME_LIKE[ask.diet]) + ")")
        args += [DIET_BITS_ANY[ask.diet]] + dk + ["%vegan_restaurant%", "%vegetarian_restaurant%"] + DIET_NAME_LIKE[ask.diet]
    sub_rx = re.compile(rf"\b{re.escape(sub_word(ask.sub))}") if ask.sub else None
    # without a diet (which decides the order first) only the best CANDIDATES by the question-free
    # part of the score are read: in a big city that is thousands of rows fewer
    cap = "" if ask.diet else f" order by rank desc, id limit {CANDIDATES}"
    capped = False
    out = None
    if ask.sub:
        # first only the places that may be of the one kind asked for (a superset of is_sub, so
        # the list is the same as filtering everything); all of them when too few are
        dk = [i for i, (name, path) in kinds.items() if ask.sub in path]
        w = sub_word(ask.sub)
        narrowed, n = _places(db, sql + (f" and (kind in ({','.join('?' * len(dk))}) or alt like ? or "
                                         f"replace(lower(cuisine), '_', ' ') like ? or name like ?)") + cap,
                              args + dk + [f"%{ask.sub}%", f"%{w}%", f"%{w}%"], ask, kinds, lat, lon, radius_km, sub_rx)
        subs = [p for p in narrowed if p.sub]
        if len(subs) >= MIN_SUB:
            out, capped = subs, bool(cap) and n == CANDIDATES
    if out is None:
        out, n = _places(db, sql + cap, args, ask, kinds, lat, lon, radius_km, sub_rx)
        capped = bool(cap) and n == CANDIDATES
    if ask.diet in ("vegan", "vegetarian"):
        overrule_branches(out, ask.diet)
    # one kind of place within the group, when there are enough of them
    if ask.sub:
        subs = [p for p in out if p.sub]
        if len(subs) >= MIN_SUB:
            out = subs
    # the travel guide's listings for these places
    if out:
        ids = [p.id for p in out]
        by_id = {p.id: p for p in out}
        for i in range(0, len(ids), 500):
            chunk = ids[i:i + 500]
            for place, article, section, gtier, listing in db.execute(
                    f"select place, article, section, tier, listing from guide where place in ({','.join('?' * len(chunk))}) "
                    f"order by place, rowid", chunk):
                by_id[place].guide.append((article, section, gtier, listing))
    for p in out:
        p.score = score(p, radius_km, ask.price)
    # for "restaurants", restaurants before cafes, bakeries and shops of the same tier
    out.sort(key=lambda p: (p.tier, int(ask.restaurant and "restaurant" not in p.kinds), -p.score, p.id))
    # the same chain at most twice
    seen, picked = {}, []
    for p in out:
        key = norm_key(p.name)
        if p.chain and seen.get(key, 0) >= 1 or seen.get(key, 0) >= 2:
            continue
        seen[key] = seen.get(key, 0) + 1
        picked.append(p)
        if len(picked) >= limit:
            break
    for p in picked:
        p.why = reasons(p, ask)
    return picked, len(out), capped


OPTIONS_BITS = {"vegan": VEGAN_YES | VEGAN_LIMITED, "vegetarian": VEGETARIAN_YES | VEGETARIAN_LIMITED | VEGAN_YES}


def overrule_branches(out, diet):
    """A branch tagged vegan-only (or vegetarian-only) whose same-name branches are tagged as
    serving options is a tagging slip: it gets the options tier. A place whose category or name
    says the diet keeps its tier."""
    groups = {}
    for p in out:
        groups.setdefault(norm_key(p.name), []).append(p)
    for group in groups.values():
        if len(group) < 2 or not any(m.diet & OPTIONS_BITS[diet] for m in group):
            continue
        for m in group:
            if m.tier == 0 and m.kind not in ("vegan_restaurant", "vegetarian_restaurant") \
                    and not VEGAN_NAME.search(m.name) and not VEGETARIAN_NAME.search(m.name):
                m.tier = 2


def _places(db, sql, args, ask, kinds, lat, lon, radius_km, sub_rx):
    """The rows of [sql] as places within the radius that serve the diet asked for, with their
    tier; and how many rows there were."""
    out = []
    rows = db.execute(sql, args).fetchall()
    for (pid, name, kind, alt, diet, src, conf, chain, lat5, lon5, street, locality, phone, website, hours, cuisine) in rows:
        kname, kpath = kinds[kind]
        p = Place(pid, name, kname, kpath, alt, diet, src, conf, chain, lat5 / 1e5, lon5 / 1e5, street, locality,
                  phone, website, hours, cuisine)
        p.km = distance_km(lat, lon, p.lat, p.lon)
        if p.km > radius_km:
            continue
        tier = 0
        if ask.diet:
            t = diet_tier(p, ask.diet)
            if t is None:
                continue
            tier = t
        p.sub = bool(ask.sub) and is_sub(p, ask.sub, sub_rx)
        p.tier = tier
        out.append(p)
    return out, len(rows)


def sub_word(sub):
    return sub.replace("_restaurant", "").replace("_shop", "").replace("_", " ")


def is_sub(p, sub, rx=None):
    """The place is of the one kind asked for: its category (or one above it, or an alternate),
    OpenStreetMap's cuisine, or its name says so. [rx]: the word's pattern, compiled once."""
    if sub in p.kinds or sub in (p.alt or "").split(","):
        return True
    word = sub_word(sub)
    if p.cuisine and word in p.cuisine.lower().replace("_", " "):
        return True
    rx = rx or re.compile(rf"\b{re.escape(word)}")
    return rx.search(p.name.lower()) is not None


def score(p, radius_km, price=None):
    s = p.conf / 100.0
    if p.src & SRC_GUIDE:
        s += 2.0
        if price and any(g[2] and g[2].lower().startswith(price.lower()[:4]) for g in p.guide):
            s += 1.0
    if p.src & SRC_OSM and p.src & SRC_OVERTURE:
        s += 0.5
    if p.website:
        s += 0.2
    if p.chain:
        s -= 1.0
    s -= 0.6 * min(p.km / max(radius_km, 1.0), 1.0)
    return s


def reasons(p, ask):
    why = []
    if ask.diet:
        lab = DIET_LABEL.get((ask.diet, p.tier))
        if lab is None:
            lab = ask.diet.replace("_", "-") if p.tier == 0 else ask.diet.replace("_", "-") + " options"
        why.append(lab)
    if p.guide:
        article, section, gtier, listing = p.guide[0]
        why.append(f"in the Wikivoyage guide ({article}" + (f", {gtier}" if gtier else "") + ")")
    return why


def kind_label(kind):
    return kind.replace("_", " ")


def kind_bits(p):
    """The place's category and why it matches: "vegan restaurant" rather than "vegetarian
    restaurant; vegan" for a vegetarian-listed place that OpenStreetMap marks vegan-only."""
    why = [w for w in p.why if not w.startswith("in the Wikivoyage")]
    if why and why[0] in ("vegan", "vegetarian") and p.kind in ("vegan_restaurant", "vegetarian_restaurant"):
        return [why[0] + " restaurant"] + why[1:]
    return [kind_label(p.kind)] + why


def describe(p, n=None, guide_text=None, origin="the centre"):
    """One place as the model and the list show it."""
    head = f"[{n}] " if n is not None else ""
    bits = kind_bits(p)
    if p.street:
        bits.append(p.street + (f", {p.locality}" if p.locality else ""))
    bits.append(f"{p.km:.1f} km from {origin}")
    if p.hours:
        bits.append(f"hours: {p.hours}")
    line = f"{head}{p.name}: " + "; ".join(bits) + "."
    if guide_text:
        line += f" The travel guide says: {guide_text}"
    return line


def guide_text(voyage, p):
    """The listing's own line in the Wikivoyage article, without its name."""
    if voyage is None or not p.guide:
        return None
    article, section, gtier, listing = p.guide[0]
    text = voyage(article)
    if not text:
        return None
    for line in text.split("\n"):
        if line.startswith("- - " + listing):
            rest = line[4 + len(listing):].lstrip(":. ").strip()
            return rest[:400] or None
    return None


# ---- the whole lookup --------------------------------------------------------------------------

HERE_RADII = (2.0, 5.0, 10.0)  # km around the phone, widened until MIN_HERE places match
MIN_HERE = 5


@dataclass
class Lookup:
    label: str          # where: "Lisbon, Portugal" or "your position"
    radius_km: float
    total: int          # places that matched, before the list was cut
    places: list
    capped: bool        # only the best CANDIDATES were read: "total" is a floor
    origin: str         # distances are from "the centre" or "you"
    city: City = None


def lookup(db, ask, here=None):
    """The places for [ask]: around the city it names, or around [here] (lat, lon) for "near me".
    None when the question names no city this database knows, or asks "near me" without [here]."""
    if ask.here:
        if here is None:
            return None
        for r in HERE_RADII:
            places, total, capped = find(db, ask, here[0], here[1], r)
            if total >= MIN_HERE:
                break
        return Lookup("your position", r, total, places, capped, "you")
    city = locate(db, ask)
    if city is None:
        return None
    places, total, capped = find(db, ask, city.lat, city.lon, city.radius_km)
    return Lookup(city.label(), city.radius_km, total, places, capped, "the centre", city)


# ---- what the model reads -------------------------------------------------------------------

PLACES_SYSTEM = (
    "You are an offline travel assistant. Answer the question from the numbered list of places, "
    "which comes from offline map data (OpenStreetMap and Overture Maps) and the Wikivoyage travel "
    "guide, best matches first. Recommend the three to five places that best answer the question, one "
    "short line each (under 30 words, in your own words, not copied from the list): its name and number "
    "like [2], what kind of place it is and where, and the gist of the travel guide's words when the "
    "list quotes them. Use only facts from the list; add what you know about a place only if it is "
    "well known and you are sure. Never invent ratings, prices, dishes or opening hours. Close with "
    "one sentence noting that map data has no ratings and places close, so it is worth checking "
    "before going. No preamble, no LaTeX, no visible deliberation."
)
MODEL_PLACES = 6     # places the model reads (the list shows up to find()'s limit)
GUIDE_CHARS = 180    # of each travel-guide listing


def where_text(total, radius_km, label, ask, capped=False):
    what = {"eat": "places to eat", "cafe": "cafes", "drink": "places to drink", "sweet": "bakeries and sweet shops",
            "stay": "places to stay"}[ask.group]
    if ask.diet:
        what = ask.diet.replace("_", "-") + " " + what
    return f"{total}{'+' if capped else ''} {what} within {radius_km:.0f} km of {label}"


def places_user(question, where, lines):
    """The user message of the places answer."""
    return "Places (" + where + "):\n\n" + "\n".join(lines) + "\n\nQuestion: " + question


def context_lines(places, voyage, origin):
    return [describe(p, i, _clip(guide_text(voyage, p), GUIDE_CHARS), origin) for i, p in enumerate(places[:MODEL_PLACES], 1)]


def _clip(text, n):
    if text is None or len(text) <= n:
        return text
    cut = text[:n]
    sp = cut.rfind(" ")
    return (cut[:sp] if sp > n // 2 else cut) + "…"


def voyage_reader(path):
    import zstandard
    c = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    d = zstandard.ZstdDecompressor()

    def text(title):
        r = c.execute("select block_id, off, len from articles where title = ?", (title,)).fetchone()
        if not r:
            return None
        raw = d.decompress(c.execute("select zdata from blocks where id = ?", (r[0],)).fetchone()[0]).decode("utf-8")
        return raw[r[1]:r[1] + r[2]]
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("question")
    ap.add_argument("--voyage")
    ap.add_argument("--here", help="lat,lon for 'near me'")
    a = ap.parse_args()
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    cats = [r[0] for r in db.execute("select name from kinds")]
    ask = parse(a.question, cats)
    print("ask:", ask)
    if ask is None:
        return
    here = tuple(map(float, a.here.split(","))) if a.here else None
    lk = lookup(db, ask, here)
    if lk is None:
        print("no place found")
        return
    print("city:", lk.city)
    voyage = voyage_reader(a.voyage) if a.voyage else None
    print(places_user(a.question, where_text(lk.total, lk.radius_km, lk.label, ask, lk.capped),
                      context_lines(lk.places, voyage, lk.origin)))


if __name__ == "__main__":
    main()
