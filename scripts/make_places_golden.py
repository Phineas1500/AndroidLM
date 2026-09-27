#!/usr/bin/env python3
"""Writes places_golden.json: what scripts/places.py answers for a set of questions over a sample
places database, for the Kotlin port's PlacesGoldenTest (app-android/research).

    python scripts/make_places_golden.py sample_places.db places_golden.json

The sample database is cut from a full build by make_places_sample.py (the fixture directory,
~/androidlm-tools/fixtures, holds both files).
"""
import json
import sqlite3
import sys

sys.path.insert(0, __import__("os").path.dirname(__file__))
import places as P  # noqa: E402
from build_places import norm_key  # noqa: E402

QUESTIONS = [
    "Tell me the best vegan restaurants in Buenos Aires",
    "vegan food in buenos aires?",
    "Buenos Aires vegan restaurants",
    "Where can I get good vegetarian food in Buenos Aires, Argentina?",
    "best steak in Buenos Aires",
    "cheap hostels in Buenos Aires",
    "Where should I stay in Buenos Aires on a budget?",
    "Where can I get sushi in Buenos Aires?",
    "any good gluten-free bakeries in Buenos Aires?",
    "Best cocktail bars in Buenos Aires",
    "Recommend a nice cafe for breakfast in Buenos Aires",
    "Where to eat Peruvian food in Buenos Aires?",
    "luxury hotels in Buenos Aires",
    "kosher restaurants in Buenos Aires",
    "halal food in Buenos Aires please",
    "Best vegan restaurants near me",
    "vegetarian food nearby",
    "What is the food like in Buenos Aires?",
    "traditional dishes of Buenos Aires",
    "What is the history of the Argentine asado?",
    "Tell me about Buenos Aires",
    "How long should I stay in Buenos Aires?",
    "Who founded the first restaurant in Paris?",
    "What is the capital of Argentina?",
    "best vegan restaurants in Atlantis",
    "Tell me the best vegan restaurants in [city I am currently in]",
    "Where can I eat vegan in Paris, Texas?",
    "Best pizza in Buenos Aires, Argentina",
    "Tell me the best vegan restaurants in London",
    "best vegan restaurants in London, Ontario",
    "Where can I get a good coffee in London?",
    "cheap places to eat in London",
    "vegan pubs in London",
    "Any vegetarian Indian restaurants in London?",
]
HERE = (-34.6, -58.4)
NORM = ["Lisboa", "São Paulo", "Zürich", "Kraków", "Straße", "Ærøskøbing", "İstanbul", "Hà Nội",
        "Paris, Texas", "  St. John's  ", "Москва", "東京", "Tromsø", "Łódź", "Reykjavík"]


def place_json(p, i, origin):
    return {"id": p.id, "tier": p.tier, "score": p.score, "km": p.km, "why": p.why,
            "line": P.describe(p, i, None, origin)}


def main():
    db_path, out = sys.argv[1], sys.argv[2]
    db = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    cats = [r[0] for r in db.execute("select name from kinds order by id")]
    cases = []
    for q in QUESTIONS:
        ask = P.parse(q, cats)
        case = {"question": q, "ask": None, "candidates": [list(c) for c in P.place_candidates(q)]}
        if ask is not None:
            case["ask"] = {"group": ask.group, "diet": ask.diet, "sub": ask.sub, "price": ask.price,
                           "here": ask.here, "restaurant": ask.restaurant}
            lk = P.lookup(db, ask, HERE)
            if lk is not None:
                case["lookup"] = {
                    "label": lk.label, "radius_km": lk.radius_km, "total": lk.total, "capped": lk.capped, "origin": lk.origin,
                    "city": lk.city.id if lk.city else None,
                    "where": P.where_text(lk.total, lk.radius_km, lk.label, ask, lk.capped),
                    "places": [place_json(p, i, lk.origin) for i, p in enumerate(lk.places, 1)],
                }
        cases.append(case)
    golden = {
        "here": list(HERE),
        "cases": cases,
        "norm_key": {s: norm_key(s) for s in NORM},
        "prompt": P.PLACES_SYSTEM,
        "clip": [[t, n, P._clip(t, n)] for t, n in [("short", 10), ("a b c d e f g h i j k l", 9),
                                                   ("abcdefghijklmnop qr", 12), ("naïve café au lait, très bon", 14)]],
    }
    with open(out, "w") as f:
        json.dump(golden, f, ensure_ascii=False, indent=1)
    n = sum(1 for c in cases if c.get("lookup"))
    print(f"wrote {out}: {len(cases)} questions, {n} with places")


if __name__ == "__main__":
    main()
