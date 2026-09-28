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

from rag import Corpus, build_context, check_context

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

# a draft per case, for the source check's context (check_context): names, numbers, claims
DRAFTS = [
    "Prioritize Higashiyama, Gion and Arashiyama. Kiyomizu-dera and Fushimi Inari are the key sites. "
    "At temples, remove your shoes; at shrines, bow twice and clap twice.",
    "With 10 hours, see Hagia Sophia, the Blue Mosque and the Grand Bazaar in Sultanahmet. The old city "
    "sits on a peninsula between the Golden Horn and the Sea of Marmara, 45 km from Istanbul Airport.",
    "Mehmed II ruled when Constantinople fell in 1453. Hagia Sophia became a mosque, a museum in 1934, "
    "and a mosque again in 2020.",
    "Rent control protects tenants from sudden increases but reduces housing supply, according to most "
    "economists; a 2019 Stanford study of San Francisco found a 15% fall in rental supply.",
    "Dehydration causes thirst and dark urine; heat stroke is a body temperature above 40 °C with confusion. "
    "Give fluids for dehydration; cool the body and call emergency services for heat stroke.",
    "India's population, about 1,428 million, is roughly 36 times Canada's 39 million, while Canada's land "
    "area of 9.98 million km2 is about three times India's 3.29 million km2.",
    "Type 1 diabetes is autoimmune, begins in childhood and needs insulin; type 2 comes from insulin "
    "resistance, usually after 45, and is treated with lifestyle changes and metformin.",
    "The Provisional IRA bombed Harrods on 17 December 1983, killing six people; a coded warning was "
    "given 37 minutes before the blast.",
    "Georgia's regions include Kakheti, known for wine, Svaneti for its towers, and Adjara for Batumi. "
    "Try khachapuri, khinkali and churchkhela.",
]

wiki, voyage = Corpus(sys.argv[1]), Corpus(sys.argv[2])
out = []
for (question, titles), draft in zip(CASES, DRAFTS):
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
        if v is not None:
            case["draft"] = draft
            case["check_passages_context"] = check_context(hits, draft, question, 2000)[0]
            case["check_excerpts_context"] = check_context(hits, draft, question, 2000, excerpt=True)[0]
    first = wiki.resolve_title(titles[0])
    case["route_views"] = wiki.db.execute("select views from articles where id=?", (first,)).fetchone()[0] if first else None
    out.append(case)
json.dump(out, sys.stdout, ensure_ascii=False, indent=1)
