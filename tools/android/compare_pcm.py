#!/usr/bin/env python3
"""Compare a MediaCodec PCM dump (s16le) with the faad2 reference WAV of the same AUs.

Finds the best alignment (decoders have different delays), then reports correlation and SNR.
usage: compare_pcm.py ref.faad.wav test.pcm
"""
import sys
import wave

import numpy as np

ref_w = wave.open(sys.argv[1])
ch, rate = ref_w.getnchannels(), ref_w.getframerate()
ref = np.frombuffer(ref_w.readframes(ref_w.getnframes()), "<i2").reshape(-1, ch).astype(np.float64)
tst = np.frombuffer(open(sys.argv[2], "rb").read(), "<i2").reshape(-1, ch).astype(np.float64)

# alignment on a 2 s window from the middle, mono mix, lags up to +-8192 samples
a = ref.mean(1)
b = tst.mean(1)
mid = min(len(a), len(b)) // 2
w = 2 * rate
seg = a[mid:mid + w]
best = max(range(-8192, 8193, 1), key=lambda L: float(np.dot(seg, b[mid + L:mid + L + w]))
           if 0 <= mid + L and mid + L + w <= len(b) else -1e30)
if best >= 0:
    ra, tb = ref[:len(ref) - 0], tst[best:]
else:
    ra, tb = ref[-best:], tst
n = min(len(ra), len(tb))
ra, tb = ra[rate:n - rate], tb[rate:n - rate]  # skip decoder start-up and tail
err = tb - ra
corr = [float(np.corrcoef(ra[:, c], tb[:, c])[0, 1]) for c in range(ch)]
snr = 10 * np.log10(np.sum(ra ** 2) / max(np.sum(err ** 2), 1e-9))
print(f"samples ref {len(ref)} test {len(tst)}  lag {best} ({best / rate * 1000:.1f} ms)  "
      f"corr L/R {corr[0]:.5f}/{corr[-1]:.5f}  SNR vs faad2 {snr:.1f} dB  "
      f"rms ref {np.sqrt(np.mean(ra ** 2)):.0f} test {np.sqrt(np.mean(tb ** 2)):.0f}")
