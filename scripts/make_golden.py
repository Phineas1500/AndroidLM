#!/usr/bin/env python3
"""Reference outputs of the Python retrieval code on the sample databases, for the Kotlin
port's tests to reproduce exactly. No model is involved: planned titles are given.

Usage: make_golden.py sample_wiki.db sample_voyage.db > golden.json

The Kotlin GoldenWordCountsTest also wants the sample's word-count file, built with a low
threshold so that most stems are read from it: build_df.py sample_wiki.db sample_wiki_df.db 2.
Build golden.json without that file next to sample_wiki.db (the output is the same either way).
"""
import json
import sys

from rag import Corpus, build_context

CASES = [
    ("I am visiting Kyoto for three days. Which districts and sites should I prioritize, and what "
     "etiquette should I know at temples and shrines?", ["Kyoto", "Etiquette in Japan", "Shinto shrine"]),
    ("I have a 10-hour layover in Istanbul. What can I realistically see, and how is the old city laid out?",
     ["Istanbul", "Hagia Sophia", "Istanbul Airport"]),
    ("Who was the ruler of the Ottoman Empire when Constantinople fell, and what happened to the Hagia Sophia "
     "afterwards and in the 20th and 21st centuries?", ["Mehmed the Conqueror", "Hagia Sophia"]),
    ("What are the main arguments for and against rent control, and what does the empirical evidence say?",
     ["Rent control", "Housing economics"]),
    ("What are the signs of dehydration versus heat stroke, and what first aid is appropriate for each?",
     ["Heat stroke", "Dehydration"]),
    ("Roughly how many times larger is the population of India than that of Canada, and how do their land "
     "areas compare?", ["India", "Canada"]),
    ("What is the difference between type 1 and type 2 diabetes in cause, onset, and treatment?",
     ["Type 1 diabetes", "Type 2 diabetes"]),
    ("What happened in the 1983 Harrods bombing, who carried it out, and what warning was given?",
     ["Harrods bombing", "Provisional Irish Republican Army"]),
    ("What are the main regions of Georgia (the country), what is each known for, and what foods should I try?",
     ["Georgia (country)", "Kakheti", "A Title That Does Not Exist"]),
]

wiki, voyage = Corpus(sys.argv[1]), Corpus(sys.argv[2])
out = []
for question, titles in CASES:
    stems = wiki.stems(question)
    case = {
        "question": question, "titles": titles,
        "stems": [[s, round(idf, 4)] for s, idf in stems],
        "query_terms": wiki.query_terms(stems),
        "resolved": [wiki.resolve_title(t) for t in titles],
        "resolved_voyage": [voyage.resolve_title(t, fuzzy=False) for t in titles],
    }
    for name, v in (("hits", None), ("hits_voyage", voyage)):
        hits = wiki.retrieve(question, titles, voyage=v)
        context, used = build_context(hits, 4000)
        case[name] = [{"title": h["title"], "section": h["section"], "start": h["start"],
                       "score": h["score"], "via": h["via"]} for h in hits]
        case[name + "_used"] = len(used)
        case[name + "_context"] = context
    first = wiki.resolve_title(titles[0])
    case["route_views"] = wiki.db.execute("select views from articles where id=?", (first,)).fetchone()[0] if first else None
    out.append(case)
json.dump(out, sys.stdout, ensure_ascii=False, indent=1)
