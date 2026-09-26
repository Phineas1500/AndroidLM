#!/usr/bin/env python3
"""Edit one raw demo take into a clip with labelled speed-ups.

Every sped-up stretch carries an on-screen label with its speed and its real duration, so the
edit never hides waiting. Phase boundaries come from the app's own log (tag AndroidLM), aligned
to the video by the recording's end time (mp4 creation_time) and length.

usage: make_cut.py <take.mp4> <take.log> <out.mp4> <caption line>...
"""
import datetime as dt
import json
import os
import re
import subprocess
import sys

FONT = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
UTC_OFFSET_H = -4  # the phone's log is in EDT


def probe(path):
    out = json.loads(subprocess.check_output(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration:format_tags=creation_time", "-of", "json", path]))
    dur = float(out["format"]["duration"])
    end = dt.datetime.strptime(out["format"]["tags"]["creation_time"][:19], "%Y-%m-%dT%H:%M:%S")
    end = end + dt.timedelta(hours=UTC_OFFSET_H)
    return dur, end


def events(log_path, year):
    """{name: absolute local datetime} from the app log."""
    ev = {}
    for line in open(log_path, errors="replace"):
        m = re.match(r"(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) .*AndroidLM: (.*)", line)
        if not m:
            continue
        when = dt.datetime.strptime(f"{year}-{m[1]}", "%Y-%m-%d %H:%M:%S.%f")
        msg = m[2]
        if msg.startswith("engine env"):
            ev.setdefault("engine_start", when)
        mm = re.match(r"run=\d+ t=(\d+)ms (\S+)", msg)
        if not mm:
            continue
        key = mm[2]
        if key.startswith("phase="):
            ev.setdefault("start_" + key[6:], when)
        elif key.startswith("phase_done="):
            ev.setdefault("done_" + key[11:], when)
        elif key in ("first_answer_token", "first_check_token", "completed"):
            ev.setdefault(key, when)
    return ev


def esc(s):
    return s.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\u2019").replace("%", "\\%")


def label(text, y, size=40):
    return (f"drawtext=fontfile='{FONT}':text='{esc(text)}':fontcolor=white:fontsize={size}:"
            f"box=1:boxcolor=black@0.72:boxborderw=18:x=(w-text_w)/2:y={y}")


def main():
    mp4, log, out = sys.argv[1:4]
    captions = sys.argv[4:]
    dur, end = probe(mp4)
    start = end - dt.timedelta(seconds=dur)
    ev = events(log, end.year)
    t = {k: (v - start).total_seconds() for k, v in ev.items()}
    # Placing the log by the mp4's creation_time and length is off by a few seconds and not by
    # the same amount each time (from -1.0 to +4.6 s over the v3 takes), so SYNC=<event>:<video
    # seconds> pins one event seen on screen (the frame where the first word appears, say) and
    # moves all of them by the same offset
    if os.environ.get("SYNC"):
        name, at = os.environ["SYNC"].rsplit(":", 1)
        offset = float(at) - t[name]
        t = {k: v + offset for k, v in t.items()}
        print(f"note: synced on {name} at {at} s (offset {offset:+.2f} s)")
    print("events (video s): " + ", ".join(f"{k} {v:.1f}" for k, v in sorted(t.items(), key=lambda kv: kv[1])))
    # The phone's log daemon occasionally drops a line. A missing first-token moment is read off
    # the video instead (the frame where the answer's first word appears), in video seconds, from
    # FIRST_ANSWER_AT / FIRST_CHECK_AT; estimating it from the phase's speed was 6 s off once.
    for first, var in (("first_answer_token", "FIRST_ANSWER_AT"), ("first_check_token", "FIRST_CHECK_AT")):
        if os.environ.get(var):
            t[first] = float(os.environ[var])
            print(f"note: {first} at {t[first]} s from {var} (read off the video)")
    needed = ["first_answer_token"] + (["first_check_token"] if "start_DRAFTING" in t else [])
    missing = [k for k in needed if k not in t]
    if missing:
        sys.exit(f"{', '.join(missing)} not in the log: find the frame where it happens and pass "
                 f"FIRST_ANSWER_AT / FIRST_CHECK_AT (video seconds)")
    tap = t.get("engine_start", t["start_PLANNING"]) - 0.3

    answer_first = "start_DRAFTING" in t
    segs = []  # (start, end, speed, label or None)
    segs.append((0.0, tap + 1.0, 2, "2x \u00b7 airplane mode on; the question is typed"))
    cur = tap + 1.0
    if "engine_start" in t and t["start_PLANNING"] - cur > 3:
        segs.append((cur, t["start_PLANNING"], 10, f"Loading the model (once per app start) \u00b7 {t['start_PLANNING'] - tap:.0f} s shown at 10x"))
        cur = t["start_PLANNING"]
    segs.append((cur, t["done_PLANNING"], 8, f"Choosing Wikipedia articles \u00b7 {t['done_PLANNING'] - cur:.0f} s at 8x"))
    cur = t["done_PLANNING"]
    if not answer_first:
        segs.append((cur, t["done_SEARCHING"], 8, f"Searching the offline Wikipedia \u00b7 {t['done_SEARCHING'] - cur:.0f} s at 8x"))
        cur = t["done_SEARCHING"]
        first = t["first_answer_token"]
        segs.append((cur, first - 0.5, 16, f"Reading the sources \u00b7 {first - cur:.0f} s at 16x"))
        segs.append((first - 0.5, t["done_ANSWERING"] + 0.5, 3, f"Writing the cited answer \u00b7 {t['done_ANSWERING'] - first:.0f} s at 3x"))
        cur = t["done_ANSWERING"] + 0.5
    else:
        first = t["first_answer_token"]
        segs.append((cur, first - 0.5, 4, f"Reading the question \u00b7 {first - cur:.0f} s at 4x"))
        segs.append((first - 0.5, t["done_DRAFTING"] + 0.5, 3, f"Writing the answer from memory \u00b7 {t['done_DRAFTING'] - first:.0f} s at 3x"))
        cur = t["done_DRAFTING"] + 0.5
        fc = t["first_check_token"]
        segs.append((cur, fc - 0.5, 16, f"Reading the sources to check the answer \u00b7 {fc - cur:.0f} s at 16x"))
        segs.append((fc - 0.5, t["done_CHECKING"] + 0.5, 3, f"Writing the cited source check \u00b7 {t['done_CHECKING'] - fc:.0f} s at 3x"))
        cur = t["done_CHECKING"] + 0.5
    segs.append((cur, min(dur - 0.2, cur + 21), 3, "3x \u00b7 the finished answer and its sources"))

    parts = []
    for i, (a, b, speed, text) in enumerate(segs):
        if b - a < 0.2:
            continue
        f = f"[0:v]trim=start={a:.3f}:end={b:.3f},setpts=(PTS-STARTPTS)/{speed},fps=30"
        f += "," + label(text, 150)
        if i == 0:
            for j, c in enumerate(captions):
                f += "," + label(c, 2190 - 78 * (len(captions) - j), 44)
        parts.append(f + f"[s{i}]")
    names = "".join(p[p.rindex("["):] for p in parts)
    graph = ";".join(parts) + f";{names}concat=n={len(parts)}:v=1:a=0,scale=720:-2,format=yuv420p[out]"
    subprocess.check_call(["ffmpeg", "-v", "error", "-y", "-i", mp4, "-filter_complex", graph, "-map", "[out]",
                           "-c:v", "libx264", "-crf", "23", "-preset", "medium", "-r", "30", out])
    total = sum((b - a) / s for a, b, s, _ in segs)
    for a, b, s, text in segs:
        print(f"{a:7.1f} {b:7.1f} x{s:<3} {text}")
    print(f"clip length about {total:.0f} s (raw {dur:.0f} s)")


if __name__ == "__main__":
    main()
