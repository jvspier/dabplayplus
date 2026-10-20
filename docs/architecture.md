# DABplay+: Android app architecture

The design was approved by the owner on 2026-09-25; this document describes the app **as built (2026-10-04)**. It
builds on the verified PoC (`tools/`, `docs/protocol.md`) and the UI decisions in `docs/design-notes.md`. Work that is
agreed but not built yet is marked **planned**.

## Constraints and targets
- minSdk **26** (Android 8), solid on **API 29** (the Dudu3: Unisoc UIS8581, 8× Cortex-A55, PowerVR GE8322). targetSdk
  36, compileSdk 37.
- Landscape head units; design baseline 1280×720, scales to other sizes.
- **Offline first**: DAB reception needs no internet. Station logos are downloaded when online and cached; nothing
  breaks without them.
- **Pure Kotlin, no native code**: one APK for every ABI and no GPL dependencies. AAC goes through the platform's
  MediaCodec; MP2 through our own Layer II decoder.
- Budget hardware: one cached background layer instead of four full-screen paints, a baseline profile shipped in the
  APK, decoding well under 1 % of a core (findings.md, "Performance on head-unit hardware").

## Big picture

```
 ┌──────────────── app: MainActivity + Compose UI (AppShell, 5 screens) ─────────────────┐
 │ Home · Stations · Now playing · Recent · Settings      bar/panel layout, driver side   │
 └──────────▲──────────────── EngineState (StateFlow) ───────────────────▲────────────────┘
            │ MediaController                                            │ state, slides, text
 ┌──────────┴── playback: RadioService (Media3 MediaSessionService) ─────┴────────────────┐
 │  DabPlayer (SimpleBasePlayer): steering-wheel keys, notification, artwork               │
 │  DabEngine (:engine, plain Kotlin): one USB thread + command queue; scan, play,         │
 │  following, announcements, reception; one RadioSession per connection                   │
 │  (raw Session / ModuleSession); sees Android only through its ports                     │
 └───────┬──────────────────────────────┬──────────────────────────────┬──────────────────┘
         │ tuner-api: RawTuner,         │ dab-core (plain Kotlin/JVM)  │ audio: AudioPlayer
         │ ModuleTuner, Transport       │ FIC, superframes, RS, PAD,   │ MediaCodec AAC, MP2,
 ┌───────┴──────────────────────┐      │ MP2, announcements, ECC guess│ PCM, AudioTrack
 │ driver-dtr100 (C100 family)  │ raw  └──────────────────────────────┘
 │ driver-siano                 │ FIC+MSC → decoded on the host
 │ driver-si4684 (module)       │ PCM, text, slides → decoded on the dongle
 └───────┬──────────────────────┘
         │ usb-android: AndroidUsbTransport (UsbDeviceConnection.bulkTransfer)
       dongle
 data: StationStore (one JSON file) · LogoStore (files + index)    app: SettingsStore (SharedPreferences)
 logos: LogoFetcher → RadioDNS SPI → website manifest / touch icon → radio-browser (only when online; Settings:
        Retry missing / Refresh all; an unanswered lookup is retried next pass, "no logo anywhere" after 3 days)
```

## Modules (Gradle)
| Module | Contents | Depends on |
|---|---|---|
| `:dab-core` | FIC/FIG (0/0,1,2,6,9,13,18,19,21,24; 1/x), DAB+ superframes (firecode, RS(120,110), AU split, lost-AU reporting), ASC, PAD (X-PAD, DLS, DL Plus, MOT slideshow), "Artist – Title" rules, MP2 (Layer II decoder + DAB framing), announcements, reception monitor, ECC guess | nothing (JVM, fast unit tests) |
| `:tuner-api` | `Transport` (bulk USB, faked in tests), `RawTuner` (host decodes), `ModuleTuner` (dongle decodes) | nothing |
| `:driver-dtr100` | `Dtr100` (register-level, verified), `C100HighLevel` (YHX Dual Antenna, untested), `C100.open` picks one | tuner-api |
| `:driver-siano` | Siano sticks with firmware on board (untested) | tuner-api, dab-core |
| `:driver-si4684` | Si4684 module (Eonon A503, XFA-DAB220; untested) | tuner-api, dab-core |
| `:usb-android` | `AndroidUsbTransport` | tuner-api |
| `:engine` | `DabEngine` (scan, play, following, announcements, reconnecting, dropout resume), its ports (`Ports.kt`), `DriverRegistry` | tuner-api, dab-core, data, the drivers, logos (`ImageSniff`); JVM |
| `:audio` | `AudioPlayer` (`AudioOut`): AAC via MediaCodec (platform FDK decoder chosen by name), MP2, PCM; own thread, pre-roll, underrun recovery, silence for lost AUs, volume ramps | dab-core, engine |
| `:data` | `StationStore` (JSON, single writer thread, fsync + rename), `LogoStore` | coroutines (JVM) |
| `:logos` | RadioDNS (dnsjava CNAME/SRV), SPI XML, web app manifest / touch icons, radio-browser; `PublicOnlyDns`, size caps, image sniffing | OkHttp, dnsjava |
| `:app` | Compose UI, the engine's Android side (`AndroidDongle`, `AudioFocus`, `Diagnostics`), `SettingsStore`, `RadioService` + `DabPlayer`, `LogoFetcher`, `BootReceiver` | everything above, Media3 |
| `:baselineprofile` | generates the baseline profile on a connected emulator | (test module) |

## Tuner drivers
Two contracts, because the dongles split into two kinds:

```kotlin
interface RawTuner {                       // the dongle hands over the raw ensemble; dab-core decodes
    fun tune(khz: Int, retries: Boolean): Tuned
    fun ficRead(): ByteArray?               // whole 32-byte FIBs
    fun startAudio(subchannel: Int, bitrateKbps: Int); fun stopAudio()
    fun mscRead(): ByteArray                // raw sub-channel bytes
    val signal: SignalReading?              // bars 0..4, none, weak, raw value
    fun close()
}
interface ModuleTuner {                    // the dongle decodes: scan, list, PCM, text, slides
    fun scan(progress: (Int, Int) -> Unit, timeoutMs: Long): List<ModuleService>
    fun play(service: ModuleService, serviceFollowing: Boolean); fun stop()
    val signal: SignalReading?; val following: Boolean
}
```
All calls come from the engine's one USB thread (the Si4684 driver runs its own reader threads and calls back through
a `ModuleListener`). Drivers only see `Transport`, so their tests replay or simulate USB traffic on the JVM.

Selection (`DongleKind`, `C100.open`): 16c0:05dc with bulk endpoints 0x02 / 0x82 on interface 0 (`DriverRegistry.fits`:
the id is a shared V-USB one, but low-speed V-USB devices can't have bulk endpoints; the manufacturer name differs between
C100 makers, issue #1) → the register-level DTR-100 driver;
only if its init fails, the YHX high-level set. 187f:0700/0800/0802 → Siano. 1a86:5537 → Si4684. `DriverRegistry` is
the one place that knows this (USB ids → `DongleKind` → driver); the engine sees only its `Drivers` interface, so a new
dongle family is a new kind and a line there.

## Engine ports
`:engine` is plain Kotlin: it reaches the platform only through the interfaces in `Ports.kt`, which the app implements.

| Port | What it is | In the app |
|---|---|---|
| `Dongle` | a USB device: name, `DongleKind`, `open()` → `Transport` + close | `AndroidDongle` (manufacturer check, claim, bulk endpoints) |
| `AudioOut` | AAC / MP2 / PCM in, silence for lost AUs, level, release | `AudioPlayer` |
| `Focus` | audio focus requests and changes (loss, ducking, gain) | `AudioFocus` |
| `EngineLog` | events, warnings, errors; `logcatOnly` for song texts | `Diagnostics` |
| `EngineTimings` | following, relock, reconnect backoff, learning intervals | defaults; shortened in tests |
| `Drivers` | kind + transport → `RawTuner` / `ModuleTuner` | `DriverRegistry` |

## Station model
- A **service** is keyed by **ECC + SId** (e.g. `E3.83C7` = 538). Favourites, recents and logos use that key.
- `StoredEnsemble` (EId, channel, kHz, label, last seen, missed scans) holds its `StoredService`s (key, label,
  sub-channel, bitrate, ASCTy). A scan replaces the ensembles it found; one missing from a scan is dropped only after
  being missed twice (marginal multiplexes come and go).
- Learned alternatives (FIG 0/24 + 0/21 while listening) are stored per service for service following.
- Si4684 modules have no FIC: their ensembles get a synthetic id above 0xFFFF per channel, and the ECC is guessed from
  the SId's country digit and the device's country (`EccGuess`).

## Playback and tuning flow
1. A tap or steering-wheel key → `DabEngine.play(key)` (a command on the USB thread's queue).
2. Same ensemble: stop the sub-channel, start the new one (no retune), 0.6–0.9 s to audio. Another ensemble: tune
   first, ≈ 0.8 s more.
3. MSC → `SuperframeDecoder` (or `Mp2Framer`) → `AudioPlayer`; PAD → DLS / slides → `EngineState` → UI and media
   session. FIC is read every 50 ms while playing: announcements (FIG 0/18, 0/19) and alternatives (0/24, 0/21).
4. **Reception**: `ReceptionMonitor` turns signal + good/bad frames into GOOD / WEAK / LOST. A lost AU becomes silence
   of its length, so short errors are short mutes, not re-buffers.
5. **Service following**: after 6 s of lost signal, try the same service on other known ensembles (band III only),
   then retry every 30 s; re-lock attempts on the current one every 5 s.
6. **Traffic announcements** (setting, off by default): switch to the announcing sub-channel on the same ensemble
   and back; alarms always come through.
7. Audio focus: a call silences, navigation ducks to 25 %, another media app pauses.

## Background, boot and USB (head-unit specifics)
- `RadioService` is a Media3 `MediaSessionService` (foreground, `mediaPlayback`): steering-wheel keys, notification,
  station logo or slide as artwork.
- USB: `device_filter.xml` lists the supported dongles; with "always open" ticked, Android opens the app when the dongle
  appears and grants access. USB permission does **not** survive a cold boot, and a dongle present before boot
  completes isn't announced (verified on the emulator), so the first open after a cold boot shows the prompt.
- **Start with the car** (setting, off by default): Android's "dongle appeared" goes to `UsbAttachActivity`, invisible and
  in its own task. It opens the radio only with the setting on or when the radio is already on screen, so otherwise
  nothing comes forward and nothing plays. On, `BootReceiver` also opens the app at boot; Android 10+ allows that only
  with "Display over other apps".
- Back never leaves the app by accident (another tab → Home; Home → nothing).
- System bars (Settings → Full screen, `MainActivity.applySystemBars`): on, they're hidden, and hidden again whenever the
  window gets focus back, because Android shows them after another app, the USB prompt or a share screen and doesn't
  hide them on return (issue #2); a swipe from an edge shows them for a moment. Off, they stay and AppShell pads the
  content by the system-bar insets, so nothing is covered; their icons follow the app's day/night theme.
- Unplugs are handled in the app process (`DabApp`), so they're seen with no screen open. If the dongle drops out while
  playing (a voltage dip while cranking or at a start-stop restart, a USB hub reset), the station is remembered and
  `UsbAttachActivity` reconnects it in the background when it's back [verified: 538 again < 1 s after the replug,
  home screen stayed in front].
- **Diagnostics log** (`diag/Diagnostics`): engine events, driver and audio-player messages, unplugs, and a signal line
  every 30 s while playing (`rx30`; every second while reception isn't good), in logcat and in two rolling files of up
  to 2 MB: `adb pull /sdcard/Android/data/io.github.jvspier.dabplay/files/diagnostics/`. Without adb: Settings →
  Diagnostics log → Save / Share (`DiagnosticsExport`) gives one file with a header, every USB device connected now
  (id, names, endpoints, what the app makes of it; unknown ones too) and both log parts; GitHub's "Dongle report"
  issue form asks for it.

## Threads
- `dab-usb`: the engine's command loop and all dongle I/O (blocking `bulkTransfer`).
- `dab-audio`: `AudioPlayer`'s decoder + AudioTrack writes (urgent-audio priority, 400 ms pre-roll).
- `station-store`: JSON writes. Logo fetching: an app-scoped coroutine on IO.
- Si4684 only: the driver's reply reader and PCM reader threads. `AudioPlayer.offer()/release()` are synchronised, and
  the module session lets no PCM through once it has stopped.

## Testing
- `:dab-core` **golden tests**: `captures/raw/*.msc` / `*.fic` against the Python PoC's output. Captures stay local
  (broadcast content), so these tests skip elsewhere, CI included.
- `:engine` tests run on the JVM (CI included) against a scripted `RawTuner` and a test broadcast built in the repo
  (`Broadcast`: valid FIC with FIG 0/0, 0/1, 0/2, 1/0, 1/1, 0/18, 0/19, and DAB+ superframes with firecode, AU CRCs and
  RS parity), so the real decode path runs: playback, signal loss → following to another multiplex, a traffic
  announcement in and out (and none with the setting off), reconnecting after a USB error with backoff, dropout
  resume (and none after a pause), a band scan into the store. Real threads with shortened `EngineTimings`.
- Drivers: `Dtr100` replays a real session log (byte-identical traffic); the untested drivers are checked against
  simulated dongles built from their protocol descriptions.
- MP2: reference files generated with ffmpeg (`tools/gen_mp2_vectors.sh`), matched to ≤ 1 LSB.
- `DecodeBenchmark` (`BENCH=1`): decoding cost per second of audio.
- CI (GitHub Actions): wrapper validation, unit tests, release build on every push.
- Hardware: the emulator with USB passthrough (android/README.md); the Dudu3 over ADB on Wi-Fi.

## Open items / risks
- The Dudu3 runs it smoothly (first drive, 2026-10-15). Steering-wheel buttons, navigation ducking, sleep and cold boot work. Still to
  check there: a phone call, signal-bar calibration.
- Untested on air: MP2 (no MP2 in NL), a real announcement switch, following a station while driving.
- Three of four dongle families are untested on hardware (README).
- Dongle firmware levels other than 4: code paths from the decompile, untested.
- Never write the vendor's FM registers: their state survives power-off and degrades DAB until a replug.
