# tools

- `dtr100.py`: the driver + CLI. `check`, `scan [--channels ..]`, `play --channel 11C --service 538
  [--seconds N] [--out x.wav] [--raw x.msc] [--log x.log]`. Prints DLS and slides while playing.
- `fic.py` (FIC/FIG parsing, channel table), `dabplus.py` (superframe/RS/AU + faad2 via ctypes),
  `pad.py` (X-PAD → DLS, DL+, MOT slideshow), `decode_raw.py` (offline decode of `--raw` captures).
- `fm_test.py`: literal port of the vendor's unused FM API (init, tune, seek detector, RSSI, audio start/stop); see findings 2026-09-25.
- `switch_test.py`: play A, proper stop, switch to B on the same ensemble without re-init; measures the switch time.
- `gen_kotlin_tables.py`: generates the Kotlin register/PLL tables for the Android dtr100 driver (DAB only, no FM).
- `golden.py`: golden reference output of the PoC for every capture (captures/golden/), used by the Kotlin dab-core tests.
- `export_aus.py`: raw capture → length-prefixed AU file + ASC JSON + faad2 reference WAV (decoder tests).
- `android/MediaCodecTest.java` + `android/compare_pcm.py`: Android MediaCodec decode test run via `app_process`
  in an emulator (build: `javac --release 11 -cp android.jar`, then `d8 --min-api 26`); compare with faad2.
- `probe.py`: first-contact test (MCU echo + version only). Run with `.venv/bin/python tools/probe.py`.
- `extract_tables.py`: dump the chip register/PLL tables from the vendor `libdab.so` to JSON
  (output goes under `decompiled/`, never commit it).
- `ghidra/DecompileAll.java`: Ghidra headless post-script that decompiles every function to one C file,
  optionally creating and naming functions from a TSV first (used for the JNI table).
- `ghidra/fn.py`: print one function (plus callees with `-d N`) from that C dump.

Setup: `python3 -m venv .venv && .venv/bin/pip install -r tools/requirements.txt`; needs faad2-libs and pipewire (`pw-play`). Ghidra lives in `~/.local/opt/ghidra_12.1.4_PUBLIC`
and needs `java-25-openjdk-devel` (a full JDK).

Regenerating the decompile (Ghidra loads x86 ELF at base 0x10000, so JNI addresses from the
file must have 0x10000 added; see `decompiled/3.26/jni-table.ghidra.tsv`):

```
cd decompiled/3.26
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ~/.local/opt/ghidra_12.1.4_PUBLIC/support/analyzeHeadless \
  "$PWD/ghidra" libdab_x86 -import apk/lib/x86/libdab.so \
  -scriptPath ../../tools/ghidra -postScript DecompileAll.java "$PWD/libdab-x86.c" "$PWD/jni-table.ghidra.tsv" \
  -deleteProject
```

Android SDK (no Android Studio): `~/.local/opt/android-sdk` (cmdline-tools 23, platform-tools, emulator,
platforms;android-29, build-tools;37.0.0, system images android-26/29 default x86_64). AVDs `dab-api26`, `dab-api29`.
Start headless: `emulator -avd dab-api29 -no-window -no-audio -no-boot-anim -gpu host -no-snapshot` (other GPU modes crash here).
