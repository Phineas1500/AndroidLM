#!/usr/bin/env python3
"""phone_eval_parse.py output -> answer records as the app's user reads them ("draft": the text),
for answer_pairs.py: the answer (with its source check, when there is one) and, on the places
route, the list the app shows with it (each place's name, kind, street and distance).

Usage: app_answer_records.py parsed.jsonl out.jsonl
"""
import json
import re
import sys

src, dest = sys.argv[1:3]
n = 0
with open(dest, "w") as f:
    for line in open(src):
        r = json.loads(line)
        text = (r.get("answer") or "").strip()
        if r.get("route") == "places":
            m = re.search(r"\[(.*)\]\s*$", r.get("sources_line") or "")
            if m and m.group(1).strip():
                # entries are "name — kind · street · distance", joined by " | "; a name can itself
                # hold " | " ("Zerö Kebab | Plant-Based Döner"), so a piece without " — " starts the next entry
                places, head = [], ""
                for p in m.group(1).split(" | "):
                    head = f"{head} | {p.strip()}" if head else p.strip()
                    if " — " in p:
                        places.append(head)
                        head = ""
                if head:
                    places.append(head)
                listing = "\n".join(f"[{i}] {p}" for i, p in enumerate(places, 1))
                text += "\n\nThe list the app shows with this answer (tap a place for its details and a map link):\n" + listing
        f.write(json.dumps({"id": r["id"], "q": r["q"], "draft": text, "route": r.get("route"),
                            "first_answer_ms": r.get("first_answer_token_ms"), "done_ms": r.get("completed_ms")},
                           ensure_ascii=False) + "\n")
        n += 1
print(n, "answers")
