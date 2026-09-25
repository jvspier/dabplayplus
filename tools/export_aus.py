#!/usr/bin/env python3
"""Export DAB+ AUs from a raw capture for decoder tests (e.g. Android MediaCodec in an emulator).

Writes <out>.aus (each AU as 4-byte big-endian length + bytes), <out>.json (ASC + format) and
<out>.faad.wav (faad2 reference decode of the same AUs).

usage: .venv/bin/python tools/export_aus.py captures/raw/x.msc outdir/name [--seconds 30]
"""
import argparse
import json
import os
import struct
import sys
import wave

sys.path.insert(0, os.path.dirname(__file__))
import dabplus  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("capture")
    ap.add_argument("out")
    ap.add_argument("--seconds", type=float, default=30)
    a = ap.parse_args()
    meta = json.load(open(a.capture + ".json"))
    dec = dabplus.SuperframeDecoder(meta["bitrate"])
    faad = dabplus.Faad()
    os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
    n = 0
    asc0 = None
    wav = None
    with open(a.out + ".aus", "wb") as fa:
        for asc, au in dec.feed(open(a.capture, "rb").read()):
            if dec.stats["superframes"] * 0.120 > a.seconds:
                break
            if asc0 is None:
                asc0 = asc
            if asc != asc0:
                continue  # keep one format per file
            fa.write(struct.pack(">I", len(au)) + au)
            n += 1
            pcm = faad.decode(asc, au)
            if pcm:
                if wav is None:
                    wav = wave.open(a.out + ".faad.wav", "wb")
                    wav.setnchannels(faad.channels); wav.setsampwidth(2); wav.setframerate(faad.rate)
                wav.writeframes(pcm)
    if wav:
        wav.close()
    info = {"service": meta["service"], "asc": asc0.hex(), **dec.fmt, "n_aus": n,
            "faad_rate": faad.rate, "faad_channels": faad.channels, "faad_errors": faad.errors}
    json.dump(info, open(a.out + ".json", "w"), indent=1)
    print(json.dumps(info))


if __name__ == "__main__":
    main()
