#!/usr/bin/env python3
"""Golden reference output for the Kotlin dab-core tests, from the verified Python PoC.

For every capture in captures/raw it writes captures/golden/<name>.txt with canonical, sorted text lines:
  *.fic -> FIC content (ensemble, services, components, sub-channels, user apps, linking, FI, OE services)
  *.msc -> superframe/AU stream (one line per AU: index, length, sha1 prefix) + PAD events (DLS, slides)
The Kotlin tests produce the same lines from the same capture and compare. The captures and goldens are
local test material (gitignored).

usage: .venv/bin/python tools/golden.py
"""
import glob
import hashlib
import json
import os
import sys


def q(x):
    return json.dumps(x, ensure_ascii=False)

sys.path.insert(0, os.path.dirname(__file__))
import dabplus  # noqa: E402
import fic  # noqa: E402
import pad  # noqa: E402


def fic_lines(path):
    d = open(path, "rb").read()
    lab = {}
    ok = bad = 0
    for i in range(0, len(d) - 31, 32):
        fib = d[i:i + 32]
        if fic.fib_ok(fib):
            ok += 1
            fic.parse_fib(fib, lab)
        else:
            bad += 1
    out = [f"fibs ok {ok} bad {bad}",
           f"ensemble {lab.get('eid', 0):04X} ecc {lab.get('ecc', 0):02X} label {q(lab.get('ensemble', ''))}"]
    for sid, name in lab.get("services", {}).items():
        out.append(f"service {sid:04X} {q(name)}")
    for sid, comps in lab.get("comps", {}).items():
        for c in comps:
            out.append(f"comp {sid:04X} ascty {c['ascty']} subch {c['subch']} primary {int(c['primary'])}")
    for sc, v in lab.get("subch", {}).items():
        out.append(f"subch {sc} start {v['start']} size {v['size_cu']} bitrate {v['bitrate']} prot {v['prot']}")
    for sid, apps in lab.get("apps", {}).items():
        for a in apps:
            out.append(f"app {sid:04X} {a}")
    for (ils, lsn), ls in lab.get("links", {}).items():
        ids = " ".join(f"{t}:{i:X}" for t, i in sorted(ls["ids"]))
        out.append(f"link ils {ils} lsn {lsn:03X} hard {int(ls['hard'])} active {int(ls['active'])} oe {int(ls['oe'])} ids {ids}")
    for (oe, rm, fid), fs in lab.get("fi", {}).items():
        for f in fs:
            fv = f"raw:{f[1]}" if isinstance(f, tuple) else str(f)
            out.append(f"fi oe {oe} rm {rm} id {fid:04X} {fv}")
    for sid, eids in lab.get("oe_services", {}).items():
        for e in eids:
            out.append(f"oe_service {sid:04X} eid {e:04X}")
    return out[:2] + sorted(out[2:])


def msc_lines(path):
    meta = json.load(open(path + ".json"))
    dec = dabplus.SuperframeDecoder(meta["bitrate"])
    out = [f"bitrate {meta['bitrate']}"]
    events = []
    n = [0]
    t = {"sf": 0}

    def on_dls(label):
        events.append(f"dls {t['sf']} {q(label)}")

    def on_slide(tid, ctype, csub, params, body):
        events.append(f"slide {t['sf']} tid {tid} type {ctype}/{csub} name {q(params.get('ContentName', ''))} "
                      f"size {len(body)} sha1 {hashlib.sha1(body).hexdigest()[:12]}")

    p = pad.PadDecoder(on_dls=on_dls, on_slide=on_slide)
    for asc, au in dec.feed(open(path, "rb").read()):
        t["sf"] = dec.stats["superframes"]
        out.append(f"au {n[0]} sf {t['sf']} asc {asc.hex()} len {len(au)} sha1 {hashlib.sha1(au).hexdigest()[:12]}")
        n[0] += 1
        p.feed_au(au)
    s = dec.stats
    out.insert(1, f"stats superframes {s['superframes']} au_ok {s['au_ok']} au_bad {s['au_bad']} "
                  f"rs_fixed {s['rs_fixed']} rs_fail {s['rs_fail']}")
    return out + events + [f"pad dl_dg {p.stats['dl_dg']} dl_crc_bad {p.stats['dl_crc_bad']} "
                           f"mot_dg {p.stats['mot_dg']} mot_crc_bad {p.stats['mot_crc_bad']} slides {p.stats['slides']}"]


def main():
    root = os.path.join(os.path.dirname(__file__), "..", "captures")
    os.makedirs(os.path.join(root, "golden"), exist_ok=True)
    for path in sorted(glob.glob(os.path.join(root, "raw", "*.fic")) + glob.glob(os.path.join(root, "raw", "*.msc"))):
        lines = fic_lines(path) if path.endswith(".fic") else msc_lines(path)
        name = os.path.basename(path)
        open(os.path.join(root, "golden", name + ".txt"), "w").write("\n".join(lines) + "\n")
        print(f"{name}: {len(lines)} lines")


if __name__ == "__main__":
    main()
