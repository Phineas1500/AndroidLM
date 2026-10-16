#!/usr/bin/env python3
"""Ask Commonplace (github.com/0xPD33/commonplace, package app.commonplace) on the phone every
question of a question file (JSONL with id and q), through its own UI test tags, for a
side-by-side with AndroidLM on the same phone.

Each question starts a new topic (so no follow-up context carries over), is typed into the ask
box and sent; the script waits until the Stop button is gone, then reads the answer's text blocks
from uiautomator dumps, scrolling through a long answer, and the app's own timings line ("first
word", tok/s, shown with Settings > Show timings on). `adb shell input text` cannot type
non-ASCII, so accents are dropped and "°" becomes " degrees " (the typed text is recorded).
A question with "asked_after" (a follow-up) is asked in the same topic right after that first
question, and only the follow-up's answer is read (the first answer is kept as "first_answer").
The next question is asked --gap seconds after an answer is done. Resumable: questions already
in <out>/answers.jsonl are skipped.

usage: phone_commonplace.py <questions.jsonl> <out dir> [--gap 30] [--timeout 900]
"""
import argparse
import json
import re
import subprocess
import time
import unicodedata
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "app.commonplace"


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout


def dump():
    for _ in range(6):
        out = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        xml = out[out.find("<?xml"):out.rfind("</hierarchy>") + len("</hierarchy>")]
        if xml:
            return ET.fromstring(xml)
        time.sleep(0.7)
    raise RuntimeError("uiautomator dump failed")


def tagged(root, tag):
    return [n for n in root.iter("node") if n.get("resource-id", "") in (tag, f"{PKG}:id/{tag}") or
            n.get("resource-id", "").endswith(":id/" + tag)]


def first(root, tag):
    found = tagged(root, tag)
    return found[0] if found else None


def bounds(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]")))
    return x1, y1, x2, y2


def tap(n):
    x1, y1, x2, y2 = bounds(n)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def wait_for(tag, timeout, contains=None):
    end = time.time() + timeout
    while time.time() < end:
        root = dump()
        for n in tagged(root, tag):
            if contains is None or contains in (n.get("text") or ""):
                return n
        time.sleep(0.8)
    return None


def ascii_text(q):
    q = q.replace("°", " degrees ")
    q = unicodedata.normalize("NFKD", q)
    q = "".join(c for c in q if not unicodedata.combining(c))
    return re.sub(r"\s+", " ", q.encode("ascii", "ignore").decode()).strip()


def type_text(text):
    # remote shell: single-quoted, so nothing is expanded; `input text` reads %s as a space
    arg = text.replace(" ", "%s").replace("'", "'\\''")
    adb("shell", f"input text '{arg}'")


def skin():
    out = adb("shell", "dumpsys thermalservice", check=False)
    tail = out[out.find("Current temperatures"):]
    m = re.search(r"mName=VIRTUAL-SKIN,.*?mValue=([0-9.]+)", tail) or re.search(r"mValue=([0-9.]+)[^\n]*mName=VIRTUAL-SKIN", tail)
    return float(m.group(1)) if m else None


def top(n):
    return bounds(n)[1]


def query_node(root, query):
    found = [n for n in tagged(root, "turn_query") if query[:30] in (n.get("text") or "")]
    return found[-1] if found else None


def answer_blocks(root, below=None):
    """The visible text blocks of the answer, top to bottom, and whether its end is on screen: the
    last answer on screen, or with `below` (a y position) the last one under it."""
    ans = [n for n in tagged(root, "answer_text") if below is None or top(n) >= below]
    if not ans:
        return [], False
    a = ans[-1]
    texts = [n.get("text") for n in a.iter("node") if n is not a and n.get("text")]
    # list items are a marker Text and a body Text: join them
    blocks, i = [], 0
    while i < len(texts):
        t = texts[i]
        if i + 1 < len(texts) and re.fullmatch(r"(\d+\.|[•\-–·*])", t.strip()):
            blocks.append(t.strip() + " " + texts[i + 1])
            i += 2
        else:
            blocks.append(t)
            i += 1
    # the answer's action row (copy, share) is drawn below it: once it is on screen, so is the end
    end_visible = any(n is not None and (below is None or top(n) >= below)
                      for t in ("copy_answer", "share_answer", "follow_up_chips") for n in tagged(root, t))
    return blocks, end_visible


def timings_line(root, below=None):
    found = [n for n in tagged(root, "timings") if n.get("text") and (below is None or top(n) >= below)]
    return found[-1].get("text") if found else None


def merge(acc, new):
    """Appends the part of `new` that follows its overlap with the end of `acc`."""
    for k in range(min(len(acc), len(new)), 0, -1):
        if acc[-k:] == new[:k]:
            return acc + new[k:]
    return acc + new


def read_answer(query=None):
    """The answer's text and timings line. With `query`, the answer to that turn of the topic (the
    last one): the view is scrolled up only until that question is on screen."""
    for _ in range(8 if query is None else 25):  # up to the top, or to the question
        if query is not None and query_node(dump(), query) is not None:
            break
        adb("shell", "input", "swipe", "540", "700", "540", "1900" if query is None else "1300", "200")
    time.sleep(0.8)
    acc = []
    timings = None
    still = 0
    for _ in range(30):
        root = dump()
        qn = query_node(root, query) if query is not None else None
        below = bounds(qn)[3] if qn is not None else None
        blocks, end_visible = answer_blocks(root, below)
        before = len(acc)
        acc = merge(acc, blocks)
        still = still + 1 if acc and len(acc) == before else 0
        timings = timings_line(root, below) or timings
        if (end_visible and acc) or still >= 3:
            break
        adb("shell", "input", "swipe", "540", "1700", "540", "900", "400")
        time.sleep(0.8)
    timings = timings_line(dump()) or timings  # the last turn's, at the bottom
    return "\n\n".join(acc), timings


def ask(typed, new_topic, timeout):
    """Types and sends a question, in a new topic or the open one, and waits until it is answered:
    (seconds to the answer's first text on screen, seconds to done or None on a timeout)."""
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")  # back to the front if it was not
    time.sleep(1)
    if new_topic:
        nt = wait_for("new_topic", 5)
        if nt is not None and nt.get("enabled") == "true":
            tap(nt)
            wait_for("welcome", 10)
    box = wait_for("ask_input", 30)
    if box is None:  # Android asks whether it may send notifications after a restart: no
        for n in dump().iter("node"):
            if n.get("resource-id", "").startswith("com.android.permissioncontroller:id/permission_deny"):
                tap(n)
                box = wait_for("ask_input", 30)
                break
    if box is None:
        raise RuntimeError("no ask box")
    tap(box)
    time.sleep(0.5)
    type_text(typed)
    time.sleep(0.5)
    send = wait_for("ask_send", 10)
    t0 = time.time()
    tap(send)
    wait_for("turn_query", 30, contains=typed[:30])
    first_seen = None
    while time.time() - t0 < timeout:
        root = dump()
        if first_seen is None:
            qn = query_node(root, typed)
            # a follow-up: text under its question, or the question scrolled away by the answer
            if (answer_blocks(root, bounds(qn)[3])[0] if qn is not None else tagged(root, "answer_text")):
                first_seen = time.time() - t0
        if first(root, "ask_stop") is None:
            return first_seen, time.time() - t0
        time.sleep(2)
    return first_seen, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("questions")
    ap.add_argument("out")
    ap.add_argument("--gap", type=float, default=30)
    ap.add_argument("--timeout", type=float, default=900)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    done_path = out / "answers.jsonl"
    done = {json.loads(l)["id"] for l in done_path.open()} if done_path.exists() else set()
    qs = [json.loads(l) for l in open(a.questions)]
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3)
    for q in qs:
        if q["id"] in done:
            continue
        typed = ascii_text(q["q"])
        s0 = skin()
        first_q = None
        if q.get("asked_after"):
            first_typed = ascii_text(q["asked_after"])
            print(f"##### {q['id']} first question {time.strftime('%H:%M:%S')} {first_typed[:70]}", flush=True)
            ask(first_typed, True, a.timeout)
            ftext, ftimings = read_answer(first_typed)
            first_q = {"q": q["asked_after"], "typed": first_typed, "answer": ftext, "app_timings": ftimings}
            time.sleep(a.gap)
        print(f"##### {q['id']} skin={s0} {time.strftime('%H:%M:%S')} {typed[:70]}", flush=True)
        first_seen, stopped_at = ask(typed, first_q is None, a.timeout)
        text, timings = read_answer(typed if first_q else None)
        shot = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
        (out / f"{q['id']}.png").write_bytes(shot)
        rec = {"id": q["id"], "q": q["q"], "typed": typed, "answer": text,
               "done_s": round(stopped_at, 1) if stopped_at is not None else None,
               "first_text_seen_s": round(first_seen, 1) if first_seen is not None else None,
               "app_timings": timings, "skin_start": s0, "skin_end": skin(),
               "result": "done" if stopped_at is not None else "timeout"}
        if first_q:
            rec["first_question"] = first_q
        with done_path.open("a") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        print(f"  done {rec['done_s']} s, {len(text)} chars, {timings}", flush=True)
        time.sleep(a.gap)
    print("PHONE_COMMONPLACE_DONE", flush=True)


if __name__ == "__main__":
    main()
