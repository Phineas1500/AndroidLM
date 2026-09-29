#!/usr/bin/env python3
"""The places route's answers for a question file, through llama-server, for comparing ways of
writing them (the app's route: places.py lookup, the model's lines, one generation). Each record
has the answer followed by the list the app shows with it, as app_answer_records.py writes the
phone's, so the two can be graded the same way.

Usage: places_answers.py places.db voyage.db wiki.db questions.jsonl out.jsonl --variant 1|2 (the first or the second version of the lines and prompt)
       [--url http://127.0.0.1:8091] [--translated ID=English question ...]
  questions: id, q, and gps [lat, lon] for "near me"; --translated gives the English question a
  non-English one is searched with (the app translates it first), the answer is asked in the
  question's own language.
"""
import argparse
import json
import sys

import places as P
from rag import chat

ap = argparse.ArgumentParser()
ap.add_argument("db")
ap.add_argument("voyage")
ap.add_argument("wiki")
ap.add_argument("questions")
ap.add_argument("out")
ap.add_argument("--variant", type=int, default=1)
ap.add_argument("--url", default="http://127.0.0.1:8091")
ap.add_argument("--translated", nargs="*", default=[])
a = ap.parse_args()

import sqlite3  # noqa: E402

db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
cats = [r[0] for r in db.execute("select name from kinds")]
voyage = P.voyage_reader(a.voyage)
wiki = P.voyage_reader(a.wiki)
translated = dict(t.split("=", 1) for t in a.translated)

with open(a.out, "w") as f:
    for q in map(json.loads, filter(str.strip, open(a.questions))):
        asked = q["q"]
        question = translated.get(q["id"], asked)
        ask = P.parse(question, cats)
        here = tuple(q["gps"]) if q.get("gps") else None
        lk = P.lookup(db, ask, here) if ask else None
        if lk is None or not lk.places:
            continue
        where = P.where_text(lk.total, lk.radius_km, lk.label, ask, lk.capped)
        if a.variant == 2:
            lines = P.context_lines_v2(lk.places, voyage, lk.origin, wiki)
            system, max_tokens = P.PLACES_SYSTEM_V2, 800
        else:
            lines = P.context_lines(lk.places, voyage, lk.origin, P.asks_hours(ask), wiki)
            system, max_tokens = P.PLACES_SYSTEM, 360
        answer_q = question if asked == question else \
            question + "\n\nAnswer in the language of the question as it was asked: " + asked
        res = chat(a.url, system, P.places_user(answer_q, where, lines), max_tokens)
        shown = "\n".join(P.list_line(p, i, lk.origin) for i, p in enumerate(lk.places[:12], 1))
        text = res["text"].strip() + "\n\nThe list the app shows with this answer (tap a place for its details and a map link):\n" + shown
        f.write(json.dumps({"id": q["id"], "q": asked, "draft": text, "prompt_tokens": res.get("prompt_tokens"),
                            "gen_tokens": res.get("gen_tokens")}, ensure_ascii=False) + "\n")
        f.flush()
        print(q["id"], res.get("prompt_tokens"), res.get("gen_tokens"), flush=True)
