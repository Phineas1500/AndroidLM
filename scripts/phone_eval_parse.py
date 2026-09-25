#!/usr/bin/env python3
"""phone_eval.sh logs -> JSONL: id, question, route, titles, sources, timings and the final text."""
import json, re, sys
qfile, outdir, dest = sys.argv[1:4]
PREFIX = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+ I AndroidLM: ")
rows = []
for line in open(qfile):
    q = json.loads(line)
    try:
        raw = open(f"{outdir}/{q['id']}.log", errors="replace").read().splitlines()
    except FileNotFoundError:
        continue
    msgs = [PREFIX.sub("", l) for l in raw if PREFIX.match(l)]
    rec = {"id": q["id"], "q": q["q"], "phone": True}
    chunks, cur = [], None
    for m in msgs:
        tm = re.match(r"run=\d+ t=(\d+)ms (.*)", m)
        tx = re.match(r"run=\d+ text\[(\d+)\]=(.*)", m)
        if tx:
            cur = [tx.group(2)]; chunks.append(cur); continue
        if tm:
            cur = None
            t, msg = int(tm.group(1)), tm.group(2)
            if msg.startswith("route="):
                rec["route"] = "plan" if "RETRIEVAL_FIRST" in msg else "verify"
            elif msg.startswith("planned="):
                rec["titles"] = msg[len("planned=["):-1].split(", ")
            elif msg.startswith("sources="):
                rec["sources_line"] = msg
            elif msg in ("first_answer_token", "first_check_token", "completed"):
                rec[msg + "_ms"] = t
            elif msg.startswith("phase_done="):
                ph = msg.split()[0].split("=")[1]
                rec.setdefault("phases", {})[ph] = msg
            elif msg.startswith("failed"):
                rec["failed"] = msg
            continue
        if cur is not None:
            cur.append(m)  # continuation line of a multi-line chunk
    text = "".join("\n".join(c) for c in chunks)
    rec["answer"] = text
    rows.append(rec)
with open(dest, "w") as f:
    for r in rows:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
print(len(rows), "records;", sum(1 for r in rows if r.get("answer")), "with text")
