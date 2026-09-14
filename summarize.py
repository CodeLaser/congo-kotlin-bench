#!/usr/bin/env python3
"""Summarise results.jsonl: per (parser, threads), the median over forks of each fork's median pass."""
import json
import statistics
import sys
from collections import defaultdict

path = sys.argv[1] if len(sys.argv) > 1 else "results.jsonl"
runs = defaultdict(list)
footprint = {}
for line in open(path):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    r = json.loads(line)
    if r.get("mode") == "footprint":
        footprint[r["parser"]] = r
    else:
        runs[(r["parser"], r["threads"])].append(r)

order = ["congo", "ast", "lighttree", "psi"]
threads = sorted({t for _, t in runs})
first = next(iter(runs.values()))[0]
print(f"corpus: {first['files']} files, {first['bytes'] / 1e6:.1f} MB")
print()
print(f"{'parser':<10} {'threads':>7} {'median ms':>10} {'MB/s':>8} {'fork spread':>12} {'vs congo':>9} "
      f"{'Mnodes/s':>9} {'alloc B/char':>13} {'scaling':>8}")
single = {}
for t in threads:
    congo_ms = None
    for p in order:
        rs = runs.get((p, t))
        if not rs:
            continue
        ms = statistics.median(r["median_ms"] for r in rs)
        spread = (max(r["median_ms"] for r in rs) - min(r["median_ms"] for r in rs)) / ms * 100
        if p == "congo":
            congo_ms = ms
        if t == 1:
            single[p] = ms
        mbs = rs[0]["bytes"] / 1e6 / (ms / 1e3)
        nps = rs[0]["nodes"] / 1e6 / (ms / 1e3)
        alloc = statistics.median(r["alloc_bytes_per_char"] for r in rs)
        vs = f"{congo_ms / ms:.2f}x" if congo_ms else ""
        scaling = f"{single[p] / ms:.1f}x" if p in single and t != 1 else ""
        print(f"{p:<10} {t:>7} {ms:>10.1f} {mbs:>8.1f} {spread:>11.1f}% {vs:>9} {nps:>9.1f} {alloc:>13.1f} {scaling:>8}")
    print()

if footprint:
    print("retained heap for the whole corpus's trees (after full walk):")
    for p in order:
        if p in footprint:
            f = footprint[p]
            print(f"  {p:<10} {f['retained_bytes'] / 1e6:>8.0f} MB   {f['retained_bytes_per_char']:.1f} bytes/char")
