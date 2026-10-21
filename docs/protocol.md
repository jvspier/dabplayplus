# DTR-100 USB protocol (from static analysis)

Source: `libdab.so` (x86 build) from vendor APK `DabPlayer-3.26.apk`, decompiled with
Ghidra 12.1.4 (`decompiled/3.26/libdab-x86.c`, Ghidra addresses, image base 0x10000).
Everything here is **[verified in decompiled code]** unless tagged **[hypothesis]**. None of it
has been checked against the real dongle unless tagged **[verified on hw]**. Function names like `FUN_00075200` point
into the decompiled file.

## Layers

```
 Java app (packed, not analysed)
   │  JNI: dab_api_*, decoder_*, fic2_*, msc_p_*        (RegisterNatives on com/ex/dabplayer/pad/dab/Dab)
 libdab.so
   ├─ DAB software stack: FIC/FIG parser, MSC framing, FAAD2 (DAB+ HE-AAC), mpg123 (DAB MP2), OpenSL ES out
   ├─ chip driver: 8-bit register read/write, paged via reg 0x03   (a RAONTECH chip; first guessed FCI)
   └─ MCU bridge: 4-byte commands on bulk EP 0x02, replies on bulk EP 0x82
 USB MCU (unmarked 28-pin) ── SPI ──> DAB chip ("Super Radio")
```

The host does all the DAB decoding above the demodulator. The chip hands out raw FIC
(384 bytes per 96 ms frame) and raw sub-channel (MSC) data.

## USB transport
- Java opens the device with `UsbManager` and passes the fd to `dab_api_init`.
  The native code wraps it (libusbhost-style `usb_device_new`), **claims interface 0**
  (on EBUSY it disconnects the kernel driver and retries), then only uses
  `USBDEVFS_BULK` (`FUN_0007b9a0`, max 0x4000 bytes per call).
- **Bulk OUT EP 0x02** carries commands, **bulk IN EP 0x82** carries replies. **EP 0x81 is
  never used.** The library has no vendor control transfers (the generic
  `usb_device_control_transfer` has no callers; string-descriptor getters are unused).
- Timeouts: 1000 ms for most calls; 2000/4000 ms for power on and SPI write acks.

## MCU command set
Header = 4 bytes `[cmd, b1, b2, b3]`. The reply's first byte is `cmd | 0x80`.

| cmd | name | request | reply | notes |
|-----|------|---------|-------|-------|
| 0x00 | echo | `00 00 00 00` | 4 bytes; bytes 1..3 XOR request bytes 1..3 must be `'D' 'A' 'B'` | "echo OK" (`FUN_00075200`). **[verified on hw]** `80 44 41 42` |
| 0x01 | version | `01 00 00 00` | 4 bytes `[?, a, b, c]`, logged as nibbles "Hw Version: x.y ; Sw Version: z.w" | **byte 3 = firmware level**, stored and used to select code paths (see below) (`FUN_00075360`). **[verified on hw]** `81 00 00 04` = level 4 |
| 0x10 | power | `10 00 00 op`; op 0 = off, 1 = on, 2 = reset | byte 0 = 0x90 | power on waits up to 4 s (`FUN_00075560/756d0/75840`). **[verified on hw]** on → `90 00 00 00` |
| 0x20 | chip select | `20 00 00 cs` | byte 0 = 0xA0 | "SPI-CS 0/1" (`FUN_00075980`); not seen in the normal init path |
| 0x21 | SPI write | `21 00 00 n` + n data bytes (same transfer) | 1 byte = 0xA1 | register write = `21 00 00 02 reg val` (`FUN_00076550`). **[verified on hw]** ack is exactly 1 byte `a1` |
| 0x22 | SPI write+read | `22 00 nlo nhi` + write bytes | `A2 00 nlo nhi` + n bytes | register read = `22 00 01 00 reg` → `A2 00 01 00 val`; burst read = same with n>1 (`FUN_00075d00`). **[verified on hw]** for n=1 |
| 0x40 | MSC read | `40 00 nlo nhi` | `C0 00 mlo mhi` + m bytes (m = actual) | fw ≥ 2 (`FUN_00075ff0`). **[verified on hw]** raw sub-channel bytes |
| 0x41 | set MSC size | `41 00 slo shi` | byte 0 = 0xC1 | fw ≥ 2; `dab_api_set_msc_size`, 0 = stop (`FUN_000770e0`, `FUN_00077240`). s = bitrate × 3 (bytes per 24 ms CIF). **[verified on hw]** |
| 0x42 | get MSC status | `42 00 00 seq` (seq = rolling counter) | 6 bytes: `[C2?, ?, len_lo, len_hi, sig_lo, sig_hi]` | available bytes + a signal value used by `dab_api_get_signal` on fw ≥ 2 (`FUN_00077990`). **[verified on hw]** reply starts `c2`, bytes 2..3 = available bytes; bytes 4..5 = the signal value (0 = perfect, 2000 = no signal) **[verified on hw 2026-09-27]**, exactly what `dab_api_get_signal` returns on fw ≥ 2 (`FUN_000773c0`); it is RAON's CER (channel error rate), see findings 2026-09-28 |
| 0x43 | flag | `43 00 00 (x & 1)` | byte 0 = 0xC3 | fw ≥ 5 only; `dab_api_set_flag` (`FUN_00076390`) |

Firmware-level dependent behaviour (`DAT_002025ec` = version reply byte 3):
- **≤ 1:** the host reads MSC straight from chip registers (page 0x0C reg 0x10) after polling
  status (page 9 reg 0x33).
- **≥ 2:** the MCU buffers MSC itself. The host polls with 0x42 and pulls with 0x40 (max
  0x480 bytes per read in the flush loop, 4096 in the JNI read).
- **≥ 5:** `set_flag` uses 0x43 instead of 0x41.
- Implemented for all three in `tools/dtr100.py` (`start_audio`, `msc_read`, `stop_audio`, `quality`). **Only level 4 is
  verified on hardware.** ≤ 1 (`FUN_00079e20`: page 9 reg 0x33 bits 4..6 = data/overflow, page 0x0C reg 0x10 burst of n
  bytes, ack page 9 reg 0x35 = 4, overflow reset page 9 reg 0x48 0→5) and ≥ 5 (0x43 on/off) are straight from the decompile.
  [hypothesis] On ≥ 5 the packed Java calls `set_flag(1)` (0x43 1) after `set_msc_size` to start streaming.
- Proper stop (`dab_api_set_flag(0)` → `FUN_00069800(0)` → `FUN_000775b0`): page 9 reg 0x3A = subch (enable bit
  cleared), page 7 reg 0x62 |= 0x70 then |= 0x0E (mask the audio and data buffer IRQs), flush the MCU buffer
  (0x42 status → `41 00 len` → `40 00 len` until empty, max 0x480 per round), then `41 00 00 00` (fw 2–4) or
  `43 00 00 00` (fw ≥ 5).
- Signal quality on any firmware (`FUN_000719d0`, the `get_signal` path for fw ≤ 1): page 9, if reg 0x37 bit 0: error and
  total bit counters (regs 0x8C..0x8F / 0x88..0x8B, 32-bit big-endian) → err*1000/total*10; else 8000.

## DAB chip register interface
- 8-bit registers, **page select = write reg 0x03**. Pages seen: 4, 6, 7, 9, 0x0A, 0x0C, 0x0F.
- Init sanity check ("check interface", `FUN_00077b70`/`FUN_000788a0`): page 7, write reg
  0x7D = 0x06, read reg 0x7D, expect 0x06. **[verified on hw]** The vendor library tries it once; DABdream+ up to
  100 times 5 ms apart (`RaonTunerInput::tunerPowerUp`), and so do we since issue #3 (a stick that needed time).
- Page 0x0F = RF/tuner: PLL word in regs 0x23..0x26, start with reg 0x20 = 0, lock/readback
  in regs 0x12..0x15 (reg 0x15 bit 1 = lock). Clock plan in regs 0xE8..0xEE (reg 0x15 bit 0 =
  plan applied; polled 10 × 10 ms, else `FUN_00078b20` returns -14 and the tune fails with it, the plan unchanged).
- Page 6 reg 0x10: `0x48` then `0xC9` = demodulator restart (after every tune).
- Page 9 = buffers/interrupts: reg 0x33 = status (bit 0 = FIC ready, bits 4..6 = MSC
  ready/overflow), reg 0x35 = ack/clear bits, reg 0x3A = audio sub-channel select
  (`subch | enable<<7 | type<<6`), regs 0x3D.. = data sub-channels, regs 0x56/0x57 and
  0x50/0x51 = buffer thresholds, regs 0x47/0x48 = buffer resets.
- Page 0x0A reg 0x10 = FIC buffer (read 384 bytes).
- Page 0x0C reg 0x10 = MSC buffer (fw ≤ 1 path).
- Page 7 reg 0x62 = interrupt mask (shadowed in `DAT_00657c51`).

## Sequences

### Open (`dab_api_init` → `FUN_000690e0`) **[verified on hw, ported in tools/dtr100.py]**
1. Signature check of the calling APK (exits if the cert isn't one of 4 known SHA1s). Not relevant to us.
2. Claim interface 0.
3. Power on: `10 00 00 01` → expect `90 ..`.
4. Check interface: page 7, reg 0x7D = 6, read back 6.
5. System init `FUN_00073220`: about 150 register writes across pages 4/6/7/9, the band
   default for clock plan 0, and RF init `FUN_00079600(1)` (page 0x0F, 99 pairs from the
   table at 0x00100480 plus 2 more). Values in `decompiled/3.26/tables.json`
   (`tools/extract_tables.py`).
6. The Java side presumably calls `dab_api_echo` / `dab_api_version` around this. The version
   *must* be read before streaming, because it selects the MSC path. [hypothesis on order: the Java is packed]

### Tune (`dab_api_tune(freq_khz)` → `FUN_00072400`) **[verified on hw for band III, full port in `DTR100.tune`]**
- Stops active sub-channels, picks the band (`< 1 452 960 kHz` → Band III, else L-band),
  programs the PLL + clock plan (`FUN_00079080`), restarts the demodulator, then polls for sync
  (page 6 reg 0xCF == 3 = locked, 1 = no signal) for up to about 150 ms, with signal-quality checks.
  Returns 0 on lock, -4 on no signal. After sync: loop A (16 × 10 ms) = signal gate on u1 = page 4
  reg 0x39<<8|0x38 against thresholds that depend on mode bits/plan/freq; loop B (≤ 75 × 10 ms) sets
  latch bits (page 6 0x82|=1, 0x1C|=0x10, 0x44|=0x80) until regs 0x17/0x18/0x7E/0x7F settle; final loop
  waits for page 6 reg 0x12 bit 7, then writes the 'locked' config (page 6 0x54=0x58, page F 0x4B=0x80,
  page 4 0x55=0x15); verdict from page 9 regs 0xFB, 0x83..0x86.
- Band III index = `(f − 174928) / 1712` + a correction for the channel-block gaps; the table has
  41 entries (5A..13F plus the N channels). PLL words scale with f by about 341.39, which is
  consistent with a **12.288 MHz (= 24.576/2) reference**. [hypothesis]
- FM (76.0–108.0 MHz, 50 kHz steps) has a separate path (`FUN_00074920`, band 4).

### FIC (`dab_api_get_fic_data` → `FUN_00079c20`) **[verified on hw: 100 % FIB CRC on 5B/9D/11C]**
Page 9, burst-read 3 bytes from reg 0x33. If bit 0 is set: page 0x0A, burst-read **384 bytes**
from reg 0x10, then page 9 reg 0x35 = 1 (ack). The 384 bytes are 12 FIBs of 32 bytes (30 data +
CRC-16, checked on the host).

### Service playback
`dab_api_set_subid(subch)` stores the sub-channel id (0..63); `dab_api_set_msc_size(n)`
enables it (`FUN_00072160`: program audio sub-channel reg 0x3A, thresholds, then MCU cmd 0x41
with n). MSC data is then read with 0x42/0x40 (`jni_ma` → `FUN_00077990`) and fed to
the decoder. **[verified on hw]** The data is the raw sub-channel stream: for DAB+, standard ETSI TS
102 563 superframes (firecode, RS(120,110), AUs). Implemented in `tools/dabplus.py`. `msc_p_*` is
packet-mode data (slideshow/EPG), not audio.

## Open items
- Version reply: only byte 3 (firmware level) is used; bytes 1..2 unknown. The 0x42 reply is settled (table above:
  available bytes, then the CER).
- Not needed for the app, so left open: MSC packet-data framing (`msc_p_*`, packet-mode data services) and the
  packed Java side's call order (the native API was enough).
- Untested firmware paths (≤ 1 and ≥ 5): ported from the decompile; our dongle is level 4.
- Chip identity: **resolved 2026-09-28: a RAONTECH part.** Page register 0x03 is RAON's map select, and OMRI-USB's
  `RaonTunerInput` drives this dongle with identical commands (findings.md, work log). The earlier guess (FCI's BBM
  driver, FC80xx family) didn't fit the package and the FM support.
