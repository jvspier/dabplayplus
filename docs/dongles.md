# USB DAB dongle families (research 2026-09-25)

Web research by a background agent. XDA was read via Wayback snapshots / a reader. **[src]** = read in the
cited source, **[inf]** = inference. Purpose: choose which extra tuner drivers DABplay+ could add (see
`docs/architecture.md`, "pluggable tuner drivers").

| # | Family / examples | USB ID, enumeration | Chips | Decoding | Apps | Public docs | Effort, main risk |
|---|---|---|---|---|---|---|---|
| 1 | **wRadio C100 single**: our DTR-100; XTRONS, Joying, Pumpkin, Dasaita… | 16c0:05dc, vendor bulk [src A,B] | "wRadio C100" [src A] | Host | vendor com.ex.dabplayer.pad, DAB-Z, DABdream+ | Our own reverse engineering | **Done** (fw level 4 verified; ≤1 / ≥5 paths in the decompile) |
| 2 | **C100 Dual Antenna** (YHX) | Same 16c0:05dc [src A] | C100 plus antenna switching [inf] | Host [inf] | DABdream+ ≥ 25.12.18 | None. YHX gave the DABdream dev a library [src A,F] | Medium. Same ID, so we must detect the variant; can't test. Look for a dual-antenna path in newer vendor APKs |
| 3 | **C100 Dual Tuner** (YHX, Erisin ES394, XTRONS) | ID not published; "USB interface completely different" [src F] | 2× C100 [src F] | Unknown | Vendor app only (DABPlus-DF2.19.24 / "Dual DAB+ H4.5.14") [src F,A] | None. YHX refuses to give out a library [src F] | Hard: closed protocol (the vendor APK could be decompiled like ours) |
| 4 | **Siano SMS2270 "Rio"** (sold as ISDB-T/DVB-T sticks) | 187f:0600, vendor bulk; Linux maps it to SIANO_RIO [src A,C] | SMS2270 | Host (raw DAB) [inf] | DABdream+ | Linux smsusb/smscoreapi (GPL; MSG_SMS_DAB_* messages exist), otya128/siano-rio-webusb (ISDB-T only) [src C,D] | Medium-hard: needs vendor DAB firmware (licensing), unverified DAB messages |
| 5 | **Siano SMS2160 "Rhine800"** | 187f:0800 (kernel: DENVER_1530) [src A,C] | SMS2160, 2-way diversity [src G] | Host [inf] | DABdream+ | Same kernel driver | Medium-hard, same as #4 |
| 6 | **Si4684-A10**: Eonon A503 / XFA-DAB220 | 1a86:5537 (QinHeng/WCH VID) [src A] | Si4684 plus WCH MCU [inf] | Mostly on the dongle [src A] | DABdream+, Eonon app | None for the USB side. Si468x command set is known from open projects [src H] | Hard: unknown USB wrapper (a vendor APK could reveal it) |
| 7 | Unknown STM32 CDC key | 0483:5740, CDC serial [src I] | Unknown | Unknown | Own APK only | None | Hard. Unsupported by DABdream |
| 8 | **MVSilicon AP8224 "MP3DAB"** ("WRADIO DAB Stick V1.0") | 1234:1111, USB mass storage (fake 172 GB drive), controlled through `.CTL` files; `DAB_DLS.TXT` [src J] | AP8224C2 + FCI FC8080 [src J] | Dongle (MP3 out) | None found | AlbrechtL/AP8224-DAB-Dongle | Medium: userspace mass storage/FAT (libaums-style), lossy MP3, slideshow unknown |
| 9 | Dension DAB+U | Mass storage, stations appear as "virtual mp3 files" [src K]; ID unknown | Unknown | Dongle | Not DAB-Z [src B] | Dension manual | Medium-hard. Niche |
| 10 | Monkeyboard (Keystone) | 04d8:000a, CDC-ACM at 57600 baud; frames start 0xFE, end 0xFD [src L] | Keystone T1 + PIC18F14K50 | Dongle; **analog audio out only** [src L] | freshollie app (GPL-3) | freshollie source; full spec under NDA [src M] | Easy protocol, but no USB audio → not useful for head units |
| 11 | **RTL-SDR** (0bda:2838/2832, Noxon DAB 0ccd:00b3 [src N]) | Vendor bulk, IQ samples | RTL2832U + R820T/FC0013 | Host: full software demodulation | AbracaDABra (Android 11+, MIT) [src O]; welle.io (Android unmaintained) [src P] | librtlsdr, welle.io, AbracaDABra | Medium: well documented, but high head-unit CPU load; big job |
| 12 | Full-Join DTN-01 (2016) | Unknown | Unknown | Unknown | Full-Join "DAB Player" [src Q] | None | Hard |

## Notes
- DABdream+'s "three types" (2026-04-07 snapshot): C100 (16c0:05dc), Dual Antenna C100 (16c0:05dc), Siano
  SMS2270 Rio (187f:0600). The live post now has five sections (+ Rhine800 187f:0800, + Si4684 1a86:5537).
  Listed as not working: RTL-SDR, all dual tuners, any other ID, 0483:5740 [src A].
- DAB-Z supports every 16c0:05dc dongle (XTRONS USBDAB01, Joying, Pumpkin Y0251, Hotaudio/Dasaita); not SDR, not
  Full-Join, not Dension DAB+U, not the Dasaita DAB box [src B]. Development stopped in 2025 [src S].
- Siano sticks are sold as TV tuners, so users can't easily tell DAB-capable ones apart [src E].
- Priority without hardware [inf]: (1) C100 variants (firmware paths ≤1/≥5, Dual Antenna detection); (2) Si4684
  (1a86:5537) and the C100 Dual Tuner could be reverse-engineered from their vendor APKs like ours; (3) Siano
  (firmware licensing is the blocker); (4) AP8224 mass storage; (5) RTL-SDR as a big separate project.

## Sources
- A: DABdream "Supported USB adapters" post: https://xdaforums.com/t/dabdream-dab-dab-player-for-usb-adapters.4638309/post-89126463 (snapshot https://web.archive.org/web/20260407202437/https://xdaforums.com/t/dabdream-dab-dab-player-for-usb-adapters.4638309/)
- B: DAB-Z v1 thread, first post: https://xdaforums.com/t/dab-app-real-zoulous-dab-z-app-v1-x-unsupported.3609608/
- C: https://github.com/torvalds/linux/blob/master/drivers/media/usb/siano/smsusb.c , https://github.com/torvalds/linux/blob/master/drivers/media/common/siano/smscoreapi.c
- D: https://github.com/otya128/siano-rio-webusb
- E: https://xdaforums.com/t/improvement-of-dab-signal-receiption-quality.4672640/page-13
- F: https://xdaforums.com/t/dabdream-dab-dab-player-for-usb-adapters.4638309/page-76
- G: https://www.worlddab.org/news/4207/siano-launches-advanced-multi-standard-digital-radio-receiver-chip-
- H: https://github.com/PE5PVB/SI4684-DAB-Receiver , https://github.com/NightRideOrg/nr-si468x-module
- I: https://xdaforums.com/t/dabdream-dab-dab-player-for-usb-adapters.4638309/post-89624554
- J: https://github.com/AlbrechtL/AP8224-DAB-Dongle
- K: https://www.dension.com/files/products/dabu/dbu-9203-3-dabu-manual-web.pdf , http://dab.dension.com/usb-connected-dab/
- L: https://github.com/freshollie/monkeyboard-radio-android
- M: https://www.monkeyboard.org/products/85-developmentboard/80-dab-fm-digital-radio-development-board
- N: https://github.com/osmocom/rtl-sdr/blob/master/src/librtlsdr.c
- O: https://github.com/KejPi/AbracaDABra
- P: https://github.com/AlbrechtL/welle.io
- Q: https://www.teltarif.de/radio-dab-plus-dongle-smartphones-tablets/news/71572.html
- R: https://play.google.com/store/apps/details?id=com.thf.dabplayer
- S: https://play.google.com/store/apps/details?id=com.zoulou.dab
