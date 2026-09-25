#!/usr/bin/env python3
"""Station switch test: play A, proper stop, start B on the same ensemble without re-init.

Measures the switch time from stop to the first decoded audio of B. Decoded audio goes to WAV
files so it can be checked afterwards.

usage: .venv/bin/python tools/switch_test.py --channel 11C --a 538 --b Qmusic [--seconds 10] [--log x.log]
"""
import argparse
import os
import sys
import time
import wave

sys.path.insert(0, os.path.dirname(__file__))
import dabplus  # noqa: E402
import dtr100  # noqa: E402
import fic  # noqa: E402


def find(labels, name):
    for sid, lab in labels["services"].items():
        if lab.lower() == name.lower() and sid in labels["comps"]:
            c = next((c for c in labels["comps"][sid] if c["primary"]), labels["comps"][sid][0])
            sc = labels["subch"].get(c["subch"])
            if sc and sc.get("bitrate"):
                return sid, c, sc
    sys.exit(f"{name!r} not found; services: {sorted(labels['services'].values())}")


def play_for(d, name, labels, seconds, wav_path, t_ref):
    sid, comp, sc = find(labels, name)
    dec, faad = dabplus.SuperframeDecoder(sc["bitrate"]), dabplus.Faad()
    d.start_audio(comp["subch"], sc["bitrate"] * 3)
    first = None
    wav = None
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        data, _ = d.msc_read()
        if not data:
            time.sleep(0.005)
            continue
        for asc, au in dec.feed(data):
            pcm = faad.decode(asc, au)
            if not pcm:
                continue
            if first is None:
                first = time.monotonic() - t_ref
                wav = wave.open(wav_path, "wb")
                wav.setnchannels(faad.channels); wav.setsampwidth(2); wav.setframerate(faad.rate)
            wav.writeframes(pcm)
    if wav:
        wav.close()
    return first, dec.stats, faad.errors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", default="11C")
    ap.add_argument("--a", default="538")
    ap.add_argument("--b", default="Qmusic")
    ap.add_argument("--seconds", type=float, default=10)
    ap.add_argument("--log")
    ap.add_argument("--outdir", default="captures")
    a = ap.parse_args()
    d = dtr100.DTR100(verbose=False, log=open(a.log, "w") if a.log else None)
    d.load_tables("decompiled/3.26/tables.json")
    try:
        print(f"firmware level {d.version()}")
        d.power_on()
        d.system_init()
        f = dict(fic.BAND3)[a.channel]
        for _ in range(3):
            rc, _info = d.tune(f)
            if rc == 0:
                break
        else:
            sys.exit("no lock")
        d.fic_enable()
        labels, *_ = dtr100.read_service_list(d)
        t0 = time.monotonic()
        first_a, st_a, err_a = play_for(d, a.a, labels, a.seconds, f"{a.outdir}/switch-a.wav", t0)
        print(f"A {a.a!r}: first audio after {first_a:.2f}s (start from tuned), {st_a}, faad errors {err_a}")
        t1 = time.monotonic()
        d.stop_audio()
        t_stop = time.monotonic() - t1
        first_b, st_b, err_b = play_for(d, a.b, labels, a.seconds, f"{a.outdir}/switch-b.wav", t1)
        print(f"stop took {t_stop * 1000:.0f} ms")
        print(f"B {a.b!r}: first audio {first_b:.2f}s after stop began (switch time), {st_b}, faad errors {err_b}")
        d.stop_audio()
    finally:
        d.close()


if __name__ == "__main__":
    main()
