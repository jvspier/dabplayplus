# DABplay+ Android app

Gradle multi-module project (see `../docs/architecture.md`). minSdk 26, compileSdk 37, targetSdk 36; AGP 9.4,
Kotlin 2.4, Compose. Builds with the installed JDK 25 (no Android Studio needed).

| Module | Kind | Contents |
|---|---|---|
| `dab-core` | Kotlin/JVM | DAB stack, ported from `../tools/` with golden tests: CRCs, `fic.FicDecoder` (FIG 0/0,1,2,6,9,13,21,24, 1/0,1), `dabplus.ReedSolomon` + `SuperframeDecoder` + `Asc`, `pad.PadDecoder` (DLS, DL+, MOT slideshow), `pad.DlsParser` ("Artist – Title" rules) |
| `tuner-api` | Kotlin/JVM | `DabTuner` interface, `Transport` (raw bulk USB), results/signal types |
| `driver-dtr100` | Kotlin/JVM | 16c0:05dc family driver over `Transport` (echo/version so far; port of `tools/dtr100.py`) |
| `driver-replay` | Kotlin/JVM | plays recorded FIC/MSC captures as a fake dongle (for the emulator) |
| `logos` | Kotlin/JVM | RadioDNS names (so far); SPI, manifest icons, radio-browser |
| `usb-android` | Android lib | `Transport` over `UsbDeviceConnection.bulkTransfer` |
| `audio` | Android lib | MediaCodec AAC decoder choice (FDK by name); MP2 decoder to come |
| `data` | Android lib | station model (`ServiceKey` = ECC + SId); Room/DataStore to come |
| `app` | Android app | Compose UI, theme tokens from `../design_handoff_radio_head_unit/`, placeholder Home |

```
cd android
echo "sdk.dir=$HOME/.local/opt/android-sdk" > local.properties    # once
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew test assembleDebug
```

Emulator that mimics the head unit: AVD `dab-headunit-api29` (1280×720, 160 dpi, landscape), started with
`emulator -avd dab-headunit-api29 -no-window -no-audio -no-boot-anim -gpu host -no-snapshot`, then
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Golden tests: `../tools/golden.py` writes `../captures/golden/*.txt` from the Python PoC for every capture in
`../captures/raw`; the `dab-core` tests reproduce them line by line (FIC content, every AU, every DLS change and
slide). Captures and goldens are local test material (gitignored); the tests skip when they're absent.
