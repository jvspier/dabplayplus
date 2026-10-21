# DABplay+ Android app

Gradle multi-module project (see `../docs/architecture.md`). minSdk 26, compileSdk 37, targetSdk 36; AGP 9.4,
Kotlin 2.4, Compose. Builds with JDK 25 (no Android Studio needed). CI (GitHub Actions, `../.github/workflows/build.yml`)
runs the unit tests and the release build on every push.

| Module | Kind | Contents |
|---|---|---|
| `dab-core` | Kotlin/JVM | The DAB stack, ported from `../tools/` with golden tests: CRCs, `fic.FicDecoder` (FIG 0/0,1,2,6,9,13,18,19,21,24, 1/0,1), `dabplus.ReedSolomon` + `SuperframeDecoder` (lost-AU reporting) + `Asc`, `pad.PadDecoder` (DLS, DL+, MOT slideshow), `pad.DlsParser` ("Artist – Title"), `mp2.Mp2Decoder` + DAB framing, announcements, `ReceptionMonitor`, `EccGuess` |
| `tuner-api` | Kotlin/JVM | `Transport` (raw bulk USB), `RawTuner` (host decodes), `ModuleTuner` (dongle decodes), signal types |
| `driver-dtr100` | Kotlin/JVM | 16c0:05dc: `Dtr100` (full port of `tools/dtr100.py`; tables from `../tools/gen_kotlin_tables.py`; a transfer-level test replays a real USB session), `C100HighLevel` (YHX Dual Antenna, untested), `C100.open` |
| `driver-siano` | Kotlin/JVM | Siano 187f:0700/0800/0802 (untested) |
| `driver-si4684` | Kotlin/JVM | Si4684 module 1a86:5537 (untested) |
| `logos` | Kotlin/JVM | RadioDNS (dnsjava), SPI XML, web app manifest / touch icons, radio-browser, `PublicOnlyDns`, `ImageSniff` |
| `usb-android` | Android lib | `AndroidUsbTransport`: `Transport` over `UsbDeviceConnection.bulkTransfer` |
| `engine` | Kotlin/JVM | `DabEngine` (scan, play, following, announcements, reconnecting, dropout resume), its ports (`Ports.kt`: `Dongle`, `AudioOut`, `Focus`, `EngineLog`, `EngineTimings`, `Drivers`), `DriverRegistry` |
| `audio` | Android lib | `AudioPlayer` (`AudioOut`): AAC via MediaCodec (platform FDK decoder by name), MP2, PCM; pre-roll, silence for lost AUs |
| `data` | Kotlin/JVM | `StationStore` (one JSON file), `LogoStore` |
| `app` | Android app | Compose UI (Home, Stations, Now playing, Recent, Settings), the engine's Android ports (`AndroidDongle`, `AudioFocus`, `Diagnostics`), `SettingsStore` (SharedPreferences), `RadioService` + `DabPlayer` (Media3), `LogoFetcher`, `BootReceiver` |
| `baselineprofile` | Android test | generates the baseline profile on a connected emulator |

## Building
```
cd android
echo "sdk.dir=$HOME/.local/opt/android-sdk" > local.properties    # once; your SDK path
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew test assembleRelease
```

Judge smoothness only on a **release** build: debug Compose is unoptimised and janks by design. The release build is
R8-minified and signed with the release key when `~/.gradle/gradle.properties` has `dabplay.storeFile` and
`dabplay.password` (the key and its password never go into the repo); elsewhere, CI included, with the debug key:
```
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed-profile -f io.github.jvspier.dabplay   # applies the baseline profile, as Play does
adb shell dumpsys gfxinfo io.github.jvspier.dabplay reset   # then interact, and read it back without "reset"
```

**Releasing:** raise `versionCode` by one and `versionName` (small steps: the last number for fixes and small
additions), add the version to `../CHANGELOG.md` (the website shows it, and its build stops without it), build with the release key, then tag `vX.Y.Z` and publish the APK as a
GitHub release whose notes say the same as the changelog. For Google Play: `./gradlew bundleRelease` gives
`app/build/outputs/bundle/release/app-release.aab`, signed with the same key.

The baseline profile (`app/src/release/generated/baselineProfiles/`) is regenerated after bigger UI changes, with the
head-unit emulator running: `./gradlew :app:generateReleaseBaselineProfile` (module `:baselineprofile`; the app is left
installed afterwards, see `gradle.properties`).

## Tests
- `./gradlew test` runs everything on the JVM. `BENCH=1 ./gradlew :dab-core:test --tests '*DecodeBenchmark*'` measures
  decoding cost; `LIVE_LOGOS=1` enables the live logo lookup test (network).
- Golden tests: `../tools/golden.py` writes `../captures/golden/*.txt` from the Python PoC for every capture in
  `../captures/raw`; the `dab-core` tests reproduce them line by line (FIC content, every AU, every DLS change and
  slide). Captures and goldens are local test material (gitignored); the tests skip when they're absent (CI included).
- Engine tests (`engine/src/test`, run everywhere): `DabEngine` against a scripted tuner that plays a test broadcast built
  in the repo (`Broadcast`: valid FIC and DAB+ superframes). They cover playback, following to another multiplex on
  signal loss, traffic announcements, reconnecting after a USB error, dropout resume and a band scan, in about 15 s.

## Diagnostics log
The app keeps its own event log (startup with device info, dongle and driver events, tuning, playback, audio decoder
and underruns, unplugs, a signal line every 30 s while playing, every second while reception is weak). After a drive:
`adb pull /sdcard/Android/data/io.github.jvspier.dabplay/files/diagnostics/` (`dabplay.log`, plus `dabplay.1.log` once
the first reaches 2 MB). Song texts (DLS) stay out of the file, in logcat only: on Android 10 other apps with storage
permission can read this folder, and the songs heard with their times would be a listening history.
On the device itself, Settings → Diagnostics log → Save or Share makes one file of it, with the USB devices connected at
that moment on top (also unknown ones: that's how a report about a new dongle shows its id).

## Emulators
Two AVDs mimic the head unit (1280×720, 160 dpi, landscape):
- `dab-headunit-api34`: **the one for testing with the dongle** (xHCI USB passthrough).
- `dab-headunit-api29`: Android 10 like the Dudu3, for version-specific checks. It can't host the dongle: the API 26/29
  emulator kernels have EHCI only (no OHCI/UHCI/xHCI) and the dongle is full-speed.

Dongle passthrough (no recordings are bundled; there is deliberately no replay driver):
- Emulator images don't declare `android.hardware.usb.host` (real head units do). Once per AVD: boot with
  `-writable-system`, `adb root && adb remount`, reboot, `adb root && adb remount`, push
  `<permissions><feature name="android.hardware.usb.host"/></permissions>` to
  `/system/etc/permissions/android.hardware.usb.host.xml`, reboot. Keep using `-writable-system` afterwards.
- Start: `emulator -avd dab-headunit-api34 -gpu host -no-snapshot -writable-system -qemu -device qemu-xhci,id=xhci -device usb-host,bus=xhci.0,vendorid=0x16c0,productid=0x05dc`
  (add `-no-window` for headless).
- After a reinstall or a reboot Android asks for USB permission again; tick "Always open" and OK.

Gotchas: `-gpu swiftshader_indirect` crashes emulator 37.1.11 (use `-gpu host`). `appops set … allow` needs
`adb shell appops write-settings` before a reboot, or the grant is lost.
