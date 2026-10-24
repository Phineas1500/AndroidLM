#!/usr/bin/env python3
"""The phone's power rails from a perfetto trace (android.power data source with
collect_power_rails), without the perfetto tools: a minimal protobuf reader for the PowerRails
packets (Trace.packet = 1, TracePacket.power_rails = 40; PowerRails.rail_descriptor = 1 {index 1,
rail_name 2, subsys_name 3}, energy_data = 2 {index 1, timestamp_ms 2, energy 3, in uWs}).

    power_rails.py trace.pftrace                  -> each rail's mean power (mW) over the trace
    power_rails.py trace.pftrace --csv out.csv    -> also every sample: t_ms, rail, energy_uws
    power_rails.py trace.pftrace --windows w.tsv  -> mean power per window (lines: name start_ms end_ms,
                                                     in the trace's timestamp_ms clock)
"""
import argparse
import bisect
import collections


def varint(b, i):
    n = shift = 0
    while True:
        c = b[i]
        i += 1
        n |= (c & 0x7F) << shift
        if c < 0x80:
            return n, i
        shift += 7


def fields(b):
    """(field number, wire type, value) of one message: an int, or bytes for length-delimited."""
    i = 0
    while i < len(b):
        key, i = varint(b, i)
        f, wt = key >> 3, key & 7
        if wt == 0:
            v, i = varint(b, i)
        elif wt == 1:
            v, i = int.from_bytes(b[i:i + 8], "little"), i + 8
        elif wt == 2:
            n, i = varint(b, i)
            v, i = b[i:i + n], i + n
        elif wt == 5:
            v, i = int.from_bytes(b[i:i + 4], "little"), i + 4
        else:
            raise ValueError(f"wire type {wt}")
        yield f, wt, v


def read(path):
    """({index: name}, {index: [(timestamp_ms, energy_uws)]})."""
    names, samples = {}, collections.defaultdict(list)
    for f, _, packet in fields(open(path, "rb").read()):
        if f != 1:
            continue
        for pf, _, rails in fields(packet):
            if pf != 40:
                continue
            for rf, wt, msg in fields(rails):
                if wt != 2 or rf not in (1, 2):
                    continue
                d = {k: v for k, _, v in fields(msg)}
                if rf == 1:
                    names[d.get(1, 0)] = d.get(2, b"").decode()
                elif rf == 2:
                    samples[d.get(1, 0)].append((d.get(2, 0), d.get(3, 0)))
    for s in samples.values():
        s.sort()
    return names, samples


def energy_at(s, t):
    """A rail's cumulative energy (uWs) at time t (ms), interpolated between samples."""
    ts = [x[0] for x in s]
    j = bisect.bisect_left(ts, t)
    if j <= 0:
        return s[0][1]
    if j >= len(s):
        return s[-1][1]
    (t0, e0), (t1, e1) = s[j - 1], s[j]
    return e0 + (e1 - e0) * (t - t0) / max(t1 - t0, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trace")
    ap.add_argument("--csv")
    ap.add_argument("--windows")
    a = ap.parse_args()
    names, samples = read(a.trace)
    order = sorted(samples, key=lambda k: names.get(k, str(k)))
    for k in order:
        s = samples[k]
        if len(s) > 1:
            mw = (s[-1][1] - s[0][1]) / max(s[-1][0] - s[0][0], 1)  # uWs per ms = mW
            print(f"{names.get(k, k):24s} {mw:9.1f} mW  ({len(s)} samples, {(s[-1][0] - s[0][0]) / 1000:.0f} s)")
    if a.csv:
        with open(a.csv, "w") as out:
            out.write("t_ms,rail,energy_uws\n")
            for k in order:
                for t, e in samples[k]:
                    out.write(f"{t},{names.get(k, k)},{e}\n")
    if a.windows:
        print()
        print("window\tseconds\t" + "\t".join(names.get(k, str(k)) for k in order))
        for line in open(a.windows):
            name, t0, t1 = line.split()
            t0, t1 = float(t0), float(t1)
            mws = [(energy_at(samples[k], t1) - energy_at(samples[k], t0)) / max(t1 - t0, 1) for k in order]
            print(f"{name}\t{(t1 - t0) / 1000:.0f}\t" + "\t".join(f"{x:.0f}" for x in mws))


if __name__ == "__main__":
    main()
