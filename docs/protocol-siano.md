# Siano USB DAB sticks (187f:0700 / 0800 / 0802)

Status: **untested on hardware.** Written from static analysis of how DABdream+ 26.9.1 drives these sticks
(2026-10-01); message names and layouts from the Linux kernel's Siano headers (facts only). All in our own words, no code
copied. Driver: `android/driver-siano/`.

## Which sticks
| USB id | Known as | Works here? |
|---|---|---|
| 187f:0800 | Siano SMS2160 "Rhine800" (Linux: Denver 1530) | yes, if its firmware reports "DAB8…" |
| 187f:0700, 187f:0802 | Denver 2160 / TDMB | yes, if its firmware reports "TDMB" or "T-DMB" |
| 187f:0600 | Siano SMS2270 "Rio" | **no**: its ROM ("Rio-A0") needs a Siano firmware file loaded by the host, which we can't distribute |

What decides is the firmware label from GET_VERSION_EX, not the USB id. The host decodes everything, as with our
DTR-100: the stick delivers raw FIBs and the raw sub-channel stream.

## Transport and messages
Interface 0; OUT **0x02**, IN **0x81**, plain bulk. Each message: an 8-byte header — type (u16), source (0), destination
(0x0B = the main module), length (u16, header included), flags (u16, 0) — then u32 words, all little-endian.
DABdream+ treats every bulk read as one message; we split a read by the length field. A message with the split flag
(0x0004) set is dropped with a log line (DABdream+ ignores the flag; whether these firmwares use it is unknown).

| type | name | payload | reply / use |
|---|---|---|---|
| 0x029C | GET_VERSION_EX | – | 0x029D: firmware label at offset 22 (up to 34 chars) |
| 0x0344 | SET_PERIODIC_STATISTICS | [1] | DAB8 firmware only: at init (then wait for a 0x031E) and before every tune |
| 0x0231 | RF_TUNE | [Hz, 15 (1.5 MHz bandwidth), 12000000, 0] | 0x0232; frequency 0 = untune |
| 0x024D | SUB_CHANNEL_START | 14 words, below | 0x024E |
| 0x024F | SUB_CHANNEL_STOP | [sub-channel id] | 0x0250 |
| 0x0267 | GET_STATISTICS | – | TDMB firmware: every 100 ms until locked, then every second; one at a time |
| 0x025E | FIC data | ← | [16..19] tuned frequency in Hz (must match), raw 32-byte FIBs from offset 20 |
| 0x025F | MSC data | ← | the started sub-channel's raw bytes from offset 8 |
| 0x0268 | statistics reply | ← | (≥ 160 bytes) locked at offset 20, pre-BER (1e-5) at 156 |
| 0x031E | periodic short statistics | ← | (≥ 48 bytes) locked at offset 8, pre-BER at 20 |
| 0x0325 | interface locked | ← | logged |

**SUB_CHANNEL_START** words, from the FIG 0/1 entry of the sub-channel (the driver decodes the stick's FIC for this):
[0, SubChId, start CU, size CU × 64, bitrate × 24 (DABdream+ masks it with 0x1FFFC0, a no-op for real bitrates; we do
the same), protection (0 UEP, 1 EEP-A, 2 EEP-B), level + 1 (EEP: the 2-bit
level field + 1; UEP: the table's level + 1, as DABdream+ sends it), UEP table index or −1, 0, −1, −1, 0, 1, −1].

## Sequences
- **Init:** GET_VERSION_EX; "TDMB"/"T-DMB" → ready; "DAB8…" → SET_PERIODIC_STATISTICS and wait for statistics;
  "Rio-A0" or anything else → refused.
- **Tune:** RF_TUNE; there is no lock answer, the statistics (or FIC arriving for this frequency) say it. DABdream+'s
  scan gives up after three "not locked" statistics in a row.
- **Play:** SUB_CHANNEL_START with the FIG 0/1 layout; MSC data follows.
- **Stop:** SUB_CHANNEL_STOP (our state is cleared even if the reply doesn't come). **Close:** stop, then untune.

## Signal
DABdream+ maps pre-BER to 0..5: ≤ 4000 → 5, ≤ 4200 → 4, ≤ 4500 → 3, ≤ 6000 → 2, ≤ 7500 → 1, else (or not locked) 0.
We fold that into 0..4 bars; not locked = no signal.

## Open questions (need a stick)
Which label each USB id reports; the FIC message's bytes 8..15 and FIBs per message; MSC message sizes; whether the
split flag ever appears; the meaning of SUB_CHANNEL_START words 0 and 8..13 and of the UEP "level + 1".
