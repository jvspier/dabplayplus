# Si4684 module (Eonon A503, XFA-DAB220; USB 1a86:5537)

Status: **untested on hardware.** Written from static analysis of how DABdream+ 26.9.1 drives the module
(2026-09-29); all facts in our own words, no code copied. Driver: `android/driver-si4684/`. The engine side is the
`ModuleSession` in `DabEngine.kt`.

## What kind of dongle
A WCH microcontroller in front of a Silicon Labs Si4684. The module does everything: band scan, station list,
decoding, service following, text and slideshow. The host gets finished audio. There is **no FIC**, so no ensemble id,
no ECC and no alternative frequencies on the host side. The app therefore keys the stations by a guessed ECC (see
`EccGuess`) and gives each channel's ensemble a synthetic id above 0xFFFF. Announcements and our own service following
aren't available; the module's own service following is switched with the "Follow station" setting.

## USB
Interface 0, plain bulk (not a CDC serial port, no control requests). OUT **0x01** = commands; IN **0x82** = replies
and events (4096-byte reads, 500 ms); IN **0x81** = audio, **s16le interleaved stereo, 48 kHz**, no framing
(16384-byte reads, 500 ms).

## Framing
- Host → module: `55 10 L2 L1 L0` `AA cmd NH NL <payload> BB` `CS` `11`. L (24-bit BE) = N + 5; N (16-bit BE) = payload
  length; CS = the sum of the `AA`…`BB` bytes mod 256. Example: status request `55 10 00 00 05 AA 40 00 00 BB A5 11`.
- Module → host: `AA type NH NL <payload> BB`, type = command | 0x80 (0xB1 = slideshow). Parsed byte by byte with a
  state that survives reads; a length over 16384 or a missing `BB` drops that byte and hunts for the next `AA`. No
  checksum is checked (DABdream+ doesn't either). Empty payloads are ignored.

## Commands (the only ones DABdream+ sends)
| cmd | payload | reply | use |
|---|---|---|---|
| 0x30 | – | 0xB0 version text | init |
| 0x32 | – | – | init, only after a version reply (DABdream+: skipping it otherwise avoids resetting the module) |
| 0x40 | – | 0xC0 status | poll; sent again after every status, and as a keep-alive after > 0.5 s of silence (checked every 2 s) |
| 0x47 | – | 0xC7 station list | after a scan, when the status says the list changed |
| 0x01 | – | 0x81 progress | start the module's band scan (can't be stopped from the host) |
| 0x44 | index, freqIdx, SId (u32 BE), component (u16 BE) | 0xC4 [ok] | select a station |
| 0x48 | 0 / 1 | 0xC8 [ok] | audio off / on |
| 0x21 | – | 0xA1 text | when the status says there is new text; again after each text |
| 0x0C | 0 / 1 | – | the module's service following off / on, before every station |

## Payloads
- **0xC7 station list:** 44-byte records: [0] index, [1] freqIdx (the 41-channel band III list incl. 10N/11N/12N),
  [2..5] SId, [6..7] component handle, [8] charset (0 = EBU Latin, else UTF-8), [9..24] label, [26] PTY, [27] charset,
  [28..43] ensemble label. Labels end at the first NUL; trailing spaces trimmed.
- **0xC0 status (≥ 21 bytes):** [0] index, [1] freqIdx, [2..5] SId, [6..7] component, [10] level 0..5, [11] SNR,
  [13..14] bitrate, [16..17] sample rate, [18] PTY, [19] 1 = service following running, [20] bit 0 new text, bit 1
  list changed. DABdream+ smooths the level one step per status (from 0 straight to the value).
- **0x81 scan progress:** [0] percent, [2] services found. At 100 % the host asks for the list.
- **0xA1 text:** [0] charset, then the text.
- **0xB1 slideshow:** `73` header ([6] tag), then `74` chunks ([2] bit 7 = last, [3] sequence from 0, [6] tag,
  [7..8] 13-bit length, data from [9]). A wrong tag or sequence drops the image. The result is the JPEG or PNG itself.

## Sequences
- **Init:** 0x30 → wait ≤ 1 s for 0xB0 → (if it came) 500 ms, 0x32, 500 ms → 0x40 → 0x47.
- **Play:** 0x0C [follow] → 0x48 [0] (≤ 300 ms for 0xC8) → 150 ms → 0x44 with index 0 → the status naming the station
  gives its real index (≤ 2 s) → 0x44 with that index. Audio on (0x48 [1]) is sent as soon as a status names the
  station, which can be before the second 0x44.
- **Scan:** 0x01 → progress frames → at 100 % 0x47 → the list.
- **Stop:** DABdream+ sends nothing. We don't either, but keep reading 0x81 and drop it, so no stale audio queues up.

## Open questions (need a module)
Whether the module frames its replies like the host does (the parser copes either way); whether 0x21 is answered at
once (then the text request loop would run continuously, as it would in DABdream+); the scale of the status level; the
audio format for stations that aren't 48 kHz; what 0x32 does.
