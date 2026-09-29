# C100 "Dual Antenna" (YHX) high-level USB protocol

Status: **untested on hardware.** Written from static analysis of how DABdream+ 26.9.1 drives these sticks through
YHX's `tuner_api` library (2026-09-29). All facts in our own words; no code copied. Driver:
`android/driver-dtr100/.../C100HighLevel.kt`. Compare `docs/protocol.md` (the register-level protocol of our DTR-100).

## Who speaks it
- Same USB ID and framing as the DTR-100 (`16c0:05dc`, bulk OUT 0x02 / IN 0x82). The MCU drives the DAB chip itself;
  there is no register access. The host still gets raw FIC and MSC and decodes them (dab-core).
- DABdream+ tries the register-level driver first (a write/read-back check of page 7 register 0x7D). Only when that
  fails does it use this command set. `C100.open` does the same.
- A stick is taken on only if the identify command answers `C2D` (dual antenna) or it reports firmware level ≥ 10
  (the two cases the library can drive; DABdream+ itself accepts any stick at this point).

## Framing
One OUT transfer, then one IN transfer of a fixed length. Reply byte 0 = command | 0x80; that is the only check, plus
the length field of the data reads. Multi-byte fields little-endian. No retries in the library.

| cmd | request | timeouts W/R (ms) | reply length | notes |
|---|---|---|---|---|
| 0x00 echo | `00 00 00 00` | 2000 / 8000 | 4 | byte 0 only |
| 0x01 version | `01 00 00 00` | 1000 / 10000 | 4 | byte 3 = firmware level (0 if the reply is bad) |
| 0x02 identify | `02 00 00 00` | 2000 / 10000 | 5 | `82 'C' '2' 'D' ..` = dual antenna |
| 0x10 power on | `10 00 00 01` | 2000 / 8000 | 4 | dual only, at init |
| 0x59 dual init | `59 00 00 00` | 2000 / 8000 | 2 | dual only, right after power on; meaning unknown |
| 0x48 tune | `48 00` + kHz u32 | 2000 / 2000 | 5 | reply byte 1 == 1 → locked |
| 0x41 MSC size | `41 00` + u16 | 2000 / 2000 | 4 | bytes per 24 ms CIF (bitrate × 3); size 0 = stream off on fw ≤ 4 |
| 0x43 stream flag | `43 00 00 f 00` | 2000 / 2000 | 4 | 5 bytes; f = 1 on, 0 off |
| 0x49 start audio | `49 00` + kHz u32 + bitrate u16 + sub-channel u8 + `01` | 2000 / 2000 | 5 | reply ignored |
| 0x46 status | `46 00 00 00` | 2000 / 2000 | 12 (14 on dual) | see below |
| 0x44 FIC | `44 00 80 01` | 2000 / 2000 | 388 | `C4 xx 80 01` + 384 bytes = 12 FIBs |
| 0x40 MSC | `40 00` + n u16 | 2000 / 2000 | n + 4 | `C0 xx` + n u16 + n bytes; length must equal n |
| 0x58 stop | `58 00 00 00` | 2000 / 8000 | 2 | |

Status reply (u16 LE): [2..3] MSC bytes waiting, [4..5] FIC bytes waiting, [6..7] packet-data bytes, [8..9] the
signal value (0 = perfect), [12..13] a second value on dual sticks (labelled "cnr" by DABdream+, unused).

Firmware gates in the library: stream off is `41 00 00 00` on fw ≤ 4 and `43 00 00 00 00` from fw 5; stream on is
always `43 00 00 01 00`; `41 00 <size>` before stream on only on dual sticks.

## Sequences
- **Init:** echo, version, identify, version again; dual: power on, 0x59.
- **Tune:** stream off, 0x48. One try outside a scan; in a scan up to 10 tries, 20 ms apart.
- **Start audio:** stream off, 0x49, (dual) 0x41 size, stream on.
- **Poll while playing** (DABdream+'s loop): 0x46; 0x44 if ≥ 384 FIC bytes wait (the host then subtracts 384); if MSC
  bytes wait, two 0x40 reads of one CIF each. With nothing to do it sleeps 20 ms.
- **Stop:** stream off, 0x58. Closing sends nothing (no power off).

## Signal
DABdream+ maps the status signal value to 0..5: < 40 → 5, < 100 → 4, < 200 → 3, < 400 → 2, < 600 → 1, else 0. We
fold that into our 0..4 bars (< 100 → 4, < 200 → 3, < 400 → 2, < 600 → 1, else 0) and treat ≥ 600 as no signal
[hypothesis: it is the same kind of error-rate value as our DTR-100's 0..2000].

## Open questions (need a stick)
- Does an MSC read for more bytes than are waiting block, pad or answer short? We copy DABdream+ (one CIF per read,
  length must match), which works for its users.
- Reply bytes 2..4 of the tune; the firmware level real dual sticks report; what 0x59 does; status bytes 10..13.
- Whether these sticks report the manufacturer string `dabusb@126.com` (the app requires it for 16c0:05dc, because
  that USB ID is shared with unrelated hobby devices).
