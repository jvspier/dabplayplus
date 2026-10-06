# DAB+ car radio app — reverse engineering + Android app

## Goal
A car-friendly Android DAB+ radio app for a **Dudu3 Android head unit**, driving a cheap
Chinese **USB DAB+ dongle** directly. It should do what DABdream+ does, with a UI you can
safely use while driving. This is a personal hobby / interoperability project.

Why: the owner used DABdream+ in a previous car. It worked fine, but the UI is not
car-friendly: lots of swiping, small buttons, small text. DAB-Z works but feels dated.

## Current phase: 5 — Android app (v1 features complete; Dudu3 run next)
The PoC (phase 4) is complete. The Android project lives in `android/` (see `android/README.md`); design and
architecture are decided and approved. Read these before starting any task:
- `docs/architecture.md` (modules, driver interfaces, playback/USB/boot) and `docs/design-notes.md` (UI decisions).
- `docs/findings.md`: hardware findings and test log; its **Status** section says where work stopped and what's next.
- `docs/protocol.md`: the DTR-100's USB/chip protocol; `docs/protocol-*.md` for the other dongles. `tools/`: the Python
  reference implementation.

Status in short (2026-10-06): everything in the README's feature list works with the real DTR-100 in the API 34
emulator (USB passthrough). That covers DAB+ and MP2, logos, reception handling and service following, traffic
announcements, calls and navigation, Start with the car, and performance tuning for the Dudu3's budget SoC. Three more
dongle families are implemented but untested (no hardware). The engine is a plain-Kotlin module with JVM tests against a
scripted tuner; CI runs all unit tests and the release build. Next: the Dudu3 run (findings.md Status).

## Hardware summary
- **Dongle:** DollaTek "DAB+ Receiver for Android Navigation", model **DTR-100**
  (Amazon.nl ASIN B09H6N3CBX, model ELA20586), USB-A with an SMA antenna jack.
- **Teardown (photos in `docs/teardown/`):** the DAB chip is custom-marked **"Super Radio"**
  to hide its identity. The USB microcontroller is a 28-pin chip with no markings and a
  24.000 MHz crystal. The board is **not RTL-SDR**: demodulation happens on the dongle,
  audio decoding on the host.
- **USB (verified):** `16c0:05dc` (a shared ID; match on manufacturer "dabusb@126.com"),
  full speed, vendor-specific, bulk EP 0x02 OUT / 0x82 IN. The MCU is a USB-to-SPI bridge.
  The DAB chip (a RAONTECH part, confirmed via OMRI-USB; first guessed FCI) sends raw FIC/MSC data. **All
  DAB decoding (FIC parsing, DAB+ superframes, AAC) happens on the host.** Firmware level 4.
- **Access:** the udev rule `/etc/udev/rules.d/60-dab-dongle.rules` (uaccess) is installed,
  so no root is needed.
- **Target:** Dudu3 Android head unit (not installed yet): Unisoc **UIS8581** (8× Cortex-A55, no big cores; PowerVR
  GE8322 GPU), 4 GB RAM / 64 GB eMMC, 1280×720, Android 10 (API 29). A budget-phone class SoC: see findings.md "Performance".

## Roadmap
Progress: 0 done. 1 not done (not needed). 2 done: the protocol lives in the unpacked native
`libdab.so`; the Java side is Tencent Legu-packed and wasn't needed. 3 not needed. 4 done. 5 in progress (v1 features
complete, Dudu3 run next).

0. **Identify.** Run `lsusb`, `lsusb -v -d VID:PID` and `dmesg -w` while plugging in. Is it
   USB Audio Class, mass storage, or vendor-specific bulk/control? Record the answer in
   `docs/findings.md`.
1. **Compatibility check.** Does DABdream+ work with this unit (phone via OTG)? If yes,
   it belongs to the known family.
2. **Static analysis.** Decompile the Chinese vendor "DAB+" APK (`com.ex.dabplayer.pad`)
   with jadx. Trace `UsbManager` / `UsbDeviceConnection` calls (`controlTransfer`,
   `bulkTransfer`) and list any native `.so` libraries plus their JNI entry points.
3. **Dynamic analysis.** Run an Android x86 VM (BlissOS or Android-x86, which may need ARM
   translation for native libraries) in QEMU/virt-manager with the dongle passed through.
   Capture with `usbmon` + Wireshark/tshark on the host. Make one labeled capture per
   action (idle, scan, tune 8A, tune 11C, volume, station switch), then diff them.
4. **Linux proof of concept.** Write a Python + pyusb CLI that can init, scan, tune and play
   audio. Proving "I can hear a station on the laptop" comes before any Android work.
5. **Android app.** Kotlin + Jetpack Compose, USB host API, foreground service plus a
   MediaSession. Deploy to the Dudu3 over ADB on Wi-Fi and debug with logcat.
   **Before writing any Android code, hold a design session with the owner** (architecture, UI,
   feature scope). They want to get the design right first time. Everything in the
   Python PoC (`tools/`) is the reference implementation to port.

Fallbacks:
- **Plan B:** switch to an RTL-SDR dongle and use the welle.io backend (GPL, C++) through
  the NDK. This means software demodulation on the head unit, and DAB+ needs an AAC
  decoder that handles 960-sample frames.
- **Plan C:** tap the bus between the MCU and the DAB chip with a cheap logic analyzer
  (sigrok/PulseView) and match its command bytes against known DAB chip command sets.

## Working rules
- **Never send guessed or unknown commands to the dongle.** Only replay transfers seen in
  captures or found in decompiled code. A random vendor request could put the MCU into
  bootloader mode or brick it. Ask before sending anything new.
- The owner does the physical steps: plugging in, sudo, VM setup, pressing buttons during
  captures. Give them exact, copy-pasteable commands and say what output you need back.
- Keep `docs/findings.md` current. Tag every claim **[verified]** or **[hypothesis]**, and
  note the evidence for it (which capture, which decompiled class).
- Captures go in `captures/`, named after the action performed. Keep a line per capture in
  `captures/README.md`.
- Vendor APKs and decompiled output go in `vendor/` and `decompiled/`. Both are gitignored
  and must never be committed or redistributed.
- The owner is a sysadmin who is comfortable in the terminal and builds their own tools. Skip
  beginner explanations, but do explain RF/DAB-specific concepts when they come up.

## App goals and platform (phase 5)
- **App name: DABplay+** (owner, 2026-09-25): a play on DAB+ and the CarPlay-style design. Don't use
  "CarPlay" (Apple's trademark) in listings or marketing.
- The owner's aim: "the best DAB+ app out there": **quick, good-looking, very intuitive, and
  reliable**.
- **Platform:** must run on **Android 10 (API 29)**, the Dudu3. Target **Android 8 (API 26)
  and up** if feasible, so check every API/library choice against minSdk 26.

## UI requirements for the app (phase 5)
- A grid of large favourite tiles: the first 8 fit one screen with no scrolling needed for normal use;
  more favourites scroll vertically (soft cap 24).
- Large station name and large scrolling DLS text; big touch targets everywhere.
- Steering-wheel buttons through a proper MediaSession (Dudu units route media keys to
  the active session).
- Auto-start and auto-resume of the last station when the head unit boots.
- Automatic day/night theme.
- Nice to have: service following (switch frequency when the signal drops) and a
  slideshow/logo view.
- Full list of UI/design decisions from the design session: `docs/design-notes.md` (layout 1a/1b selectable,
  driver-side mirroring, 4:3 artwork, "Artist – Title", pluggable dongle drivers; FM is out of scope).
- **Offline first:** the head unit may have no internet. Everything comes from the DAB
  broadcast itself: the station list from a band scan (FIC: FIG 0/1, 0/2, 1/x), stored
  locally; presets store SId + channel + sub-channel; service following uses FIG 0/6
  (linking), 0/21 (frequency info) and 0/24 (other ensembles), plus a rescan as fallback.
  Internet is never *required*, but online features are first-class: **station-logo download is a v1 feature**,
  used whenever a connection is available (cached permanently per station), so nothing breaks without it.
- **Slideshow:** show every broadcast slide (JPEG/PNG, honour TriggerTime). Cover art is just a new slide
  after a track change; nothing labels it as such. NPO sends per-song 320×240 cards with the album cover,
  title, artist and show (verified): show the whole slide, don't crop it. **Station logos:** fetched when online and cached per
  ECC+SId: RadioDNS SPI (official, keyed by DAB ids; NPO and Talpa have it) → the station website's
  web app manifest icon / apple-touch-icon (homepage via radio-browser.info name match; e.g. KINK: 512×512)
  → radio-browser.info favicon → a generated tile (initials). No slideshow-logo step (a logo can't be told from a news
  card) and no manual logo override (owner, 2026-09-27: not needed).
- **MP2 (DAB classic) is a v1 feature** (owner, 2026-09-27): not needed in NL, but users elsewhere (UK etc.) need it
  for distribution. Own Kotlin Layer II decoder: done 2026-09-28 (golden-tested vs ffmpeg), untested on air (no MP2 in NL).
