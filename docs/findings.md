# Findings log

Every claim is tagged **[verified]** (seen directly) or **[hypothesis]** (inferred). Update
this file as work progresses.

## Status (2026-09-24, end of day)
- Phases 0 and 2 done; phase 4 (Linux PoC) nearly done. Protocol: `docs/protocol.md`. Tools: `tools/README.md`.
- The PoC (`tools/dtr100.py`) does all of this on real hardware:
  - `scan`: full band III in ≈ 19 s, reliably finds 5B, 9C, 9D, 11C, 12C (55 services).
  - `play --channel 12C --service "NPO Radio 2"`: DAB+ audio via faad2 → `pw-play`, with live DLS and slides.
    `--raw x.msc` saves the sub-channel for offline work (`tools/decode_raw.py`, `tools/slides_report.py`).
  - `fic`: raw FIC capture. `tools/decode_fic.py` shows user apps (SLS), service linking (DAB→FM), frequency
    info and other-ensemble services (regional service following).
- Researched and decided (see CLAUDE.md "App goals" / UI sections): offline-first; slideshow shown whole (NPO
  sends per-song album-cover cards); logo chain RadioDNS SPI → station website manifest icon → radio-browser →
  SLS logo → generated tile, cached per ECC+SId.
- **Next steps (owner to pick):**
  1. ~~Android decoder check~~ done 2026-09-25: MediaCodec (`OMX.google.aac.decoder`) decodes all three DAB+
     formats (HE-AAC v1 32/48 kHz, v2) correctly on API 26 and 29. No faad2 needed. Re-verify on the Dudu3.
  2. Smaller PoC gaps: MP2 (DAB classic) services (none received here), ~~service switching without re-init
     + proper stop~~ (verified 2026-09-25: 0.90 s switch), firmware ≤1/≥5 paths
     (implemented from the decompile, untestable here), signal level for the UI (u1 / page 9 regs 0x83..0x86 / 0x42 status bytes 4..5),
     DLS artist/title split rules per broadcaster. ~~FM~~: tried 2026-09-25, this dongle can't receive FM usefully (RF front end).
  3. Then the **Android design session with the owner** before writing any app code.
- Not a git repo yet (`git init` offered, not done).

## Dongle: DollaTek DTR-100
- [verified] Bought from Amazon.nl, "DollaTek DAB-radio-ontvanger", ASIN B09H6N3CBX,
  model/part ELA20586, about €24.
- [verified] The case is labeled "DAB+ Receiver for Android Navigation, Model: DTR-100". It
  has a USB-A plug and an SMA jack for the included windshield antenna.

### Teardown (see `docs/teardown/`)
- [verified] Two-board design: a purple module silkscreened **"Dip V1 202508"** is soldered
  onto a green carrier PCB.
- [hypothesis] "202508" is a date code (August 2025), so this is a recent revision.
- [verified] The carrier has the USB-A connector, with **unpopulated** pads ESD1, ESD2, C1,
  C2 and C4. The ESD protection was left off, presumably to save cost. The back has a QR
  code and "12371", which look like batch or serial markings.
- [verified] The top half of the module holds a large QFN chip marked **"Super Radio"**
  (custom marking; the real part number is hidden), a **24.576 MHz** crystal next to it,
  and a row of passives along the top edge.
  - [hypothesis] This is the DAB receiver/demodulator, and the passives are RF front-end
    filtering and matching.
- [verified] There is an SOT-223 part marked **"WS33 530"**.
  - [hypothesis] A 3.3 V LDO stepping USB 5 V down.
- [verified] The bottom half of the module, nearest the USB connector, has a **28-pin
  SSOP/TSSOP-style chip with a blank top** and a **24.000 MHz** crystal.
  - [hypothesis] This is the USB microcontroller that bridges the host and the DAB chip.
- [hypothesis] **Not RTL-SDR.** There is no Realtek RTL2832U plus tuner pair and no
  28.8 MHz crystal. Demodulation happens on the dongle. **Audio decoding does not**: it
  happens on the host (see the APK analysis).
- [verified] The "Super Radio" chip is a QFN with about 12 pins per side (~48 pins).
  - [hypothesis] QFN-48 fits several DAB receiver SoCs; not identifying on its own.
  - [hypothesis] **Probably not an FC8080.** A forum post (Rundfunkforum t=56416, only seen
    as a search snippet because the site sits behind a bot check) says 16c0:05dc dongles use
    the FCI/Silicon Motion **FC8080**, identified from the Android app. But the FC8080
    datasheet lists only a **32-pin 4×4 mm QFN** (8 pins/side) or a 25-ball WLCSP. Our
    chip has about 12 pins/side and is clearly larger. The FC8080 does accept a 24.576 MHz
    clock, so the crystal alone doesn't rule it out. Older units of this USB-ID family may
    have used the FC8080 and newer ones something else.
  - [refuted by libdab.so analysis] ~~Good package match: Silicon Labs/Skyworks Si468x.~~
    The host-side code decodes audio and parses FIC itself and uses paged 8-bit registers,
    not the Si468x command API, so the chip is not a Si468x. Original note kept below:
    The Si4684-A10 product summary lists "QFN 48-pin, 7 x 7 x 0.85 mm", SPI and I2C host
    control, MP2 **and HE-AAC v2 decoded on chip**, "I2S digital audio out with ASRC" and
    an integrated stereo DAC. The Si468x needs firmware loaded at every boot, which
    would fit the slow first enumeration (see USB section). If this is right, audio
    probably reaches the host as **PCM relayed by the MCU from I2S**, not as AAC.
    Unconfirmed; the vendor APK's command bytes (Si468x commands are documented in
    Skyworks AN649) or a logic-analyzer trace would settle it.
- [refuted] ~~24.576 MHz = 512 × 48 kHz suggests on-chip audio decoding.~~ Audio is decoded on
  the host. The crystal is the chip's reference clock (PLL words imply 24.576/2 = 12.288 MHz).
  The FC8080 datasheet lists 24.576 MHz as a supported clock.
- [verified] The module has castellated edge pads down one side plus test vias, i.e. it is a
  generic reusable module. Useful for plan C (tapping MCU↔DAB bus or I2S).
- [verified] The chip markings are deliberately hidden, so identifying parts through a
  datasheet is not an option.

## USB identification (phase 0, done 2026-09-23)
Evidence: `journalctl -k` on plug-in, `lsusb -v -d 16c0:05dc`, `lsusb -t` on the Fedora laptop.

- [verified] **VID:PID `16c0:05dc`**, bcdDevice 1.00. This is the same ID DABdream+ lists.
- [verified] Strings: Manufacturer **"dabusb@126.com"**, Product **"DAB USB Dongle"**,
  Serial is garbage (`㼿` = U+3F3F, i.e. bytes `3F 3F` / "??", presumably never programmed).
- [verified] **Full speed (12 Mbit/s)**, bcdUSB 1.10, bMaxPacketSize0 64, bus powered,
  MaxPower 100 mA.
  - [hypothesis] Full speed rules out V-USB (which is bit-banged low speed), despite the
    V-USB shared ID. The unmarked 28-pin chip is an MCU with a hardware USB peripheral;
    the ID was just borrowed.
- [verified] One configuration, **one interface, class/subclass/protocol ff/ff/ff**
  (vendor specific), three **bulk** endpoints, no isochronous endpoints:
  | EP | Dir | Type | wMaxPacketSize |
  |------|-----|------|----|
  | 0x81 | IN  | Bulk | 8  |
  | 0x02 | OUT | Bulk | 64 |
  | 0x82 | IN  | Bulk | 64 |
  - [verified in code] EP 0x02 OUT = commands, EP 0x82 IN = replies and MSC/FIC data.
    EP 0x81 is never used by the vendor lib, and there are no vendor control requests.
- [verified] **No kernel driver binds** (`Driver=[none]` in `lsusb -t`). It is not USB Audio
  Class and not mass storage. It is a **vendor-specific bulk device**, so a libusb/pyusb
  (Linux) and UsbDeviceConnection.bulkTransfer (Android) approach fits.
- [verified] The first enumeration attempt failed (the kernel assigned device number 5,
  then 6 two seconds later). It succeeded on the retry.
  - [verified] Reproduced on the second plug-in (20:09:38, device 7 → 8) after the board
    was reseated, with the **same ~1.94 s gap** and no error logged. So it is repeatable,
    not a loose plug.
  - [hypothesis] The MCU doesn't answer the first enumeration attempt because it's still
    booting. (The Si468x firmware-upload explanation is out: the chip is not a Si468x, and
    the init path in libdab.so uploads no firmware blob, only register writes.) The kernel retries and it works. Harmless
    on Linux; check that the Dudu3 host retries the same way.
- [verified] Access without root: `/etc/udev/rules.d/60-dab-dongle.rules` matches
  16c0:05dc + manufacturer "dabusb@126.com" with `TAG+="uaccess"`. After a replug the
  device node has an rw ACL for the desktop user and `lsusb -v` opens it (Device Status 0x0000).
- [verified] usbmon is built into this Fedora kernel (`CONFIG_USB_MON=y`); `/dev/usbmon0-4`
  exist. The dongle sits on **bus 3** (port 7), so capture on `usbmon3`. The usbmon
  nodes are root-only.
- [hypothesis] Bandwidth doesn't settle the audio question on its own: full-speed bulk
  gives roughly 1 MB/s, which fits both 48 kHz stereo PCM (192 kB/s) and compressed
  AAC/MP2 (8–24 kB/s).

### Expectations from research
- DABdream+ (by TorstenH82, `com.thf.dabplayer`) says it supports three types of adapter.
  Per its XDA thread, they enumerate on Android as **VID 16c0 / PID 05dc**.
  - 16c0:05dc is the "Van Ooijen Technische Informatica shared ID for use with libusb". The
    ID is generic, so it reveals nothing about the hardware.
- DABdream+ also added support for the **Siano Rhine800** adapter.
- The Chinese vendor app shipped with many of these dongles is **"DAB+"
  (`com.ex.dabplayer.pad`)**. The XDA thread has attached APKs: v3.26, H3.23.8, and a
  "black" re-skin. The module maker is **YHX**, which has hosted APK downloads for normal
  and dual-tuner modules.
- Some other cheap DAB dongles (MVSilicon AP8224 + FCI FC8080) show up as **mass storage**
  and serve live audio as MP3 files. See AlbrechtL/AP8224-DAB-Dongle on GitHub. That is a
  different chip family from ours, but it's the reason mass storage is on the checklist.
- DABdream+ is not compatible with every adapter; some dongles only work with their
  vendor app.

## Vendor APK static analysis (phase 2, started 2026-09-23)
Files in `vendor/` (gitignored), unzipped to `decompiled/<ver>/apk/`:
- `DabPlayer-3.26.apk` sha256 `202edc1b…deae`
- `YHX-DAB+H3.23.8.apk` sha256 `4aa772a1…ad46`

- [verified] **Both APKs are packed with Tencent Legu** (`libshell-super.2019.so`,
  `libshella-4.1.0.31.so`, assets `0OO00l111l1l`, `o0oooOO0ooOo.dat`, `t86`,
  `tosversion`; `classes.dex` is only a 61 KB loader stub). jadx on the APK will not
  show the real Java code. Unpacking it would need a runtime dex dump, but it may not be
  needed (see below).
- [verified] The real work is in **`lib/<abi>/libdab.so`, which is not packed**, and exists
  for arm64-v8a, armeabi, armeabi-v7a **and x86** (NDK r16b, stripped). The x86 build
  means an Android-x86 VM would not need ARM translation.
  - Needs only liblog, libOpenSLES, libstdc++, libm, libc, libdl. **No libusb**, and no Java
    UsbDeviceConnection is needed for the transfers: the `.so` opens `/dev/bus/usb/%03d/%03d`
    itself and uses `ioctl` (strings: `out of memory in usb_host_context`,
    `init the usb device first`), i.e. usbdevfs directly, likely via an embedded copy of
    Android's libusbhost. [verified strings / hypothesis on libusbhost]
  - Only export is `JNI_OnLoad`; methods are registered with RegisterNatives on class
    `com/ex/dabplayer/pad/dab/Dab`. [verified]
  - **Anti-tamper:** JNI_OnLoad-area strings fetch the app's signing certificate and SHA1
    it (`getPackageInfo`, `signatures`, `X.509`, `SHA1`). The library probably refuses
    to work in any other app. [verified strings / hypothesis on behaviour]
- [verified] **Audio is decoded on the host, not on the dongle.** libdab.so bundles
  **FAAD2** (`NeAACDec*`, used for DAB+ HE-AAC) and **mpg123 1.25.13** (DAB MP2), and outputs
  through OpenSL ES. The FIC (the service directory channel) is also parsed on the host
  (`decoder_fic_*`, `fic2_*`, `jni/libdab/fig0.c`). So the dongle delivers **raw FIC +
  MSC sub-channel data**, not PCM. This contradicts the "Si468x decodes audio" hypothesis
  and fits a demodulator-only chip like the FC8080. Package mismatch still open.
- [verified] Strings imply **an SPI bridge protocol**: the MCU exposes a small command
  set and the host drives the DAB chip over SPI through it. Error strings name these
  commands: `echo`, `verion` [sic], `reset`, `power on`, `power off`, `cs` (chip select,
  `SPI-CS 0` / `SPI-CS 1`), `read`, `write`, `get msc size`, `msc read`
  (`spi read fail(%d): %02x`, `msc packet crc dismatch`, `msc packet index dismatch`).
  Byte values decoded with Ghidra: see **`docs/protocol.md`** (full command table, register
  paging, init/tune/FIC/MSC sequences). Summary: 4-byte command header on EP 0x02,
  reply on EP 0x82 with `cmd|0x80`; register write = `21 00 00 02 reg val`, register read =
  `22 00 01 00 reg`; **no vendor control transfers and EP 0x81 unused**. [verified in code]
- [verified in code] The chip driver looks like FCI's "BBM" reference driver (paged 8-bit
  registers, per-channel clock plans, FIC 384 bytes/frame, audio + 4 data sub-channel
  buffers). This backs up the FC80xx-family claim, **but** the driver also has an FM path
  (76–108 MHz), and the package doesn't match the FC8080. So: an FCI-family or FCI-compatible
  chip. [hypothesis]
- [hypothesis] PLL words scale with frequency by about 341.39, i.e. a 12.288 MHz reference
  = the **24.576 MHz crystal ÷ 2**. This fits the teardown.
- [verified] JNI method names (RegisterNatives table, in order):
  `decoder_init/close/feed_data/decode/is_feed_data/get_samplerate/get_channels/
  reset_ensemble_info`, `dab_api_init (I[BILjava/lang/Object;)I`, `dab_api_close/echo/
  version/power_on/get_signal/set_msc_size/tune/set_subid/get_fic_data/sr/set_flag`,
  `decoder_fic_init/deinit/get_usage/parse/reset/get_service_count/find_service_link/
  get_subch_info`, `dab_get_image`, `dab_get_pgm_index`, `dab_get_new_pgm_bitrate`,
  `fic2_init/decode/set_freq/get_pgm_count/get_pgm/get_date_time/get_anous/
  fast_decode_init/fast_detect`, `msc_p_init/get_data/feed`.
- [verified] `assets/dab.bin` (21 MB in 3.26, 32 MB in H3.23.8) is an **MPEG-1 Layer II
  audio file**, 64 kbps stereo. It's probably a demo/test clip, not firmware.
- [verified] H3.23.8 is newer: it adds FM/RDS service-linking strings (`FM %d RDS PI`,
  `DAB %d sid`) and FIG 0/1, 0/2, 0/6, 0/13 parsing. The MCU/USB command strings are
  identical to 3.26.

## Hardware tests
- **2026-09-23, `tools/probe.py`, first contact** (MCU commands only, DAB chip not touched):
  - [verified] echo `00 00 00 00` → `80 44 41 42` (cmd|0x80, "DAB"). Framing and reply-code
    rule from `docs/protocol.md` confirmed.
  - [verified] version `01 00 00 00` → `81 00 00 04`. **Firmware level 4**: MSC data is
    buffered by the MCU (cmds 0x41/0x42/0x40); the 0x43 flag command (level ≥ 5) does not apply.
    HW version bytes read as 0.
  - No usbmon capture taken for this run.
- **2026-09-23, `tools/dtr100.py check`, first chip access** (all bytes replayed from libdab.so):
  - [verified] power on `10 00 00 01` → `90 00 00 00`.
  - [verified] register write `21 00 00 02 03 07` (page 7) → **1-byte ack `a1`**; `21 00 00 02 7d 06`
    → `a1`.
  - [verified] register read `22 00 01 00 7d` → `a2 00 01 00 06`. The value read back matches, so
    the **MCU↔DAB chip SPI path works** and register read/write framing is as in `docs/protocol.md`.
  - No usbmon capture taken for this run.

- **2026-09-23, `tools/dtr100.py scan`, first reception** (log: `captures/2026-09-23-scan-band3.log`).
  Antenna at a window, the Netherlands. Ported vendor system init + band III tune + FIC read
  (tune = conservative port, see `docs/protocol.md`):
  - [verified] **System init completes**: all register writes acked, clock plan 0 applied
    (page 0x0F reg 0x15 bit 0 set), PLL locks.
  - [verified] **Three ensembles received with 100 % FIB CRC pass**:
    **5B** "5B Z-H/Zeeland" (14 services incl. Omroep Zeeland, Radio Rijnmond, Omroep West),
    **9D** "9D" (SO De Bevelanden, Streekomroep WFM, Omroep ZVL),
    **11C** "DAB+" (national commercial: 538, Qmusic, Sky Radio, Radio 10, Veronica, BNR, …).
    FIC = 384 bytes per read = 12 FIBs, CRC-16 CCITT inverted, exactly as in the code.
  - [verified] Many channels report demod sync state 3 but then fail the final lock check
    (page 6 reg 0x12 bit 7 + regs 0x7E/0x7F), including 12C, where the NPO national
    multiplex is expected [hypothesis]. Either those are genuinely too weak indoors, or the
    conservative tune port is missing something from the false-lock loop I skipped
    (read-modify-write latches 0x82|=1, 0x1C|=0x10, 0x44|=0x80). To investigate.
  - [verified] Sync state 3 on its own is not a lock: it also shows up on channels
    without a multiplex.

- **2026-09-24, `tools/dtr100.py play`, first audio** (log: `captures/2026-09-24-play-538.log`,
  output `captures/538.wav`). 11C, service "538 NONSTOP" (the substring match picked it, since fixed),
  subch 3, DAB+ 72 kbit/s EEP 3-A:
  - [verified] Sub-channel start as ported (`FUN_00072160`/`FUN_000712e0`/`FUN_00077ea0`, page 9 reg
    0x3A = 0x80|subch) and MCU **`41 00 <n lo> <n hi>` → `c1`**, n = bitrate × 3 bytes per CIF.
  - [verified] MSC polling: `42 00 00 <seq>` → 6-byte reply `c2 ...`; `40 00 <len>` → `c0 00 <len>` +
    raw sub-channel bytes. The data rate matches the sub-channel bitrate (128 304 B in 15 s ≈ 68 kbit/s
    of 72). The stream is **raw DAB+ sub-channel data**: superframe sync (firecode) found after one
    initial byte search, 118/118 superframes, 0 RS corrections needed, **236/236 AU CRCs OK**.
  - [verified] faad2 (ctypes, ASC with 960-sample frames + SBR sync extension) decoded all AUs with
    0 errors: HE-AAC v1, 32 kHz out (16 kHz core), stereo. The WAV has music-like levels (RMS about 17 %,
    peaks about 65 %, L/R correlation about 0.98). **The laptop plays DAB+.**
  - [verified] The first tune after init sometimes fails the final lock check (reached sync 3, then
    no page 6 reg 0x12 bit 7 within 40 × 10 ms). A retry works. Same symptom as the 12C issue.
  - [verified] ffmpeg 8.1's native AAC decoder can't do **SBR with 960-sample frames** ("not
    implemented"), so faad2 or fdk-aac is required. On Android this means checking that MediaCodec
    handles DAB+ HE-AAC 960, or bundling faad2/fdk. [open]
  - [verified] The "msc packet crc/index dismatch" strings belong to *packet-mode data* (slideshow/EPG)
    parsing in libdab.so, not to audio.

- **2026-09-24, PAD / DLS / slideshow** (offline decode of `captures/raw/*.msc` with
  `tools/decode_raw.py`; captures from `dtr100.py play --raw`, 90 s each):
  - [verified] In DAB+ the PAD is in a data_stream_element at the start of (nearly) every AU. All three
    stations use variable-size X-PAD with contents indicators. X-PAD app types seen: 1 (data group length),
    2/3 (DLS), 12/13 (MOT). `tools/pad.py` decodes them; **every DLS and MOT data group passed CRC**.
  - [verified] DLS: 538 → "Stereo Love - Edward Maya & Vika Jigulina"; Qmusic → "New Religion - BEBE REXHA &
    FAITHLESS", "Bailando - ENRIQUE IGLESIAS & SEAN PAUL"; Omroep Zeeland → station messages +
    "Sienna Spiro - Great Expectation". Format on these stations: "Title - Artist".
  - [verified] **No DL Plus** on these three stations in 90 s: the only DL commands were
    "remove label". Artist/title would have to be split heuristically from "Title - Artist" (or
    use DL+ where a station sends it).
  - [verified] MOT slideshow: 320×240 PNGs, but only the **station logo** in these windows (538, Qmusic,
    Omroep Zeeland), repeated every ~25–40 s. No album art seen. It still gives a free, offline logo
    source for presets.

- **2026-09-24, full tune port** (`FUN_00072400` loops A/B + quality verdict ported literally,
  log `captures/2026-09-24-scan-band3-fulltune.log`):
  - [verified] **12C "NPO" now locks** (NPO Radio 1/2/3FM/Klassiek/5/FunX/SterrenNL/BLEND/P&N, 104–160
    kbit/s), plus **9C** (15 services: KINK, Qmusic 90s & 00s, Veronica 80s, Radio Noordzee, …). Total
    5 ensembles: 5B, 9C, 9D, 11C, 12C, all at 100 % FIB CRC. The earlier failures came from the
    skipped settle loop (it sets latch bits page 6 0x82|=1, 0x1C|=0x10, 0x44|=0x80 each round and
    waits for regs 0x17/0x18/0x7E/0x7F to settle).
  - [verified] The signal metric **u1 = page 4 reg 0x39<<8 | 0x38** (read during the sync poll) separates
    real multiplexes (≈10 000–30 000) from false "sync 3" on empty channels (65–333). Loop A rejects on
    `u1 < threshold` (320..6500 depending on transmission-mode bits). [hypothesis: u1 ~ correlation
    or signal power]
  - [verified] 11B is marginal here (u1 ≈ 7000–10 000; once 11 % FIB CRC, once rejected in loop B).
    Probably a distant multiplex. [hypothesis]
  - [verified] The sync-state register (page 6 0xCF) is 1 on empty channels in most cases, and 3 on
    channels with some (possibly adjacent) energy.

- **2026-09-24, scan speed** (`dtr100.py scan`):
  - [verified] USB is fast: register read ≈ 0.28 ms, write ≈ 0.20 ms. An empty channel costs ≈ 0.18 s
    (PLL settle 0.05 s + the chip's fixed ≈ 127 ms acquisition window: page 6 reg 0xCF goes 0 → 1/3
    after ≈ 45 polls). A locked channel tune takes ≈ 0.6 s. The service list is complete after
    0.4–1.5 s of FIC.
  - [verified] Scan now stops reading FIC once ensemble label + all service labels + all
    sub-channels are known and nothing changed for 0.3 s (max 3 s), and it retries channels that
    reached sync 3 (up to 3 tries if u1 ≥ 1000). Result: **full band III scan ≈ 19 s, 4/4 runs found
    all 5 ensembles / 55 services** (before: ≈ 50 s and 9C/12C sometimes missing).
  - [verified] 9C is marginal here: u1 is bimodal (≈ 7–10 k or ≈ 100 = failed acquisition), and
    q = 34–50 vs 4–17 for the other ensembles. It sits next to the strong 9D. The retry handles it.
  - Design note for the app: keep ensembles found in earlier scans and only drop one after it has
    been missing in several scans (marginal multiplexes come and go, certainly in a car).
- **2026-09-24, service-following FIGs** (60 s raw FIC per ensemble, `dtr100.py fic`,
  `tools/decode_fic.py captures/raw/*.fic`):
  - [verified] FIG 0/13: every service except Amor FM (5B) signals **SLS**. 12C also carries a
    **TPEG** data service (SId E3815201, packet mode, FIG 0/3 + 1/5).
  - [verified] FIG 0/6 + 0/21: NPO Radio 1/2/3FM/Klassiek (12C) and Omroep Zeeland (5B) are **hard
    linked to FM (RDS PI = SId)** with full FM frequency lists (e.g. Omroep Zeeland FM 87.9). The chip
    has an FM path in the vendor driver (`FUN_00074920`), so a **DAB→FM fallback is possible** with
    this dongle. [hypothesis until FM is tried]
  - [verified] 5B lists other DAB ensembles in FIG 0/21 (OE): EIds 805A=5A, 806A=6A, 807C=7C,
    808B=8B, 812B=12B, 817C=7C; FIG 0/24 says RADIONL is on all of them. These are the regional
    multiplexes for **service following by region**. No DAB↔DAB linkage sets in FIG 0/6 here;
    following works via the same SId + FIG 0/24 + FIG 0/21.
  - [verified] ECC = E3 (Netherlands) on all ensembles. EIds follow the pattern 80xx = channel name
    (805B = 5B, 809C = 9C, 812B = 12B); 9D is EId 8648.
  - [verified] 5B carries announcement support/switching (FIG 0/18, 0/19): a traffic/news
    announcement feature is possible later.

- **2026-09-24, station logos online** (DNS + HTTP tests from the laptop):
  - [verified] **RadioDNS** is used in NL. FQDN `<SCIdS>.<SId>.<EId>.<gcc>.dab.radiodns.org` (gcc = SId country
    nibble + ECC, e.g. `0.83c7.8008.8e3`) → CNAME. **Talpa** (538, Sky Radio, Radio 10, Radio Noordzee, …) →
    `talpa_network_radio.radiodns.metadata.radio`, **NPO** → `npo.radiodns.metadata.radio`, both with
    `_radioepg._tcp` SRV. **Omroep Zeeland** → `c528.radioplayer.org` (`_radiospi._tcp`), but its SI.xml
    answers **HTTP 401** (Radioplayer needs an API key). **Qmusic, KINK: no RadioDNS record.**
  - [verified] `http://<host>/radiodns/spi/3.1/SI.xml` (Talpa 13 services, NPO 11) lists each service with its
    DAB bearer (`dab:8e3.8008.83c7.0` = exactly our ECC/EId/SId) and **official logos in 13 sizes**,
    32×32 … 1920×1080 (e.g. `http://metadata.radio/__logos/talpa_network_radio/43260567/128x128.png`,
    a real 128×128 PNG).
  - [verified] **radio-browser.info** (public community API, `/json/stations/search?name=..&countrycode=NL`)
    finds Qmusic/KINK/Omroep Zeeland with logo URLs of mixed quality (favicon.ico, SVG,
    apple-touch-icon). It works as a name-matched fallback.
  - [verified] Stations without RadioDNS: the **station website** works. radio-browser gives the homepage
    (`https://kink.nl/`), and its `<link rel="manifest">` → `/static/site.webmanifest` lists 192×192 and
    **512×512** icons (KINK: a square "K" app icon, ideal for a preset tile); `apple-touch-icon` 180×180 as
    the next fallback.
  - Logo strategy for the app (all cached per ECC+SId, fetched only when online): RadioDNS SPI → station
    website manifest icon / apple-touch-icon (homepage via radio-browser) → radio-browser favicon →
    broadcast SLS logo (repeat heuristic) → generated tile; user can override.
- **2026-09-24, cover art research** (web research, see sources in this entry):
  - [well-sourced] DAB+ has no cover-art type: covers are ordinary MOT slideshow slides a station renders per
    song (ETSI TS 101 499 v3.2.1 §4 gives it as an example). ContentName must change per new slide. Known
    senders: German (ARD "Jetzt läuft", Radio Schlagerparadies), Norwegian (NRK, Nova). **No source
    confirms per-song covers from NPO, Talpa or DPG.**
    https://www.etsi.org/deliver/etsi_ts/101400_101499/101499/03.02.01_60/ts_101499v030201p.pdf
  - [well-sourced] Much in-car cover art comes over **IP**, not DAB: VW/Audi Online Gracenote, DTS AutoStage
    (Hyundai/Kia, Toyota, Volvo, JLR, BMW, Mercedes), Radioplayer (BMW, VW).
    https://www.radioworld.com/columns-and-views/beyond-rds-radiodns-and-dts-autostage
  - Receiver heuristic [inference]: a cover slide is a *new* ContentName/image shortly after a DLS/DL+ title change
    and is not repeated; logos repeat with the same name/hash. Judge per song change (for example, logo-only after 3
    song changes). Fallback: broadcast slide → station logo → optional online lookup (MusicBrainz + Cover Art
    Archive, 1 request/s, cached) when artist/title are known.

- **2026-09-24, cover-art hunt** (4 min raw captures each, `tools/slides_report.py captures/raw/*.msc`, summary in
  `captures/raw/2026-09-24-coverart-hunt.txt`):
  - [verified] **NPO sends per-song slides with the album cover**: a composite 320×240 PNG with the cover
    (about 118 px square) + title + artist + current show and presenter (e.g. "Canned Heat / Jamiroquai" + "MET MORAD").
    When there is no cover, a generic music-note placeholder. A new ContentName per song (66.PNG → 67.PNG,
    36.PNG → 37.PNG), appearing **≈ 20–30 s after the DLS changes**. The slide then repeats every 8–10 s.
    Seen on NPO Radio 2 and NPO 3FM (12C).
  - [verified] Logo only (one image repeating) on Sky Radio (every 28 s), Radio 10 (52 s), 100% NL (30 s, JPEG),
    KINK (67 s), and earlier 538/Qmusic/Omroep Zeeland.
  - [verified] DLS formats differ per broadcaster: NPO "Artist - Title", Talpa/DPG "Title - Artist", 100% NL
    "Onair: Artist - Title", KINK "Artist - Title". Station slogans are interleaved. So splitting artist and
    title from DLS needs per-station rules (no DL+ seen anywhere yet).
  - App implication: show the **whole slide** (it's designed as a 320×240 now-playing card, so don't crop the
    cover). Treat "new ContentName shortly after a DLS change" as now-playing art and a repeating image as the logo.

- **2026-09-25, Android MediaCodec DAB+ decode test** (emulators API 26 and API 29, AOSP x86_64 images;
  `tools/android/MediaCodecTest.java` run via `app_process`, input from `tools/export_aus.py`, compared with
  `tools/android/compare_pcm.py` against the faad2 reference; data in `captures/android/`):
  - [verified] New samples (2026-09-25, 20 s each, `captures/raw/2026-09-25-5B-*.msc`): RADIONL, Tukker FM,
    THE GROOVE and Sunrise FM (5B, 48 kbit/s) are **HE-AAC v2 (SBR + PS)**, ASC `130c56e59d4880`.
    Three DAB+ formats are seen here: HE-AAC v1 32 kHz (core 16 kHz, ASC `141456e5a8`), HE-AAC v1 48 kHz (core
    24 kHz, `131456e598`), HE-AAC v2 48 kHz.
  - [verified] **Android's built-in decoder handles all three** on API 26 and API 29: `OMX.google.aac.decoder`
    (FDK AAC), MIME `audio/mp4a-latm`, `csd-0` = our ASC (960-sample frameLengthFlag + SBR/PS sync
    extensions), one AU per input buffer. 0 errors, correct output rate/channels, 1920 samples/AU. Versus faad2:
    correlation 0.994–0.999, **core band SNR 50–78 dB** (i.e. the same AAC decode; 25.7 dB on the PS stream
    after mono mixdown), SBR band present at 1.0–1.3× faad2's energy (SBR/PS synthesis is not bit-exact
    between decoders, as expected), stereo width identical incl. PS. Decoder delay ≈ 958 samples more than faad2.
  - Consequence for the app: **no need to bundle faad2** (which would also make the app GPL). Use MediaCodec.
    [hypothesis] A real head unit may list a vendor AAC decoder first that lacks 960/SBR support, so the app
    should pick `OMX.google.aac.decoder` / `c2.android.aac.decoder` by name, with a fallback, and it must be
    re-verified on the Dudu3.
  - [verified] Emulator note: on this laptop the emulator only runs with `-gpu host` (swiftshader/guest/off
    segfault at start-up). AVDs `dab-api26`, `dab-api29` in `~/.android/avd`, SDK in `~/.local/opt/android-sdk`.

- **2026-09-25, USB DAB dongle market** (web research; XDA read via Wayback/reader; details and all URLs are in
  `docs/dongles.md`):
  - [src] Our family is known as **"wRadio C100"** (16c0:05dc), supported by DABdream+, DAB-Z and the vendor app.
    The AP8224 MP3 stick is also branded "WRADIO" and uses an FCI FC8080 → C100 is likely an FC8080-class part
    [inf], consistent with our driver analysis.
  - [src] DABdream+ supports: C100, **C100 Dual Antenna** (same 16c0:05dc; YHX gave the DABdream dev a
    library), **Siano SMS2270 "Rio"** (187f:0600), **Siano SMS2160 "Rhine800"** (187f:0800), **Si4684-A10**
    dongles (Eonon A503 / XFA-DAB220, 1a86:5537). Not supported by it: RTL-SDR, dual tuners, 0483:5740.
  - Other families: AP8224 "MP3DAB" (1234:1111, USB mass storage, MP3 out; AlbrechtL docs), Dension DAB+U
    (mass storage), Monkeyboard/Keystone (04d8:000a CDC serial, analog audio only → not useful), RTL-SDR (full
    software demod; AbracaDABra (MIT) shows it works on Android 11+), C100 Dual Tuner (closed protocol).

- **2026-09-25, station switch without re-init** (`tools/switch_test.py`, log `captures/2026-09-25-switch-538-qmusic.log`):
  - [verified] The ported **proper stop** works on fw level 4: page 9 reg 0x3A = subch (off), page 7 reg 0x62 = 0x70 → 0x7E,
    one flush round (`42` → `41 00 80 04` / `40 00 80 04` → `42` empty), `41 00 00 00`. It takes **5 ms**.
  - [verified] 538 → Qmusic (11C) with no re-init and no retune: **first decoded audio 0.90 s after the stop began**;
    154/154 and 152/152 AUs OK, 0 faad errors, both WAVs contain real audio.
  - [inference] Floor for a DAB+ switch ≈ 0.6–0.9 s: the chip's time de-interleaver (≈ 384 ms, 16 CIFs) + waiting for
    the next superframe start (≤ 120 ms) + collecting one superframe (120 ms) + the first decoder frame. So the
    architecture target is **< 1 s within an ensemble** (≈ 1.5 s across ensembles), not < 0.5 s.

- **2026-09-25, FM via the dongle** (`tools/fm_test.py`, logs `captures/2026-09-25-fm-*.log`):
  - [verified in code] The vendor library contains a **complete FM API that neither app version (3.26, H3.23.8) ever calls**
    (dead code, likely from the chip vendor's reference driver): `FUN_00074aa0` init (FM clock plan, ≈ 70 register writes, FM
    RF table of 78 pairs), `FUN_00074920` tune (76–108 MHz, 50 kHz steps, FM PLL table of 642 words, ratio ≈ 686 = 2× band III),
    `FUN_00074100`/`745e0`/`74700` seek (frequency-offset S-curve via page 6 regs 0xFC–0xFE + RSSI), `FUN_00074470` RSSI (page F regs
    0/2/4), `FUN_00074510` status (page 6 0xEF stereo, 0xDB, 0xEE quality), `FUN_00077f10` audio start (same audio buffer as DAB).
    The "FM %d freq / RDS PI" strings in H3.23.8 belong to the FIG 0/21 parser, not to FM reception.
  - [verified] Ported literally and run: init OK, the FM PLL locks on every frequency, the seek detector finds weak S-curves at **104.4, 96.0 and
    88.0 MHz** (104.4/88.0 = NPO Radio 1/2 FM frequencies from FIG 0/21), but all are below the vendor threshold (±800).
  - [verified] In FM mode the RF gain registers sit at maximum on every frequency (page F [0x78, 0x18, 0x7F]; with DAB on 11C: [0x12, 0x58, 0x16]),
    so hardly any FM energy reaches the chip. **No FM audio**: the chip's audio buffer stays empty (page 9 reg 0x33 = 0) and the MCU returns 0 bytes.
  - [hypothesis] The dongle's RF input is band-filtered for DAB band III (the passives at the top edge of the module), and the antenna
    is a band III window antenna, so this unit is **not usable for FM**. It's unknown whether the MCU firmware would even forward FM audio.
    FM would need a dongle whose front end passes 88–108 MHz; the app only offers FM when the connected dongle supports it (capability flag).
  - [verified] Afterwards DAB still works; power off (`10 00 00 00` → `90`) + on + init is a clean reset. DAB quality was worse than usual
    (q ≈ 110–140 vs 4–17, a few FIB CRC errors) at unchanged signal levels (u1 15–23 k) on all ensembles, before and after the power
    cycle. That points to local interference/antenna. MCU status bytes 4..5 = `d0 07` (2000) also appear in normal DAB logs (idle value),
    so it isn't FM leftover state.

## Open questions
1. ~~What is the VID:PID of this unit?~~ Answered: 16c0:05dc, vendor-specific bulk.
2. Does DABdream+ work with this unit?
3. ~~How does audio reach the host?~~ As raw DAB MSC sub-channel data over bulk EP 0x82.
   The host decodes it (FAAD2 / mpg123 in the vendor lib). [verified in code, not yet on hardware]
4. ~~Java or native?~~ Native (`libdab.so`, unpacked, x86 build available). Decoded; see
   `docs/protocol.md`. Remaining: the Java call order (Java is Legu-packed) and the MSC packet
   framing.
5. Which DAB chip is hiding under "Super Radio"? This only matters for plan C.

## Sources
- DABdream+ XDA thread: https://xdaforums.com/t/dabdream-dab-dab-player-for-usb-adapters.4638309/
- Chinese DAB+ app XDA thread: https://xdaforums.com/t/dab-app-com-ex-dabplayer-pad-for-dab-usb-adapters.4626191/
- DABdream+ on Google Play: https://play.google.com/store/apps/details?id=com.thf.dabplayer
- AP8224 dongle notes (a different family): https://github.com/AlbrechtL/AP8224-DAB-Dongle
- Rundfunkforum "DAB+ USB Receiver Dongle" (16c0:05dc, FC8080 claim; Cloudflare-blocked
  for automated fetch, read it in a browser): https://www.rundfunkforum.de/viewtopic.php?t=56416&start=15
- Rundfunkforum Si4684/Si4688 thread: https://www.rundfunkforum.de/viewtopic.php?t=56042
- FC8080 manual (package 32-QFN 4×4 / 25-WLCSP, clocks incl. 24.576 MHz):
  https://www.manualshelf.com/manual/silicon-motion/fc8080/manual-english.html
- Si4684-A10 product summary (QFN-48 7×7, I2S, HE-AAC on chip):
  https://www.skyworksinc.com/-/media/SkyWorks/SL/documents/public/product-summaries/Si4684-A10_PS.pdf
- Si468x programming guide AN649 (command set, for matching captured bytes):
  https://www.skyworksinc.com/-/media/Skyworks/SL/documents/public/application-notes/an649.pdf
- Search 2026-09-23: "dabusb@126.com" gives **no hits**; no public GitHub RE of
  `com.ex.dabplayer.pad` or this USB protocol found. XDA threads return 403 to automated fetch.

## Photo index (`docs/teardown/`)
- `01-module-overview-super-radio.jpeg` — full module with the Super Radio chip
- `02-usb-mcu-and-carrier.jpeg` — unmarked 28-pin MCU, 24 MHz crystal, carrier, USB
- `03-closeup-crystals-ws33.jpeg` — both crystals and the WS33 regulator
- `04-closeup-24576mhz-crystal.jpeg` — 24.576 MHz crystal near the DAB chip
- `05-closeup-both-crystals.jpeg` — 24.576 and 24.000 MHz crystals, readable
- `06-carrier-back-qr.jpeg` — back of the carrier, QR code and "12371"
