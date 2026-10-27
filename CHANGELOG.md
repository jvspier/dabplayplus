# Changelog

What changed in each version of DABplay+, newest first. Each version's APK is on its
[release page](https://github.com/jvspier/dabplayplus/releases); every version installs over the previous one and keeps
your stations, favourites and settings.

## [1.3.6] - 2026-10-27

### Fixed
- Some DTR-100 and C100 sticks found all stations but then played nothing and showed no signal: the chip on the
  stick that passes the audio on never did. DABplay+ now notices this within a couple of seconds and reads the audio
  straight from the stick's DAB chip instead, the way DABdream+ does. Sticks that already worked keep working exactly
  as before.
- KINK's "Alternative Rock Radio - KINK non-stop" is no longer shown as if it were a song, also in the head unit's
  media widget. KINK marks that slogan as a song itself.

### Changed
- On Now playing, the song line under the picture uses the full width of the screen, so long titles scroll less often.

## [1.3.5] - 2026-10-26

### Fixed
- Full screen now hides the head unit's bars the standard Android way. On a head unit that shows its bars anyway, the
  app now fits between them instead of being covered
  ([#2](https://github.com/jvspier/dabplayplus/issues/2)). That used to happen only with Full screen off.

## [1.3.4] - 2026-10-26

### Fixed
- Start with the car now also works on head units that sleep when the car is switched off instead of shutting down
  (the Dudu3 does this). Waking up from sleep didn't count as a start, so the app stayed where it was and you had to
  open it and press play yourself. Now it comes to the front and plays the last station, as after a cold start.

### Changed
- Settings are grouped: Look, Favorites & radio, Starting, Station logos and Help. The version number is at the bottom.
- The diagnostics log now also notes the screen going off and on, what opened the app at start-up, and which app or
  key pressed play, pause, next or previous. That helps with reports like
  [#4](https://github.com/jvspier/dabplayplus/issues/4).

## [1.3.3] - 2026-10-26

### Fixed
- The slideshow picture no longer blinks away for a moment every so often. Stations repeat their slides, and each
  repeat briefly cleared the picture before showing it again. A new slide now simply replaces the old one.

## [1.3.2] - 2026-10-21

### Fixed
- On some head units the system bars still came back after leaving the app and returning, until Full screen was
  switched off and on again ([#2](https://github.com/jvspier/dabplayplus/issues/2)). Older Android versions (8 to 10)
  ignored the request to hide them again; DABplay+ now makes sure it takes.
- Some C100 sticks often didn't work when the app started ([#3](https://github.com/jvspier/dabplayplus/issues/3)):
  their DAB chip needs a moment after power-on, and DABplay+ asked only once. It now waits for it, the way other DAB
  apps do. A failed switch to another channel no longer resets the whole dongle either.

### Changed
- While a connected dongle doesn't respond, the app says "DAB dongle not responding, retrying…" instead of "not
  connected".

## [1.3.1] - 2026-10-20

### Fixed
- The head unit's system bar no longer covers the app's title and clock
  ([#2](https://github.com/jvspier/dabplayplus/issues/2)). The bars were hidden only when the app started; after another
  app, a notification or a system screen they came back and stayed. They're now hidden again every time you return.

### New
- **Settings → Full screen** (on by default). Turn it off to keep the head unit's own bars visible, for example a Dudu
  navigation bar; the app then fits next to them, so nothing is covered.

## [1.3.0] - 2026-10-20

### New
- **Settings → Diagnostics log**, with **Save** (to Downloads or a USB stick) and **Share** (any app). One file with the
  app version, every connected USB device and what DABplay+ makes of it, and the app's log; no song titles. No adb
  needed any more.
- A [Dongle report](https://github.com/jvspier/dabplayplus/issues/new?template=dongle-report.yml) form on GitHub that
  asks for exactly that.

## [1.2.1] - 2026-10-19

### Fixed
- C100 sticks from other makers weren't detected ([#1](https://github.com/jvspier/dabplayplus/issues/1)). DABplay+ only
  accepted C100 dongles that report one particular manufacturer name; it now checks the stick's USB interface instead,
  so every C100 brand should work. Devices that only share the USB ID, such as hobby electronics, are still left alone.
- When a USB device has a DAB dongle's ID but isn't one DABplay+ can use, the app now says so instead of "Connect the
  DAB dongle".

## [1.2.0] - 2026-10-19

### New
- **Settings → Background**: four tints for the page behind everything, as swatches: Dusk (the original), Ocean, Ember
  and Aurora. Each has a day and a night version, so one choice covers both.
- **Settings → Pure black at night** (off by default): black behind everything while the night theme is on, whatever
  background you picked.

## [1.1.0] - 2026-10-18

### New
- **Settings → Station logos** shows what the logo download did ("92 of 160 have a logo · 68 not found online", with
  progress while it looks them up), with **Retry missing** and **Refresh all**. A refreshed logo stays until a new one
  replaces it.

### Changed
- A logo lookup that went unanswered (a timeout, the hotspot dropping) no longer counts as "not found" for three days;
  it's tried again next time.
- The Now playing tab in the side rail is a play triangle instead of a waveform.

## [1.0.0] - 2026-10-15

The first stable release, after the first drives on a Dudu3 head unit (Android 10) with the DTR-100 dongle. The same app
as 0.9.1.

## [0.9.1] - 2026-10-06

### New
- The app icon, with a themed version for Android 13 and later.
- The playback notification shows the DABplay+ mark instead of a generic icon.

## [0.9.0] - 2026-10-06

The first release, as a pre-release before the first run on a real head unit.

### New
- Favourites grid with large tiles (eight on one screen, up to 24); long-press and drag to reorder.
- Now playing with "Artist – Title", the station's slideshow (shown whole, never cropped) or its logo.
- Station logos, downloaded automatically when online and cached; everything else works offline.
- DAB+ and DAB (MP2), with its own MP2 decoder.
- Reception handling: signal bars, "No signal" in tunnels, short errors become a brief mute instead of a stutter.
- Follow station: switches to the same station on another multiplex when the signal is gone.
- Traffic announcements (optional, off by default).
- Silent during phone calls, quieter under navigation prompts, steering-wheel buttons through a proper media session,
  resumes the last station; Back never leaves the radio by accident.
- Start with the car (optional, off by default).
- Two layouts (player bar or side panel), day/night theme, controls on the driver's side.
- Dongles: the wRadio C100 / DTR-100 type (tested); C100 Dual Antenna, Si4684 modules and Siano sticks (written from
  public sources, untested).

[1.3.3]: https://github.com/jvspier/dabplayplus/releases/tag/v1.3.3
[1.3.2]: https://github.com/jvspier/dabplayplus/releases/tag/v1.3.2
[1.3.1]: https://github.com/jvspier/dabplayplus/releases/tag/v1.3.1
[1.3.0]: https://github.com/jvspier/dabplayplus/releases/tag/v1.3.0
[1.2.1]: https://github.com/jvspier/dabplayplus/releases/tag/v1.2.1
[1.2.0]: https://github.com/jvspier/dabplayplus/releases/tag/v1.2.0
[1.1.0]: https://github.com/jvspier/dabplayplus/releases/tag/v1.1.0
[1.0.0]: https://github.com/jvspier/dabplayplus/releases/tag/v1.0.0
[0.9.1]: https://github.com/jvspier/dabplayplus/releases/tag/v0.9.1
[0.9.0]: https://github.com/jvspier/dabplayplus/releases/tag/v0.9.0
