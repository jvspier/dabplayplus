# Design notes (input for the Android design session)

Design reference: the Claude Design export (`design_handoff_radio_head_unit/`, kept locally, not in the repo: layouts 1a "tiles + mini player" and
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
  Edit favourites, Download station logos (when online), Follow station when the signal drops. Later: traffic announcements.
- **Clock** (owner, 2026-09-27): Settings → Clock System / 12h / 24h; System follows Android's setting. Applies to the
  rail clock and the Recent times.
- **Logo download is automatic** (owner asked how people would know, 2026-09-27); the Settings row says so.
  **Plus control** (owner, 2026-10-18: "92 of 160" alone felt like zero control): a "Station logos" row under it with
  what happened ("92 of 160 have a logo · 68 not found online", "Looking up logos… 12 of 68", "No internet now …")
  and two buttons, **Retry missing** (the stations without a logo, now) and **Refresh all** (every station again; a
  logo stays until a new one replaces it). A lookup that went unanswered (timeout, hotspot gone) isn't a "not found":
  it's tried again next pass; a real "not found" after 3 days.
- **Reorder favourites by dragging** (owner, 2026-09-27): long-press a Home tile, drag it to another slot, drop.
  Head units have no vibration motor, so the pickup is visual and subtle: a small springy pop (scale 1.05) with a
  soft shadow; the other tiles dim to 82 %. Settings → Edit favourites keeps big up/down buttons as the alternative.
- **Resolution:** design baseline 1280×720 (the owner's Dudu3), but it must scale properly to other head units
  (2000×1200 etc.): the app is intended for the **Play Store** eventually.

- **Favourites: no hard limit of 8** (owner, 2026-09-25). The first 8 fit one screen (4×2, no scrolling for
  normal use); more favourites scroll vertically with row snapping. Soft cap 24. Skip back/forward cycles
  through all favourites.
- **FM is dropped from the plan** (owner, 2026-09-25): no FM in the app. Tested on the DTR-100: its RF front end passes
  almost no 88–108 MHz and no FM audio reaches USB; the head unit's own tuner has no standard Android API. The test
  results stay in `findings.md` (2026-09-25) for reference.
- **Dongle support: pluggable tuner drivers** behind one interface; the DAB stack (FIC, audio, PAD, UI) is
  shared. Launch with the 16c0:05dc family fully supported (port the firmware ≤1 and ≥5 code paths from the
  decompile too); other families (AP8224/FC8080 mass-storage, Siano, maybe RTL-SDR later) are "experimental"
  until confirmed by users. In-app **diagnostics export** for users with untested dongles (planned). A research round on
  existing dongle families comes first. (2026-10-04: C100 Dual Antenna, Si4684 and Siano are implemented and
  untested; the README's dongle table is the current list.)
- **Legal:** The owner accepts the risk of shipping the vendor-derived register tables (their call, 2026-09-25).

- **App name: DABplay+** (owner, 2026-09-25). No existing Play Store app or GitHub repo with that name
  (checked 2026-09-25); an EUIPO trademark search is still to do before publishing.
- **MP2 (DAB classic) is supported** (owner, 2026-09-25) for DAB users worldwide: an own Layer II decoder in
  Kotlin (port of a permissively licensed decoder such as kjmp2, licence to verify, or written from the spec),
  tested against mpg123/ffmpeg; the vendor APK's `assets/dab.bin` is an MP2 test file.

## Decided later (2026-09-27 onwards)
- Player states map to DAB states: "Tuning…", "No signal · waiting for reception" / "…searching other multiplexes"
  (amber), live signal bars (0–4) for weak reception, "DAB dongle not connected" (amber), real problems in red.
- Scan progress on the Stations screen: the channel being scanned on the Scan button; the list fills afterwards.
- **v1 scope additions/removals** (owner, 2026-09-27): MP2 (DAB classic) decoding is a day-1 feature for
  distribution; the manual station-logo override is dropped. Reception loss handling and "Follow station" followed
  the same week.
- **Rail: icons only, in both layouts** (owner, 2026-09-28): the handoff's labelled, filled rail for 1b is dropped;
  1b uses the same 120 dp icon rail as 1a.
- **Now playing, v2** (owner's mockup and iterations, 2026-09-28): the slide or logo stands centred up top, sharp and
  whole, with a soft orange glow; its blurred reflection fades out below it (over 110 dp; fainter under slides than
  under logos, fainter by day); the "Artist – Title" line (one font and size, scrolls when long, subtle shadow) sits
  under it. No card and no glass over the picture: slides keep their own text strips visible. A slide stands ≈1.45×
  its native 320×240 (464×348 at 720p; 2× looked soft), a logo at 62 % of the stage height; both on the same floor.
  The blur is our own (a shrunk, box-blurred copy drawn with bilinear filtering): Compose's blur needs Android 12.
- **Startup shows the last-played station** (2026-09-28): logo and name at once, "DAB dongle not connected" (amber)
  until the dongle is ready, instead of an empty player.
- **Now playing controls** (owner, 2026-09-28): only previous / play / next, centred as a symmetric group; the favourite
  heart (48 dp) sits in the opposite top corner, level with the station chip and inset alike: symmetric, and on the
  rail's (= driver's) side, closest to hand. Mirrors with the driver side.
- **"Download station logos" off = initials everywhere** (2026-09-28): matches its Settings line; the downloaded logos
  stay cached, so switching it back on shows them again at once.
- **README screenshots** (owner, 2026-09-28): station logos may show (they only identify the stations; the README says
  they belong to their broadcasters); no slides with album covers or people, and no presenter names.
- **Now playing tab: a play triangle** (owner, 2026-10-18): someone new to the app didn't read the waveform as "now
  playing". The animated waveform stays where it marks the station playing (lists, tiles, Recent).
- **Rail icons in their tab colours in both themes** (owner, 2026-09-28); by day 22 % darker, so amber and green read
  on white.
- **Calls and navigation** (owner, 2026-09-28): a phone call / voice assistant (transient focus loss) silences the
  radio, which keeps receiving so it's live again at once; a navigation prompt ducks it to 25 %; both fade (~120 ms).
  Our own ducking (setWillPauseWhenDucked), so it's the same on every head unit. [verified, emulator `gsm call`]
  silence and restore; the duck path is untested (no nav prompt in the emulator).
- **Station logo as the media session's artwork** when there's no slide (head unit launchers' media widgets show it).
- **Traffic announcements** (owner, 2026-09-28): Settings switch, **off by default**. On: while listening, the engine
  follows FIG 0/18 (the station's announcement clusters) and FIG 0/19 (what a cluster announces now, on which
  sub-channel) and switches to the announcing sub-channel on the same multiplex, back when it ends (ASw 0, or no
  longer repeated for 3 s). Banner over any screen: "Traffic announcement · <station>" with Skip (amber); alarm
  announcements always come through (red, no Skip). "TA" badge next to the signal bars when the setting is on: lit
  when the station takes part, amber during an announcement. [verified] 11C and 5B signal traffic support (ASu 0x0002),
  the badge lights live on 538; a real announcement switch is untested (none happened during testing).

- **Back never leaves the app by accident** (owner, 2026-10-01): Back on another tab goes to Home; on Home it does
  nothing. Leaving is the head unit's home button. Reason: the system bar slides in over the mini player after a swipe
  from the bottom edge, so a stray tap on its Back sent the radio to the launcher. Edit favorites keeps its own Back.

- **Start with the car** (owner, 2026-10-01): a setting, **off by default** (many drivers start with CarPlay / Android
  Auto). Off: Android's "dongle appeared" (the "always open" tick in the USB prompt) lands in an invisible activity in its
  own task, which opens the radio only when it is already on screen; nothing comes forward, nothing plays (2026-10-06,
  after the review found that closing/sending back the main activity let a live app flash to the front and play). On: a boot listener opens the
  app; Android 10+ only allows that with "Display over other apps", so the Settings row says so and opens that screen
  until it is allowed. [verified] Android 10 and 14 emulators: opens itself after a reboot with the permission
  (BAL_ALLOW_SAW_PERMISSION), blocked without it; the setting off: nothing opens.
  Caveat [verified, emulator]: Android forgets USB permission at a cold boot and does not announce a dongle that was
  there before boot finished, so the first open after a cold boot shows the USB prompt. Head units that sleep instead
  of shutting down keep it; to check on the Dudu3.

- **Going public** (owner, 2026-10-04): no history rewrite. The old design export and mockups may stay in git history,
  and the Co-Authored-By trailers stay (it is how the app was made). The vendor-derived register tables stay in
  (reconfirms 2026-09-25). Distribution: APKs via GitHub Releases after 1-2 weeks of Dudu3 use, one release key.
- **Website** (owner, 2026-10-18): one page on GitHub Pages in the app's night theme (draft "A · Night drive" of three),
  plus the privacy page Google Play needs. `site/` + `site/build.sh` (assembles from the repo's screenshots, icon, font and
  social preview; fills in the version), published by `.github/workflows/pages.yml`. Loads nothing from elsewhere: the
  Phosphor icons are inline, the font is the app's; no cookies, no analytics.
- **Backgrounds** (owner, 2026-10-19, for other users' taste; no redesign): Settings → Background under Display, four
  swatches: Dusk (the original), Ocean, Ember, Aurora. One choice covers day and night (each has a light and a dark
  version; Ember by day is the rose one). Separate switch **Pure black at night**, off by default: black, no glows, a
  neutral side panel, whatever the background. Only the page background, the Now playing glow, the side panel's
  gradient and the A–Z headers change; tiles, text, icons, accents and the playing tile stay. `dabColors()` in
  ui/theme/Tokens.kt (unit-tested).
- **Diagnostics log in Settings** (2026-10-20, after issue #1): Save (Storage Access Framework: Downloads, a USB stick)
  and Share (share sheet), because head units often have no mail app and Android 11+ hides the log folder without adb.
  The file lists every USB device connected at that moment, so reports about unknown dongles carry their id. Still no
  song titles. GitHub has a "Dongle report" issue form; README and website link to it.
