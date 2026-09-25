# Captures

One line per capture: filename, date, action performed, notes.

| File | Date | Action | Notes |
|------|------|--------|-------|
| `2026-09-23-scan-band3.log` | 2026-09-23 | `tools/dtr100.py scan`: power on, system init, tune all 41 band III channels, 2 s FIC read on locked ones | Not usbmon: our own transfer log (`->` request, `<-` reply), 61 962 lines. Antenna at a window, the Netherlands. Locks on 5B, 9D, 11C |
| `2026-09-24-scan-11c-5b-subch.log` | 2026-09-24 | `dtr100.py scan --channels 11C,5B` with FIG 0/1 + 0/2 parsing | Transfer log. Sub-channel and bitrate for every service |
| `2026-09-24-play-538.log` + `538.wav` | 2026-09-24 | `dtr100.py play --channel 11C --service 538 --seconds 15 --out captures/538.wav` | First audio: 538 NONSTOP, 236/236 AUs OK, 14.1 s 32 kHz stereo WAV |
| `raw/2026-09-24-{11C-538,11C-qmusic,5B-omroep_zeeland}.msc` (+ `.json`, `.slides/`) | 2026-09-24 | `dtr100.py play --raw ... --seconds 90` | Raw sub-channel bytes for offline decoding (`tools/decode_raw.py`). Slides = extracted station logos |
| `2026-09-24-scan-band3-fulltune.log` | 2026-09-24 | `dtr100.py scan` with the full tune port | Locks 5B, 9C, 9D, 11C, 12C; 11B marginal |
| `raw/2026-09-24-{5B,9C,9D,11C,12C}.fic` | 2026-09-24 | `dtr100.py fic --seconds 60` | Raw FIC (384-byte blocks). Decode with `tools/decode_fic.py` |
| `2026-09-24-scan-band3-fast.log` | 2026-09-24 | `dtr100.py scan` with FIC early stop + retries | ≈ 19 s full scan |
| `raw/2026-09-24-{12C-npo_radio_2,12C-npo_3fm,11C-sky_radio,11C-radio_10,11C-100p_nl,9C-kink}.msc` + `coverart-hunt.txt` | 2026-09-24 | `dtr100.py play --raw --seconds 240` per station | Cover-art hunt: NPO sends per-song album-cover slides; others logo only |
| `raw/2026-09-25-5B-{radionl,tukker_fm,the_groove,sunrise_fm}.msc` | 2026-09-25 | `dtr100.py play --raw --seconds 20` | HE-AAC v2 (PS) samples, 48 kbit/s |
| `android/*` | 2026-09-25 | `tools/export_aus.py` + MediaCodecTest on emulators API 26/29 | AU files, faad2 reference WAVs, MediaCodec PCM (`*.api26.pcm`, `*.api29.pcm`) |
| `2026-09-25-switch-538-qmusic.log`, `switch-a.wav`, `switch-b.wav` | 2026-09-25 | `tools/switch_test.py --channel 11C --a 538 --b Qmusic` | Proper stop + switch without re-init: 0.90 s |
| `2026-09-25-fm-{first,diag,seek,104.4}.log`, `2026-09-25-fm.bin` | 2026-09-25 | `tools/fm_test.py` + diagnostics | FM via the vendor's dead-code FM API: PLL locks, weak S-curves at 88.0/96.0/104.4, gain at max, no audio |
