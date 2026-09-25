#!/usr/bin/env python3
"""Print decompiled functions from a DecompileAll.java dump, optionally with callees.

usage: fn.py DUMP.c NAME|ADDR [...] [-d DEPTH] [-x SKIP_REGEX]
NAME is a Ghidra function name (FUN_00075200, jni_dab_api_tune); ADDR is hex (75200).
"""
import argparse
import re

ap = argparse.ArgumentParser()
ap.add_argument("dump")
ap.add_argument("names", nargs="+")
ap.add_argument("-d", "--depth", type=int, default=0)
ap.add_argument("-x", "--skip", default=r"^(FUN_00012|__|mem|str|pthread|usleep)")
a = ap.parse_args()

funcs, addr2name, cur = {}, {}, None
for line in open(a.dump, errors="replace"):
    m = re.match(r"// ==== (\S+) @ ([0-9a-f]+)", line)
    if m:
        cur = m.group(1)
        funcs[cur] = []
        addr2name[int(m.group(2), 16)] = cur
        continue
    if cur and not line.startswith("/* WARNING") and line.strip():
        funcs[cur].append(line.rstrip("\n"))


def resolve(n):
    if n in funcs:
        return n
    try:
        return addr2name.get(int(n, 16))
    except ValueError:
        return None


seen = set()
todo = [(resolve(n), 0) for n in a.names]
while todo:
    n, d = todo.pop(0)
    if not n or n in seen:
        continue
    seen.add(n)
    print(f"// ==== {n}")
    print("\n".join(funcs[n]))
    print()
    if d < a.depth:
        body = "\n".join(funcs[n][1:])
        for c in dict.fromkeys(re.findall(r"\b(FUN_[0-9a-f]{8}|jni_\w+)\(", body)):
            if c != n and not re.match(a.skip, c):
                todo.append((c, d + 1))
