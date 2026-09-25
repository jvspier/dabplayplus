# DABplay+: Android app architecture

Status: **approved by the owner, 2026-09-25**. Nothing is built yet. It builds on the verified PoC (`tools/`, `docs/protocol.md`)
and the design decisions in `docs/design-notes.md`.

## Constraints and targets
- minSdk **26** (Android 8), must be solid on **API 29** (Dudu3). targetSdk = whatever the Play Store requires
  at release time (API 35/36 at the time of writing).
- Landscape head units; design baseline 1280×720, scaling to 2000×1200 and others.
- **Offline first**: DAB reception needs no internet and nothing breaks without it. Online features are still
  first-class: **station-logo download is in v1**, fetched whenever a connection is available and cached.
- "Quick" targets: preset switch within the same ensemble **< 1 s** to audio (measured 0.90 s in the PoC; DAB's
  time interleaving + superframe alignment put the floor at ≈ 0.6 s); across ensembles **≈ 1.5 s**;
  cold start (boot → audio) as soon as the dongle enumerates, **< 3 s** after that.
- **Pure Kotlin, no native code** (verified: MediaCodec decodes DAB+ on API 26/29). That gives one universal
  APK for every ABI (arm64, armv7, x86) and no GPL dependencies.

## Big picture

```
 ┌──────────────── app (single Activity, Jetpack Compose) ───────────────────────┐
 │ Home · Stations · Now playing · Recent · Settings     layout 1a/1b + mirroring │
 └───────────────▲──────────────── state (StateFlow) ───────────────▲──────────────┘
                 │ MediaController (play/pause/skip)                │ RadioState (DLS, slide, signal, scan)
 ┌───────────────┴── playback: RadioService (Media3 MediaSessionService, foreground) ┴─┐
 │  RadioPlayer (Media3 SimpleBasePlayer) ── steering-wheel keys, notification         │
 │  RadioEngine: tuning, service following, scan, favourites ↔ presets                  │
 └───────┬───────────────────────────────┬───────────────────────────────┬─────────────┘
         │ tuner-api (interface)         │ dab-core (pure Kotlin/JVM)    │ audio
 ┌───────┴─────────┐          ┌──────────┴──────────────┐      ┌─────────┴──────────────┐
 │ driver-dtr100   │  FIC,MSC │ FIC/FIG → service DB    │ AUs  │ MediaCodec AAC (FDK)   │
 │ (16c0:05dc)     │ ───────► │ DAB+ superframe, RS, AU │ ───► │ MP2 (own Kotlin dec.)  │
 │ driver-replay   │          │ PAD: DLS, DL+, MOT SLS  │ PCM  │ AudioTrack, audio focus│
 │ (future others) │          │ "Artist – Title" rules  │      └────────────────────────┘
 └───────┬─────────┘          └─────────────────────────┘
         │ Android USB host API (UsbDeviceConnection.bulkTransfer)
       dongle
 data: Room (ensembles, services, favourites, recents) · DataStore (settings) · disk cache (logos, slides)
 logos: RadioDNS SPI → website manifest icon → radio-browser → SLS logo (WorkManager, only when online)
```

## Modules (Gradle)
| Module | Contents | Android deps |
|---|---|---|
| `:dab-core` | FIC/FIG parsing (0/0,1,2,6,9,13,21,24; 1/x), service model, DAB+ superframe (firecode, RS(120,110), AU split), ASC building, PAD (X-PAD, DLS, DL+, MOT slideshow), DLS → "Artist – Title" normaliser with per-broadcaster rules | **none** (plain Kotlin/JVM, so fast unit tests) |
| `:tuner-api` | `DabTuner` interface, capabilities, driver registry (match by VID/PID + probe) | USB types only |
| `:driver-dtr100` | Port of `tools/dtr100.py`: MCU commands, register access, init, full tune, FIC read, sub-channel start/stop, MSC read; firmware paths ≤1, 2–4, ≥5 | USB host API |
| `:driver-replay` | Plays back `captures/raw` recordings as if a dongle were attached, for development in the emulator and UI work without hardware | none |
| `:audio` | AAC via MediaCodec (decoder chosen by name: `c2.android.aac.decoder` / `OMX.google.aac.decoder`, then fallback), MP2 via an own Kotlin Layer II decoder, AudioTrack output, audio focus and ducking | media |
| `:data` | Room DB, DataStore settings, image cache | Room, DataStore |
| `:logos` | RadioDNS lookup (DNS CNAME/SRV via dnsjava), SPI XML parse, manifest/apple-touch-icon scrape, radio-browser API | OkHttp, WorkManager |
| `:app` | Compose UI (Nocturne tokens → theme, Inter font, Phosphor icons), navigation, settings screens, playback service wiring, diagnostics export | Compose, Media3 |

## Tuner driver interface (sketch)
```kotlin
interface DabTuner {
    val capabilities: Capabilities            // bands (III, L), max parallel sub-channels, fwLevel
    suspend fun open(); suspend fun close()
    suspend fun tune(freqKhz: Int): TuneResult  // Locked(signal) | NoSignal(reason)
    val fic: Flow<ByteArray>                  // 384-byte FIC blocks (or whole FIBs)
    suspend fun startSubchannel(subch: Int, bitrateKbps: Int)
    suspend fun stopSubchannel()
    val msc: Flow<ByteArray>                  // raw sub-channel bytes
    val signal: StateFlow<Signal>             // u1 / quality → 0..4 bars
}
```
Everything above the interface (FIC parsing, audio, PAD, UI) is shared. A dongle that outputs decoded audio
(PCM/MP3 families) gets an alternative `AudioSource` path in the same interface family.

## Station model
- A **service** is keyed by **ECC + SId** (e.g. `E3.83C7` = 538), which is globally unique and stable across
  multiplexes. Favourites, recents, logos and slides are all keyed by it.
- A service can be on several **ensembles** (EId + frequency + sub-channel + bitrate + ASCTy). The DB keeps
  all known carriers per service, plus last-seen time and signal.
- A scan updates the DB. An ensemble missing from a scan is only dropped after it has been missed several
  times, because marginal multiplexes come and go.

## Playback and tuning flow
1. Preset tap → `RadioEngine.play(serviceKey)`.
2. Same ensemble as now: proper stop, start the new sub-channel (no retune). Verified in the PoC: 0.90 s.
3. Other ensemble: tune, then **start the stored sub-channel straight away** (don't wait for the FIC), and
   verify against the FIC in the background, target < 1.5 s.
4. MSC → superframe decoder → AUs → MediaCodec → AudioTrack. PAD from the AUs → DLS / slides → UI.
5. **Service following**: a signal watchdog. On loss (> N s), try the same SId on other known ensembles
   (DB + FIG 0/24 + 0/21). No FM fallback: FM is out of scope (see design-notes).

## Background, boot and USB (head-unit specifics)
- `RadioService` is a Media3 `MediaSessionService`, foreground type `mediaPlayback`. The MediaSession gives
  steering-wheel keys (next/previous favourite, play/pause) and the system media notification for free.
- **USB permission without a prompt at every start**: an `USB_DEVICE_ATTACHED` intent filter with
  `device_filter.xml` (VIDs/PIDs of the supported dongles). Once the user ticks "always open", Android grants
  access automatically on every attach. (The attach intent only goes to activities, so an invisible
  trampoline activity hands it to the service.)
- **Boot and ACC**: a `BOOT_COMPLETED` receiver starts the service (allowed on Android 10). Many head units don't
  reboot but *sleep* on ACC off, and the dongle re-enumerates on wake. So the service treats every
  attach/detach as normal: re-open, re-init and resume the last service.
- Audio focus: request focus for media. Navigation prompts duck (or pause, depending on the unit). This
  must be tested on the Dudu3.

## Performance notes
- USB I/O on one dedicated thread (blocking `bulkTransfer`); the DAB pipeline runs on a single-thread
  coroutine dispatcher; MediaCodec and AudioTrack on their own. Ring buffer between MSC and the decoder.
- RS decoding only runs when the syndrome is non-zero (it almost never is on a good signal).
- Register writes are 0.2 ms each over USB (measured), so init (≈ 150 ms) and tune (≈ 0.6 s) are dominated
  by the chip's own settle times, not by the code.

## Testing
- `:dab-core` **golden tests on the JVM**: feed `captures/raw/*.msc` / `*.fic` and compare with the Python PoC's
  output (services, DLS, slides, AU CRCs). Captures stay out of any public repo (broadcast content).
- `:driver-replay` lets the whole app run in the emulator without a dongle.
- Decoder: MediaCodec test like `tools/android/MediaCodecTest.java`, also as an instrumented test.
- On-device: the Dudu3 over ADB on Wi-Fi with logcat, plus the in-app diagnostics export (transfer log + events).

## Open items / risks
- **MP2 (DAB classic)**: decided, it is supported. Android has no Layer II decoder (its MP3 decoder is Layer III
  only), so `:audio` gets its own Kotlin Layer II decoder (a port of a permissively licensed one such as kjmp2,
  licence to verify, or written from the spec), golden-tested against mpg123/ffmpeg. There are no MP2 stations
  here, so testing uses files.
- **Vendor AAC decoders** on some head units: choose the platform FDK decoder by name. Verify on the Dudu3.
- **ACC sleep/wake** and USB re-enumeration behaviour on the Dudu3 (and the ~2 s first-enumeration delay).
- Dongle firmware levels other than 4 are untested (the code paths come from the decompile).
- Build tooling: Gradle + AGP need a JDK AGP supports (installing JDK 21 alongside 25 may be needed).
