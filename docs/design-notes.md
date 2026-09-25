# Design notes (input for the Android design session)

Design reference: `design_handoff_radio_head_unit/` (Claude Design export: layouts 1a "tiles + mini player" and
1b "docked player panel", five screens, Night/Day themes; README has all tokens and sizes). It was generated
from a *streaming radio* brief, so some parts need DAB equivalents. Collect decisions here; settle them in the
design session.

## Decided
- **Drop "Stream quality"** from Settings (owner, 2026-09-25): DAB+ reception costs nothing and has no
  data usage; the setting came from the streaming template.
- **Drop "genre"** from tiles, rows and Now playing (owner, 2026-09-25).
- **Artwork = what the broadcast sends** (the current slide, else the station logo), fitted into the design's
  frame. No artwork settings (owner, 2026-09-25).
- **The artwork frame is 4:3 (320×240)**, the DAB slideshow format, instead of the mock's square. The design
  is adapted so it looks good (owner, 2026-09-25). Logos (often square) are fitted inside it.

- **Audio decoding: Android MediaCodec** (`audio/mp4a-latm` + ASC as csd-0), verified on API 26/29 for all DAB+
  formats received here. Prefer the platform FDK decoder by name. No faad2 (avoids GPL).

- **Both layouts, user-selectable** (owner, 2026-09-25): Settings → Layout: "Mini player bar" (1a) or
  "Side player panel" (1b). Plus a **driver-side / handedness** setting that mirrors the rail (and the side panel)
  to the left or right, so drivers in right-hand-drive countries (UK etc.) don't have to reach over. Default
  from the device locale/country, user can change it.
- **Now playing artwork: 4:3 frame**, ≈ 520×390 in 1a (the 320×240 slide at 1.6×); square logos centred inside.
- **No LIVE badge.** A small, subtle **signal indicator** (bars) instead; not a big pill.
- **Now-playing text is always shown as "Artist – Title"** (owner: "cohesive and correct every time"). Source:
  DL+ when present, otherwise per-broadcaster DLS rules (NPO "Artist - Title", Talpa/DPG "Title - Artist",
  100% NL "Onair: Artist - Title"). When the DLS isn't a song (slogans, show names) or the format is unknown:
  show the raw text on the title line and never guess a split.
- **Lists and tiles show station names only** (no multiplex/channel line, no genre).
- **Keep Recent**, with a **Clear** button (with undo).
- **Settings:** Display Day/Auto/Night, Layout (bar/panel), Driver side (left/right), Resume last station,
  Edit favourites, Download station logos (when online), Follow station when the signal drops. Later: FM
  fallback, traffic announcements.
- **Resolution:** design baseline 1280×720 (the owner's Dudu3), but it must scale properly to other head units
  (2000×1200 etc.): the app is intended for the **Play Store** eventually.

- **Favourites: no hard limit of 8** (owner, 2026-09-25). The first 8 fit one screen (4×2, no scrolling for
  normal use); more favourites scroll vertically with row snapping. Soft cap 24. Skip back/forward cycles
  through all favourites.
- **FM: only through the dongle** (owner, 2026-09-25), not the head unit's own tuner (no standard Android API;
  vendor-specific). The UI makes clear it's FM via the DAB dongle, and the option appears only when the connected
  dongle supports FM. **Tested 2026-09-25: the DTR-100 can't do FM usefully**: the chip's FM mode runs, but the RF front end
  passes almost no 88–108 MHz and no FM audio reaches USB. So no dongle we support has FM today; the capability flag stays false
  and the FM option stays hidden until a dongle that can do it is supported.
- **Dongle support: pluggable tuner drivers** behind one interface; the DAB stack (FIC, audio, PAD, UI) is
  shared. Launch with the 16c0:05dc family fully supported (port the firmware ≤1 and ≥5 code paths from the
  decompile too); other families (AP8224/FC8080 mass-storage, Siano, maybe RTL-SDR later) are "experimental"
  until confirmed by users. In-app **diagnostics export** for users with untested dongles. A research round on
  existing dongle families comes first.
- **Legal:** The owner accepts the risk of shipping the vendor-derived register tables (their call, 2026-09-25).

- **App name: DABplay+** (owner, 2026-09-25). No existing Play Store app or GitHub repo with that name
  (checked 2026-09-25); an EUIPO trademark search is still to do before publishing.
- **MP2 (DAB classic) is supported** (owner, 2026-09-25) for DAB users worldwide: an own Layer II decoder in
  Kotlin (port of a permissively licensed decoder such as kjmp2, licence to verify, or written from the spec),
  tested against mpg123/ffmpeg; the vendor APK's `assets/dab.bin` is an MP2 test file.

## To discuss
- "Station unavailable / buffering" states map to DAB states: tuning, no signal, weak signal (signal meter?).
- Scan (≈ 19 s) progress UI on the Stations screen; ensembles found vs services.
