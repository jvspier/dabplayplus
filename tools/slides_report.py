#!/usr/bin/env python3
"""Cover-art hunt: per raw capture, list distinct slideshow images and relate them to DLS changes.

A cover-art slide typically has a new ContentName/image shortly after the track (DLS) changes,
while a logo repeats with the same name/hash. Saves one copy per distinct image in
<capture>.slides/ (via decode_raw-style naming) and prints a timeline.

usage: .venv/bin/python tools/slides_report.py captures/raw/*.msc
"""
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import dabplus  # noqa: E402
import pad  # noqa: E402


def report(path):
    meta = json.load(open(path + ".json"))
    dec = dabplus.SuperframeDecoder(meta["bitrate"])
    t = [0.0]
    events = []
    seen = {}

    def on_dls(label):
        if label:
            events.append((t[0], "DLS", label))

    def on_slide(tid, ctype, csub, params, body):
        h = hashlib.sha1(body).hexdigest()[:10]
        name = params.get("ContentName", f"tid{tid}")
        first = h not in seen
        seen.setdefault(h, []).append(t[0])
        if first:
            outdir = path + ".slides"
            os.makedirs(outdir, exist_ok=True)
            ext = {(2, 1): "jpg", (2, 3): "png"}.get((ctype, csub), "bin")
            open(os.path.join(outdir, f"{int(t[0]):04d}s-{h}.{ext}"), "wb").write(body)
        events.append((t[0], "SLIDE", f"{name} {len(body)}B hash {h}{' NEW' if first else ''}"
                       + (f" cat {params['CategoryID/SlideID']}" if "CategoryID/SlideID" in params else "")
                       + (f" url {params['ClickThroughURL']}" if "ClickThroughURL" in params else "")
                       + (f" alt {params['AlternativeLocationURL']}" if "AlternativeLocationURL" in params else "")))

    p = pad.PadDecoder(on_dls=on_dls, on_slide=on_slide)
    for _asc, au in dec.feed(open(path, "rb").read()):
        t[0] = dec.stats["superframes"] * 0.120
        p.feed_au(au)
    print(f"=== {meta['service']} ({meta['channel']}), {t[0]:.0f} s, AUs ok {dec.stats['au_ok']}/"
          f"{dec.stats['au_ok'] + dec.stats['au_bad']}, MOT groups {p.stats['mot_dg']} "
          f"(CRC bad {p.stats['mot_crc_bad']}), slides {p.stats['slides']}, distinct images {len(seen)}")
    for ts, kind, text in events:
        print(f"  [{ts:6.1f}s] {kind:5} {text}")
    for h, times in seen.items():
        gaps = [round(b - a) for a, b in zip(times, times[1:])]
        print(f"  image {h}: shown {len(times)}x, repeat gaps {gaps}")


if __name__ == "__main__":
    for f in sys.argv[1:]:
        report(f)
