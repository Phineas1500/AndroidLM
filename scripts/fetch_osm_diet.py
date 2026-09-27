#!/usr/bin/env python3
"""Fetch OpenStreetMap places that carry diet or vegan/vegetarian cuisine tags, and pharmacies,
ATMs and money changers, worldwide.

The places database (build_places.py) takes its places from Overture Maps; OpenStreetMap adds
what Overture lacks: whether a place serves vegan or vegetarian food (diet:vegan=only/yes/...),
other diets (gluten-free, halal, kosher), opening hours (a pharmacy open late), and places
Overture does not have (it has few ATMs).
Only objects with a diet:* key or a vegan/vegetarian cuisine are fetched (a few hundred
thousand), in one query to QLever's copy of the OpenStreetMap planet (qlever.dev, University of
Freiburg), which answers it in about a minute; the public Overpass servers time out on it.

    python scripts/fetch_osm_diet.py OUT.json

Writes the SPARQL JSON result. Data (c) OpenStreetMap contributors, ODbL 1.0.
"""
import sys
import time
import urllib.parse
import urllib.request

ENDPOINT = "https://qlever.dev/api/osm-planet"
UA = "AndroidLM-places-build/1.0 (github.com/Phineas1500/AndroidLM)"

# tag key -> result variable
TAGS = {
    "name": "name", "name:en": "name_en", "amenity": "amenity", "shop": "shop", "cuisine": "cuisine",
    "diet:vegan": "vegan", "diet:vegetarian": "vegetarian", "diet:gluten_free": "gluten_free",
    "diet:halal": "halal", "diet:kosher": "kosher", "opening_hours": "hours", "website": "website",
    "contact:website": "contact_website", "phone": "phone", "contact:phone": "contact_phone",
    "addr:street": "street", "addr:housenumber": "housenumber", "addr:city": "city",
    "operator": "operator", "brand": "brand",
}

QUERY = """PREFIX osmkey: <https://www.openstreetmap.org/wiki/Key:>
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
SELECT ?s ?wkt %s WHERE {
  { SELECT DISTINCT ?s WHERE {
      { ?s osmkey:diet:vegan ?x } UNION { ?s osmkey:diet:vegetarian ?x } UNION
      { ?s osmkey:diet:gluten_free ?x } UNION { ?s osmkey:diet:halal ?x } UNION
      { ?s osmkey:diet:kosher ?x } UNION
      { ?s osmkey:cuisine ?c . FILTER(CONTAINS(?c, "vegan") || CONTAINS(?c, "vegetarian")) } UNION
      { ?s osmkey:amenity "pharmacy" } UNION { ?s osmkey:amenity "atm" } UNION
      { ?s osmkey:amenity "bureau_de_change" }
  } }
  ?s geo:hasGeometry/geo:asWKT ?wkt .
  %s
}""" % (" ".join("?" + v for v in TAGS.values()),
        "\n  ".join("OPTIONAL { ?s osmkey:%s ?%s }" % (k, v) for k, v in TAGS.items()))


def main():
    out = sys.argv[1]
    t = time.time()
    data = urllib.parse.urlencode({"query": QUERY}).encode()
    req = urllib.request.Request(ENDPOINT, data=data, headers={
        "User-Agent": UA, "Accept": "application/sparql-results+json"})
    with urllib.request.urlopen(req, timeout=1800) as r, open(out + ".part", "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
    import os
    os.replace(out + ".part", out)
    print(f"wrote {out}: {os.path.getsize(out) // 1_000_000} MB in {time.time() - t:.0f} s")


if __name__ == "__main__":
    main()
