# Findings log

Every claim is tagged **[verified]** (seen directly) or **[hypothesis]** (inferred). Update
this file as work progresses.

## Status (2026-10-15)
- **Phase 5, Android app: 1.0.0 released** (2026-10-15) after the first run on the Dudu3. Before that it was verified
  with the real DTR-100 in the API 34 emulator (USB passthrough). What's in it: README "Features"; how it's built: `docs/architecture.md`.
- **Since the 09-27/28 log below** [verified on hw unless noted]:
  - Bug + security review in four parts (engine survives errors and detach; everything from the air or the internet is
    bounded; storage survives power cuts; UI robustness), RAON signal-bar thresholds.
  - Traffic announcements (setting, off by default; the TA badge lights on 538; a real switch untested), calls silence
    the radio and navigation ducks it [verified: call via `adb emu gsm call`; ducking untested].
  - Performance for budget head units (section below): one cached background layer, baseline profile.
  - Driver interface (`RawTuner` / `ModuleTuner`); the DTR-100 replay test gives byte-identical traffic through it, and
    a hardware regression after each driver change passed (scan 5 ensembles / 55 stations in 21.5 s, 538 in 0.6–0.8 s).
  - Three more dongle families from DABdream+'s traffic, **untested** (no hardware): C100 Dual Antenna, Si4684 module,
    Siano (docs/protocol-*.md).
  - Weak reception [verified, 12C indoors, CER ~900–1070, up to 51 RS failures/s]: every burst of bad AUs emptied the
    audio buffer and cost a 400 ms re-buffer ("skipping"). Lost AUs now become silence of their length.
  - Back never leaves the app (the system bar slides in over the mini player after a swipe from the bottom edge).
  - Start with the car (setting, off by default) [verified, Android 10 + 14 emulators]: a boot listener can open the
    app only with "Display over other apps" (BAL_ALLOW_SAW_PERMISSION; blocked without). **USB permission doesn't
    survive a cold boot**, and a dongle present before boot completes isn't announced, so the first open after a
    cold boot shows the USB prompt [verified, API 34 emulator].
  - Code review of the drivers/engine work and its fixes (2026-10-06); dongle dropouts while playing resume in the
    background [verified, 3 real unplug/replug rounds: 538 again 1.0–1.05 s after the replug, home screen in front];
    the diagnostics log file.
  - Engine split (2026-10-06): `DabEngine` is a plain-Kotlin module (`:engine`) behind ports, `:data` is plain Kotlin,
    `DriverRegistry` picks the driver. JVM tests run the engine against a scripted tuner and an in-repo test broadcast
    (playback, following, announcements, reconnecting, dropout resume, scan) [verified: 10 tests, stable on one CPU
    core]. Hardware regression after each of the steps [verified, DTR-100: scan 7 ensembles / 74 stations, 538 in
    0.6–0.8 s, 11C → 12C, pause/play, an unplug with resume in 0.97 s].
  - App icon and README banner; 0.9.0 / 0.9.1 as GitHub pre-releases, signed with the release key (2026-10-06).
  - 1.1.0 (2026-10-18, owner requests after the first drives): logo control in Settings (Retry missing, Refresh all,
    what was found) [verified, API 34 emulator online: retry of 20 → 19 not found + 1 no answer; refresh of 67 kept
    all 47 logos]; an unanswered lookup is no longer a 3-day "not found" [verified: unit tests, and live: "YOURSAFE: no
    answer" became "no usable logo" on the next pass]; the Now playing tab is a play triangle (both themes checked).
  - 1.3.1 (2026-10-20, GitHub issue #2: the system bar covered the title and clock): the bars were hidden only once,
    at start; Android shows them again after another app or a system screen and doesn't hide them on return
    [verified: reproduced on the Android 10 emulator by leaving via Home and coming back]. Now hidden again whenever
    the window gets focus back, plus Settings → Full screen to keep the bars, with the app fitted next to them
    [verified on emulators Android 8.0, 10 and 14: start, Home and back, the notification shade, both settings, and
    switching while the app is open; the Full screen switch on the Dudu3 in the car: owner, 2026-10-21]. The Android 8.0 emulator (a phone) needs ~60 s to open any app, also the system
    Settings; not the app. Seen there too: in a phone's landscape height (387 dp) the rail shows only 3 of its 5 tabs
    [verified, open item: small screens].
  - 1.3.0 (2026-10-20): Settings → Diagnostics log (Save / Share) and a "Dongle report" issue form, so owners of the
    untested dongles can report without adb [verified, API 34 emulator: saved to Downloads with header, USB list and
    log; the share sheet opens with the file; the USB list's format is unit-tested, a real device in it is not yet].
  - 1.2.1 (2026-10-19, GitHub issue #1, a CAMECHO "C100 based" stick not detected on Android 16 and 8.1 while other
    apps use it): DABplay+ was the only app requiring the manufacturer name "dabusb@126.com" [verified: DABdream+ 26.9.1,
    com.ex.dabplayer 1.3 (Java and libdab.so) and the native libraries of 3.26 / YHX H3.23.8 match on 16c0:05dc alone].
    The name check is replaced by the interface's shape (bulk 0x02 + 0x82; V-USB hobby devices on the same id are low
    speed and can't have bulk endpoints); a device with the id that doesn't fit is reported on screen and not retried; the
    log names every dongle's id, manufacturer and product. The DTR-100 still works with it [verified, owner, Dudu3, 1.2.1].
    The reporter's CAMECHO stick works with 1.2.1, so the name check was the cause [verified, reporter on issue #1,
    2026-10-20; issue closed].
  - 1.2.0 (2026-10-19, owner idea): background tints (Dusk, Ocean, Ember, Aurora; day and night versions) and an
    optional Pure black at night [verified, API 34 emulator: all 9 variants on Favorites and Now playing, Settings in
    both layouts; the unit tests check that only the background colours change and Dusk is unchanged].
  - **First run on the Dudu3** (2026-10-15, owner, a 0.9.x build, DTR-100 behind the head unit): over an hour of
    driving with no bugs seen, quick and responsive [verified, owner report]. A tunnel did what it should: "No signal"
    inside, audio back quickly after it [verified, owner report]. The head unit's own media widget in its system bar
    shows station, DLS and artwork from the media session, and slides show on air [verified, owner photo]. The
    steering-wheel buttons work through the media session [verified, owner report]. Later (2026-10-18, owner report):
    navigation prompts duck the radio, sleep and cold boot behave as intended, logos download once online (a scan
    elsewhere: about 160 stations, 92 with a logo; many local stations have none online) [verified, owner report]. Logos weren't
    downloaded yet (the initials tile showed); the head unit hadn't been online.
- **Next**: (1) still to check on the Dudu3: a phone call, Start with the car, and the diagnostics log (`rx30` lines)
  to calibrate the signal bars; (2) the Google Play listing (owner, later). Done 2026-10-18: the repo is public, README
  screenshots carry the play-triangle tab (strip from the emulator; real retakes with the dongle when convenient),
  GitHub social preview uploaded.

## Work log: phase 5, 2026-09-27 … 09-28
- **Android app, phase 5** (`android/`, see `android/README.md`). Done and verified on the API 34 head-unit emulator
  (1280×720) with the real dongle over USB passthrough:
  - dab-core, the dtr100 driver and the playback core (scan 22 s / 55 stations, MediaCodec audio, DLS, slides,
    Media3 session + media keys, auto-resume).
  - **Real UI** from the Claude Design handoff: Home (favourites grid 1a / list tiles 1b, "+ Add" tiles), Stations
    (letter groups with sticky headers, swipe-right to favourite, A–Z scrubber), Now playing (4:3 artwork),
    Recent (Clear + Undo), Settings (Day/Auto/Night, Bar/Panel, driver side Left/Right, switches, Edit favourites).
    Both layouts, both driver sides and both themes checked with screenshots.
  - **Smoothness** [verified, emulator]: R8 release build 2.1 MB (debug 19.5 MB); flinging the station list:
    4.4 % janky frames and 1 slow UI-thread frame in 412 (debug build: 28 % / 28). Animations are read in the
    draw layer (no per-frame recomposition), slides decode off the UI thread, tabs keep their scroll position.
  - [verified] After the app is killed mid-stream (reinstall, force-stop, crash) the dongle still holds a stale reply
    on EP 0x82 (seen: `c2 00 00 00 72 01`), so the next `version` read fails. Fix: `Dtr100.drainStale()` reads (never
    writes) EP 0x82 until empty before init; recovery then works on every restart.
  - [verified] Audio stutter/skips right after a cold-start auto-resume (owner, 2026-09-27; fine once warm). Cause: USB
    read, superframe decode, AAC decode and a blocking AudioTrack write all ran on one thread with ~0.1–0.3 s of buffer
    and no pre-roll. Fix: AAC decode + AudioTrack on their own URGENT_AUDIO thread behind a non-blocking queue, a 1 s
    track buffer with a 400 ms pre-roll (DAB+ PCM arrives in 120 ms superframe bursts), re-prime after an underrun, USB
    thread at AUDIO priority. After the fix: cold starts and a UI stress run (tab switching + list flings) log no underruns
    and no MSC read slower than 150 ms (one underrun was seen once under heavy host load in the emulator).
  - [verified] NPO slides are 320×240 (4:3) like the artwork frame and are drawn with Fit, so the whole slide shows; a
    photo cut off at the top is how NPO composed that card.
  - **Station logos** (2026-09-27) [verified, emulator online]: first pass found 41 of the 55 received services in
    ≈ 18 s (24 RadioDNS SPI, 3 website manifest, 6 apple-touch-icon, 8 radio-browser favicon). The 14 misses are
    small local stations with no usable image anywhere (not in radio-browser, or only a banner under 96 px);
    they keep the initials tile and are retried after 3 days. Found on the way: Radioplayer's SPI SRV points to
    port 443 (plain HTTP there hangs, so 443 = HTTPS); radio-browser names often add "Radio" ("Omroep Zeeland
    Radio"), so generic words are ignored when there is no exact match. The broadcast SLS logo step is left out
    for now: a logo can't be told apart from a news card reliably.
  - **Reception loss** (2026-09-27, antenna pulled 3×, emulator + dongle):
    - [verified] Bytes 4..5 of the `0x42` status reply are the signal value: the vendor's `dab_api_get_signal` on
      fw ≥ 2 returns exactly them (`FUN_000773c0` → `DAT_002025e8`, set in `FUN_00077990`). Scale: 0 = perfect,
      **2000 = nothing** (jumps there the moment the antenna is pulled). Seen while re-locking: 950 → 670 → 340 → 170
      → 0. Bars thresholds (100/300/700/2000) are [hypothesis]: calibrate in the car.
    - [verified] Antenna out: audio data stops within ~2 s (0 bytes from the MCU), bad AUs are dropped by the AU CRC,
      so the output goes silent (no noise). Antenna back: **the chip re-locks by itself without a retune** (~2 s to
      audio, one clean re-buffer), even after 2 minutes out.
    - App: `ReceptionMonitor` (dab-core, with hysteresis) → GOOD/WEAK/LOST, live bars, "No signal · waiting for
      reception" (amber), MediaSession `STATE_BUFFERING`; PAD half-messages are dropped after a gap (the DLS toggle has
      only 2 values: stale segments could be stitched into a new text; a blank title was seen once after a gap).
  - **FIC during playback** (2026-09-27, owner-approved test):
    - [verified in decompiled code] the vendor lib wraps its FIC read (`FUN_00079c20`, the same register sequence as
      our `ficRead`) and MSC read in one process-wide mutex (`DAT_00101e48`): built to interleave them.
    - [verified on hw] polling the FIC every 50 ms while streaming: audio stays clean (126/0 AUs per 5 s, 0 resyncs),
      all FIBs valid (1320/0 in 90 s), but only ~15 % of FIC frames are caught (the "FIC ready" bit is mostly already
      cleared, presumably by the MCU's own MSC handling). Enough for slow FIGs: on 5B the FIG 0/24 list of
      RADIONL's other ensembles (5A, 6A, 7C, 8B, 12B, 7C) plus their FIG 0/21 DAB frequencies arrived after ~85 s.
  - **Service following** [verified, emulator]: alternatives learned live are stored per station (stations.json);
    after 6 s LOST the engine tries them plus scan-known ensembles with the same service (tune, brief FIC read to
    confirm the service), about 0.2 s per unreceivable candidate; none → back to the own frequency (retuned every
    5 s until it locks) and another round every 30 s. Antenna test on RADIONL: 3 rounds, then back on 5B by itself.
    A real switch to another ensemble is untested here (no second ensemble carries the same service locally).
  - **DAB (MP2) support** (2026-09-28): own Kotlin MPEG-1/2 Layer II decoder (dab-core `mp2/`): all DAB variants
    (48 kHz tables B.2a/B.2c, 24 kHz half rate, mono/stereo/joint, header CRC). [verified] Golden tests against ffmpeg
    on synthetic vectors (`tools/gen_mp2_vectors.sh`): 7 variants, max 1 LSB difference, 88–90 dB SNR; CRC errors
    detected. The synthesis window's 257 coefficients come from a system ffmpeg build (ISO sign convention; the vendor
    lib's mpg123 copy has the same magnitudes). DAB framing (sync + lock on frame length) and PAD extraction
    (`[X-PAD reversed][ScF-CRC][F-PAD]`) tested on the vectors; the ScF-CRC length rule is [hypothesis].
    - [verified in decompiled code] The dongle side needs nothing new: the vendor starts every audio sub-channel with
      type 2 (`FUN_00072160(…, 2, n)`; type 4 = packet data; type 1, which would set bit 6 of reg 0x3A, is never
      used), so MP2 plays through the same `startAudio` as DAB+.
    - [verified] FIG 0/1 short form (UEP, typical for MP2) had no bitrate, so scans dropped MP2 stations. Added EN 300
      401 Table 8, checked entry by entry against the vendor lib's copy (u16 records size/level/bitrate at 0xd7180).
    - Untested on air: no DAB (MP2) services in NL. Needs a listener in the UK or elsewhere.
  - **Independent confirmation of the protocol** (2026-09-28): github.com/flohoff/wradio-c100 (C++, LGPL-2.1, 2026) is
    derived from the IRT's OMRI-USB (github.com/hradio/omri-usb, 2020), the "default DAB layer" of many Android head
    units. Its `RaonTunerInput` drives this dongle with byte-identical commands (SPI write `21 00 00 02 reg val`, read
    `22 00 01 00 reg`, power `10 00 00 01`, echo, version), the same clock-plan values (page 6 0x3C..0x43) and demod
    restart (`0x10` = 0x48, 0xC9). [verified by reading their source] Consequences:
    - The DAB chip is a **RAONTECH** part (page register 0x03 is RAON's map select), not FCI as guessed earlier.
    - The `0x42` status bytes 4..5 are RAON's **CER** (channel error rate): `cer ≤ 4000 counts → 0`, else
      `err·1000/period·10`, `> 1200 → 2000`; no lock → 2000. Exactly the 0…2000 scale we measured.
    - RAON's antenna-level table (CER thresholds 810/700/490/400/250/180/0 → levels 0..6, moving one step per update)
      gives grounded signal-bar thresholds: 4 bars = CER < 180, 3 < 400, 2 < 700, 1 < 2000 (adopted 2026-09-28, one bar
      per update like RAON's meter; lost signal drops to 0 at once).
    - Page 9 reg 0x33 bits: 0 FIC ready, 1..3 MSC0 int/underflow/overflow, 4..6 MSC1 int/underflow/overflow, 7 OFDM lock.
    - OMRI-USB wires only this RAON stick; its device filter also lists 1d19:110d, 0fd9:004c, 0416:b003 without code
      (a "Found Siano device" log line hints Siano support was removed). No help for other dongle families.
    - Their code is LGPL: read for knowledge only, nothing copied into DABplay+ (MIT).

## Performance on head-unit hardware (2026-09-29)
The Dudu3 has a Unisoc UIS8581 (owner's spec sheet): 8× Cortex-A55 at 1.2–1.6 GHz, PowerVR GE8322, 4 GB. One A55 core
is roughly 10–12× slower than one laptop core (i7-1355U); cheaper head units are similar (A53/A55, weaker GPUs).
- **Decoding [verified, laptop JVM, `BENCH=1 ./gradlew :dab-core:test --tests '*DecodeBenchmark*'`]:** DAB+ superframes +
  RS + PAD cost 0.2–0.5 ms CPU per second of audio (0.05 % of a core), FIC 0.06 ms, the Kotlin MP2 decoder 1.3–3.4 ms
  (0.34 %). ×12 for an A55: under 5 % of one of its eight cores in the worst case. Not a concern.
- **Memory [verified, emulator]:** 36 MB PSS while running. Not a concern.
- **UI thread [verified, emulator pinned to 2 host threads]:** p50 1–2 ms, p90 2–4 ms per frame during tab switches and
  scrolling (release build). ×10–12 for an A55 puts tab switches at the edge of the 16.7 ms budget: a baseline profile
  is essential (below) and tab switches may still drop a frame or two. **To measure on the Dudu3.**
- **GPU [verified, overdraw overlay]:** before 2026-09-29 every pixel was painted 4+ times per frame (window background
  + page colour + three full-screen radial glows). Now the page is one cached layer and the window background is off:
  background 1×, tiles/bars 2×, content 3×. The emulator's GPU numbers say nothing (host GPU; its software GPU mode
  segfaults in emulator 37.1.11), so GPU time is **to measure on the Dudu3**.
- **Baseline profile [verified]:** `:baselineprofile` generates it on the emulator (`./gradlew :app:generateReleaseBaselineProfile`,
  ~17.8k rules, 752 of our methods); it ships in the APK. Against an uncompiled install it halves the UI thread's p90
  (4.1 → 1.8 ms) and the median frame makes the deadline. Play applies it at install; a sideloaded APK needs
  `adb shell cmd package compile -m speed-profile -f io.github.jvspier.dabplay` once (a head unit that is always in use
  rarely gets ART's idle-time compile).
- Continuous animations while playing (waveform, DLS marquee) keep the GPU drawing 60 frames a second; fine on the
  numbers above, but watch the unit's temperature/throttling behind the dashboard on the first long drive.

## Work log: 2026-09-24 (end of day)
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
  - **Resolved 2026-09-28:** the chip is a **RAONTECH** part (OMRI-USB's RAON driver uses identical commands; see the
    work log). The FC8080 notes below are kept for the record.
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
  chip. [hypothesis] **Superseded 2026-09-28: a RAONTECH part** (work log).
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

- **2026-09-25, after the FM test: replug** (scan 5B/9C/9D/11C/12C):
  - [verified] After a USB replug, DAB is back to normal (q 0–6, 100 % FIB CRC on all five ensembles; 9C even at q=4 vs 34–50
    before). So the degradation **was dongle state left over from FM mode**, not interference. The FM init writes registers the
    DAB init never writes (e.g. page 7 reg 0xC8, page 4 reg 0x69, page 6 regs 0xD2–0xD7/0xE5–0xF9), and neither the vendor
    power-off (`10 00 00 00`) nor a full DAB re-init resets them. Only a power cycle (replug) does.
  - [verified] The first tune after the replug locked (rc 0) but returned **0 % valid FIBs**; the next tune was fine. The PoC
    scan now retunes when it locks without a single valid FIB. App requirement: treat "lock without valid FIC" as a failed
    tune and retry.

- **2026-09-27, first light on Android** (API 34 emulator, dongle via xHCI USB passthrough, temporary radio-test screen):
  - [verified] The API 26/29 emulator kernels (4.14) have only EHCI (no OHCI/UHCI/xHCI), so QEMU refuses the full-speed
    dongle ("speed mismatch"). The API 34 image (kernel 6.1, xHCI) sees it. Emulator images also lack the
    `android.hardware.usb.host` feature; adding the standard permissions XML enables UsbManager. Real head units
    declare it (the vendor app, DABdream+ and DAB-Z use the same USB host API).
  - [verified] Permission dialog "Allow DABplay+ to access DAB USB Dongle?" with "Always open DABplay+ when connected"
    (the device_filter works). Kotlin Dtr100 over UsbDeviceConnection: fw level 4, init 0.4 s, 11C locked in 0.6 s
    (u1 18133, q 4), 14 stations after 0.9 s of FIC.
  - [verified] **Audio plays**: 538 (HE-AAC 32 kHz, `c2.android.aac.decoder`), 234/234 AUs OK; switch to Qmusic without
    re-init works, 388/388 AUs OK; live DLS "All Star - SMASH MOUTH" is shown as "Smash Mouth – All Star".
  - Still to verify on the real Dudu3 (Android 10): USB host + permission flow, the decoder choice (OMX vs c2 naming), audio focus.

- **2026-09-27, playback core on Android** (API 34 emulator + dongle passthrough; DabEngine, StationStore, RadioService/DabPlayer):
  - [verified] First start (empty store) scans automatically: **full band III scan on Android in 22 s: 5 ensembles, 55 stations**
    (same as the PoC). Results, favourites (by ECC+SId, e.g. E3.83C7) and last-played are persisted in `files/stations.json`.
  - [verified] Media3 MediaSession: DABplay+ is the system's "media button session", state PLAYING/PAUSED. KEYCODE_MEDIA_NEXT/
    PREVIOUS step through the favourites (538 → KINK → NPO 3FM, retuning 9C/12C in ≈ 0.75–0.85 s); PLAY_PAUSE pauses/resumes.
  - [verified] Cold restart resumes the last station without a scan: dongle ready → audio 1.8 s (tune 0.84 s + first audio 0.98 s);
    3.3 s including the app launch.

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
