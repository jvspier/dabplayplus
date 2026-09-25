#!/usr/bin/env python3
"""Offline decode of a raw sub-channel capture (dtr100.py play --raw): superframes, AUs, PAD.

Prints DLS changes and DL Plus tags with timestamps, saves slideshow images next to the
capture (<capture>.slides/). Optionally decodes the audio to WAV.

usage: .venv/bin/python tools/decode_raw.py captures/raw/x.msc [--wav out.wav]
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import dabplus  # noqa: E402
import pad  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("capture")
    ap.add_argument("--wav")
    a = ap.parse_args()
    meta = json.load(open(a.capture + ".json"))
    dec = dabplus.SuperframeDecoder(meta["bitrate"])
    slides_dir = a.capture + ".slides"
    t = [0.0]

    def on_dls(label):
        print(f"[{t[0]:6.1f}s] DLS: {label!r}")

    def on_dlplus(tags, running, toggle):
        print(f"[{t[0]:6.1f}s] DL+: {tags} (item {'running' if running else 'stopped'}, toggle {toggle})")

    def on_slide(tid, ctype, csub, params, body):
        os.makedirs(slides_dir, exist_ok=True)
        ext = {(2, 1): "jpg", (2, 3): "png", (2, 0): "gif"}.get((ctype, csub), "bin")
        name = os.path.basename(params.get("ContentName", f"tid{tid}")).replace("/", "_")
        fn = os.path.join(slides_dir, f"{int(t[0]):04d}s-tid{tid}-{name}")
        if not fn.lower().endswith("." + ext):
            fn += "." + ext
        open(fn, "wb").write(body)
        print(f"[{t[0]:6.1f}s] SLIDE: {len(body)} bytes type {ctype}/{csub} {params} -> {fn}")

    p = pad.PadDecoder(on_dls, on_dlplus, on_slide)
    faad = dabplus.Faad() if a.wav else None
    wav = None
    print(f"{meta['service']} ({meta['channel']}, subch {meta['subch']}, {meta['bitrate']} kbit/s)")
    for asc, au in dec.feed(open(a.capture, "rb").read()):
        t[0] = dec.stats["superframes"] * 0.120
        p.feed_au(au)
        if faad:
            pcm = faad.decode(asc, au)
            if pcm and wav is None:
                import wave
                wav = wave.open(a.wav, "wb")
                wav.setnchannels(faad.channels); wav.setsampwidth(2); wav.setframerate(faad.rate)
            if pcm:
                wav.writeframes(pcm)
    if wav:
        wav.close()
    print(f"superframes {dec.stats}  pad {p.stats}")


if __name__ == "__main__":
    main()
