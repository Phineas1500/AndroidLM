#!/usr/bin/env python3
"""Where a measured phone session's time, power and CPU went: one line per question and per idle
gap between questions, from the files of a heat run (power rails from perfetto, the phone-side
heat_sampler.sh log, phone_session.sh's session.tsv and each run's AndroidLM log).

    heat_report.py <run dir>            (the dir holds <name>.pftrace, <name>_sampler.log, session/)
    heat_report.py <run dir> --threads  also each question's CPU seconds by thread name

Power: the rails' mean over the window, grouped (CPU big = X3, mid = A715s, little = A510s, memory =
DDR + memory controller, display, other). CPU seconds are summed over the app's and the engine's
threads by name, from /proc/<pid>/task/*/stat (100 ticks a second).
"""
import argparse
import collections
import datetime
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import power_rails  # noqa: E402

GROUPS = [
    ("cpu_big", ("S2M_VDD_CPUCL2",)),
    ("cpu_mid", ("S3M_VDD_CPUCL1",)),
    ("cpu_little", ("S4M_VDD_CPUCL0", "S9M_VDD_CPUCL0_M")),
    ("memory", ("S4S_VDD2H_MEM", "S5S_VDDQ_MEM", "L21S_VDD2L_MEM", "S1M_VDD_MIF")),
    ("display", ("VSYS_PWR_DISPLAY", "L22M_DISP")),
    ("ufs_int", ("L2S_PLL_MIPI_UFS", "S5M_VDD_INT")),
]
CLK_TCK = 100


def sampler(path):
    """[(uptime_s, epoch_s, freqs, skin, vmstat, {(pid, tid): (comm, ticks, proc)})]"""
    out, cur = [], None
    for line in open(path, errors="replace"):
        tag, _, rest = line.rstrip("\n").partition(" ")
        if tag == "T":
            e, u = rest.split()
            cur = {"up": float(u), "epoch": int(e), "freq": None, "skin": None, "vm": {}, "threads": {}, "pid": None}
            out.append(cur)
        elif cur is None:
            continue
        elif tag.startswith("cpu") and tag != "cpu":
            f = [int(x) for x in rest.split()]
            cur.setdefault("cores", {})[int(tag[3:])] = (sum(f) - f[3] - f[4], sum(f))  # busy, total
        elif tag == "F":
            cur["freq"] = [int(x) // 1000 for x in rest.split()]
        elif tag == "S":
            cur["skin"] = float(rest) if rest.strip() else None
        elif tag == "V":
            it = rest.split()
            cur["vm"] = {it[i]: int(it[i + 1]) for i in range(0, len(it) - 1, 2)}
        elif tag == "P":
            # the sampler lists the app first, then its child processes (the engine)
            cur["pid"] = rest.split()[0]
            procs = cur.setdefault("procs", {})
            procs[cur["pid"]] = "app" if not procs else "engine"
        elif tag == "X":
            m = re.match(r"(\d+) \((.*)\) (.*)", rest)
            if m:
                f = m.group(3).split()
                cur["threads"][(cur["pid"], m.group(1))] = (m.group(2), int(f[11]) + int(f[12]), int(f[36]))
    return out


def at(samples, up, key):
    """The last sample at or before uptime [up] that has [key]."""
    best = None
    for s in samples:
        if s["up"] > up:
            break
        if s.get(key):
            best = s
    return best


def after(samples, up, key):
    for s in samples:
        if s["up"] >= up and s.get(key):
            return s
    return None


def cpu_by_name(s0, s1, procs_of):
    """CPU seconds between two samples, by (process kind, thread name)."""
    out = collections.Counter()
    for k, (comm, t1, _) in s1["threads"].items():
        if k not in s0["threads"] and s0["procs"].get(k[0]) is not None:
            t0 = 0  # a thread started in the window
        elif k not in s0["threads"]:
            continue  # a process started in the window: its threads' totals include its start
        else:
            t0 = s0["threads"][k][1]
        kind = procs_of(k[0], s1)
        out[(kind, comm)] += (t1 - t0) / CLK_TCK
    return out


CLUSTERS = [("little", range(0, 4)), ("mid", range(4, 8)), ("big", range(8, 9))]


def busy(s0, s1):
    """Each cluster's busy share (0-1 per core, averaged over its cores) between two samples."""
    out = {}
    for name, cores in CLUSTERS:
        b = t = 0
        for c in cores:
            if c in s0.get("cores", {}) and c in s1.get("cores", {}):
                b += s1["cores"][c][0] - s0["cores"][c][0]
                t += s1["cores"][c][1] - s0["cores"][c][1]
        out[name] = b / t if t > 0 else 0.0
    return out


def proc_kind(pid, s):
    return s.get("procs", {}).get(pid, "app")


def logs(session_dir, qid):
    p = os.path.join(session_dir, qid + ".log")
    if not os.path.exists(p):
        return {}
    rec = {"tokens": 0, "gen_ms": 0}
    for line in open(p, errors="replace"):
        m = re.search(r"route=(\w+)", line)
        if m and "route" not in rec:
            rec["route"] = m.group(1)
        m = re.search(r"phase_done=(\w+) wall=(\d+)ms(?: tokens=(\d+) tok_s=([\d.]+) prompt_tokens=(\d+))?", line)
        if m:
            rec.setdefault("phases", []).append((m.group(1), int(m.group(2)) / 1000,
                                                 int(m.group(3) or 0), float(m.group(4) or 0), int(m.group(5) or 0)))
        m = re.search(r"t=(\d+)ms first_answer_token", line)
        if m:
            rec["first_ms"] = int(m.group(1))
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--threads", action="store_true")
    a = ap.parse_args()
    name = os.path.basename(os.path.normpath(a.run))
    names, rails = power_rails.read(os.path.join(a.run, name + ".pftrace"))
    by_name = {names[k]: v for k, v in rails.items()}
    smp = sampler(os.path.join(a.run, name + "_sampler.log"))
    # epoch -> uptime, from the sampler's own pairs
    off = sum(s["up"] - s["epoch"] for s in smp) / len(smp)
    sess = os.path.join(a.run, "session")
    rows = [l.rstrip("\n").split("\t") for l in open(os.path.join(sess, "session.tsv"))][1:]
    day = datetime.date.fromtimestamp(smp[0]["epoch"])
    windows = []
    prev_end = None
    for r in rows:
        qid, asked, s0, bat, secs, s1, result = r[:7]
        t0 = datetime.datetime.combine(day, datetime.time.fromisoformat(asked)).timestamp() + off
        t1 = t0 + int(secs)
        if prev_end is not None and t0 - prev_end > 5:
            windows.append(("(gap)", prev_end + 3, t0, None))
        windows.append((qid, t0, t1, r))
        prev_end = t1
    windows.append(("(idle after)", prev_end + 3, smp[-1]["up"], None))

    def power(t0, t1):
        g = {}
        total = 0.0
        for rail, s in by_name.items():
            mw = (power_rails.energy_at(s, t1 * 1000) - power_rails.energy_at(s, t0 * 1000)) / max((t1 - t0) * 1000, 1)
            total += mw
            for gname, members in GROUPS:
                if rail in members:
                    g[gname] = g.get(gname, 0) + mw
        g["other"] = total - sum(g.values())
        g["total"] = total
        return g

    cols = [g for g, _ in GROUPS] + ["other", "total"]
    print("window\tsecs\troute\ttok\ttok/s\tfirst_s\tskin0\tskin1\tclk_mid\tclk_big\t" + "\t".join(c + "_mW" for c in cols)
          + "\tenergy_J\tengine_cpu_s\tapp_cpu_s\tswapin\trefault_file\tbusy_little\tbusy_mid\tbusy_big")
    totals = collections.Counter()
    for wname, t0, t1, r in windows:
        if t1 - t0 < 2:
            continue
        p = power(t0, t1)
        a0, a1 = at(smp, t0, "threads"), at(smp, t1, "threads") or after(smp, t1, "threads")
        cpu = cpu_by_name(a0, a1, proc_kind) if a0 and a1 else collections.Counter()
        eng = sum(v for (k, _), v in cpu.items() if k == "engine")
        app = sum(v for (k, _), v in cpu.items() if k == "app")
        v0, v1 = at(smp, t0, "vm"), at(smp, t1, "vm")
        swin = (v1["vm"].get("pswpin", 0) - v0["vm"].get("pswpin", 0)) if v0 and v1 else 0
        rf = (v1["vm"].get("workingset_refault_file", 0) - v0["vm"].get("workingset_refault_file", 0)) if v0 and v1 else 0
        fr = [s["freq"] for s in smp if t0 <= s["up"] <= t1 and s["freq"]]
        mid = sum(f[1] for f in fr) / len(fr) if fr else 0
        big = sum(f[2] for f in fr) / len(fr) if fr else 0
        c0, c1 = after(smp, t0, "cores"), at(smp, t1, "cores")
        bz = busy(c0, c1) if c0 and c1 else {n: 0.0 for n, _ in CLUSTERS}
        sk0 = at(smp, t0, "skin")
        sk1 = at(smp, t1, "skin")
        lg = logs(sess, wname) if r else {}
        gen = [ph for ph in lg.get("phases", []) if ph[2] > 0]
        tok = sum(ph[2] for ph in gen)
        tps = sum(ph[2] for ph in gen) / max(sum(ph[2] / ph[3] for ph in gen if ph[3] > 0), 1e-9) if gen else 0
        energy = p["total"] * (t1 - t0) / 1000
        print(f"{wname}\t{t1 - t0:.0f}\t{lg.get('route', '')}\t{tok}\t{tps:.2f}\t{lg.get('first_ms', 0) / 1000:.1f}\t"
              f"{sk0['skin'] if sk0 else ''}\t{sk1['skin'] if sk1 else ''}\t{mid:.0f}\t{big:.0f}\t"
              + "\t".join(f"{p[c]:.0f}" for c in cols)
              + f"\t{energy:.0f}\t{eng:.1f}\t{app:.1f}\t{swin}\t{rf}"
              + "".join(f"\t{b:.2f}" for b in bz.values()))
        if r:
            totals["secs"] += t1 - t0
            totals["energy"] += energy
            totals["engine"] += eng
            totals["app"] += app
        if a.threads and cpu:
            for (k, comm), v in cpu.most_common(12):
                if v >= 0.5:
                    print(f"    {k:6s} {comm:24s} {v:7.1f} s")
    print(f"\nquestions: {totals['secs']:.0f} s, {totals['energy']:.0f} J, engine CPU {totals['engine']:.0f} s, "
          f"app CPU {totals['app']:.0f} s")


if __name__ == "__main__":
    main()
