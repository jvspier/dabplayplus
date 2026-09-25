#!/usr/bin/env python3
"""Offline decode of raw FIC captures (dtr100.py fic): services, user apps, service linking,
frequency information and other-ensemble services.

usage: .venv/bin/python tools/decode_fic.py captures/raw/2026-09-24-5B.fic [...]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import fic  # noqa: E402

CH = {f: n for n, f in fic.BAND3}


def fmt_freq(f):
    if isinstance(f, tuple):
        return f"raw:{f[1]}"
    if f >= 170000:
        return f"{CH.get(f, '?')} ({f / 1000:.3f})"
    return f"FM {f / 1000:.1f}"


def main():
    for path in sys.argv[1:]:
        d = open(path, "rb").read()
        lab = {}
        for i in range(0, len(d), 32):
            if fic.fib_ok(d[i:i + 32]):
                fic.parse_fib(d[i:i + 32], lab)
        svc = lab.get("services", {})
        print(f"=== {os.path.basename(path)}: ensemble {lab.get('ensemble')!r} EId {lab.get('eid', 0):04X} "
              f"ECC {lab.get('ecc', 0):02X}")
        print("  user apps per service:")
        for sid in sorted(svc):
            print(f"    {sid:04X} {svc[sid]:<16} {', '.join(sorted(lab['apps'].get(sid, []))) or '-'}")
        for sid in sorted(set(lab["apps"]) - set(svc)):
            print(f"    {sid:04X} {'(data service)':<16} {', '.join(sorted(lab['apps'][sid]))}")
        if lab["links"]:
            print("  service linking (FIG 0/6):")
            for (ils, lsn), ls in sorted(lab["links"].items()):
                ids = ", ".join(f"{t} {i:04X}" + (f" ({svc[i]})" if t == "DAB" and i in svc else "")
                                for t, i in sorted(ls["ids"]))
                print(f"    LSN {lsn:03X}{' intl' if ils else ''} {'hard' if ls['hard'] else 'soft'}"
                      f"{' active' if ls['active'] else ''}{' OE' if ls['oe'] else ''}: {ids or '(no Id list)'}")
        if lab["fi"]:
            print("  frequency information (FIG 0/21):")
            rmn = {0: "DAB ens", 8: "FM+RDS PI", 9: "FM", 6: "DRM", 14: "AMSS"}
            for (oe, rm, fid), fs in sorted(lab["fi"].items(), key=lambda kv: (kv[0][0], kv[0][1], kv[0][2])):
                name = svc.get(fid, "")
                print(f"    {'other' if oe else 'this '} {rmn.get(rm, f'R&M{rm}'):<9} {fid:04X} {name:<16} "
                      f"{', '.join(fmt_freq(f) for f in sorted(fs, key=str))}")
        if lab["oe_services"]:
            print("  services also on other ensembles (FIG 0/24):")
            for sid, eids in sorted(lab["oe_services"].items()):
                print(f"    {sid:04X} {svc.get(sid, ''):<16} -> EId {', '.join(f'{e:04X}' for e in sorted(eids))}")


if __name__ == "__main__":
    main()
