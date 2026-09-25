# Handoff: Radio streaming app for Android head units

## Overview
A radio streaming app for Android car head units (landscape, 1280×720). It's built for use while driving, in the style of CarPlay: big touch targets, very little text, and a few strong colours that mark the important controls. Screens: Home (favourites), Stations (all stations), Now playing, Recent, Settings. There's a Night (dark) theme, which is the default, and a Day (light) theme.

## About the design files
The files in this bundle are **design references built in HTML**. They show the intended look and layout; they are not production code. Rebuild them in the app's existing Android stack (Jetpack Compose is recommended if nothing is set up yet; Android Automotive / Android Auto templates are an option if you're targeting those platforms), using its own patterns. Open `Radio Head Unit v2.dc.html` in a browser to view them. It contains two layout options side by side:
- **1a — Tiles first + mini player bar**: a left rail, the content area, and a mini player bar at the bottom.
- **1b — Docked player panel**: a left rail, a player panel (400px), and the content area. Now playing uses the full width.

Pick one layout; they share the same tokens and components. Theme switching lives in the root element's props (`displayMode: "Night" | "Day"`); in the HTML the theme is applied through the `data-theme="dark|light"` CSS variables.

## Fidelity
**High fidelity.** Colours, sizes, radii and type are final. Recreate them closely, scaled from 1280×720 (a 1dp : 1px baseline on a 1280×720 mdpi head unit).

## Global layout
- Canvas 1280×720, landscape only. Screen corners are rounded 14px in the mock only (that's the device bezel); don't round them in the app.
- Font: Inter (system-ui fallback). Headings use weight 500; nothing above 600.
- Minimum touch target: 72px. Primary controls are 80–136px.
- Minimum text size: 14px (labels), with 17–28px for content.

### Left navigation rail
- **1a:** 120px wide, transparent over the page background. A clock at the top (24px/500). Five square buttons, 92×92, radius 14, gap 14, icons 42px, **icon only**. There's a 1px vertical divider on the right edge that fades out over 48px at each end.
- **1b:** 108px wide, filled with `rail` (dark #12131F / light #FFFFFF). Five buttons, 88×96, radius 14, gap 10: a 38px icon above a 15px/500 label.
- Five tabs and their colours: Home `#4C8DFF` (ph-house), Stations `#FFB547` (ph-radio), Playing `#FF6A3D` (ph-waveform), Recent `#34D27B` (ph-clock-counter-clockwise), Settings `#A99CF0` (ph-gear-six). All icons use the Phosphor *fill* weight.
- **Active tab:** filled with its tab colour, icon/label `#14151F`, shadow `0 10px 28px -10px <tabColour>`.
- **Inactive tab:** transparent. Dark theme: icon in the tab colour at 70% alpha (`<hex>b3`). Light theme: neutral-500.

### Play/pause button (the main action; the same style everywhere)
- A circle filled with `linear-gradient(145deg, #FF8A4C, #FF5A36)`, icon `#FFF5F0`, shadow `0 10px 32px -8px rgba(255,90,54,.75)`.
- Sizes: 136 (1a Now playing), 128 (1b Now playing), 112 (1b panel), 80 (1a mini bar). Icon ≈ 44% of the diameter.
- Skip back/forward sit next to it: rounded squares, radius 14–16, filled with `surface` (1b panel: the page background at 50%), neutral-200 icon.

### Colour roles (keep each to its job)
- Orange `#FF6A3D`: play/pause, the station currently playing, the waveform, play buttons in lists (list play button: a 72px circle, background `rgba(255,106,61,.16)`, orange icon).
- Pink `#FF4D8A`: favourites / hearts. Filled = favourite; outlined in neutral-500 = not a favourite. Now-playing heart button background `rgba(255,77,138,.14)`.
- Red `#FF3B4E`: the LIVE badge (padding 3×8, radius 5, text `#FFF5F5` 13px/600, letter-spacing .08em).
- Amber `#FFB547`: the Display setting. Selected segment text `#2A1A00`.
- Green `#34D27B`: stream quality, and switches when on. Selected segment text `#062312`.
- Blue `#4C8DFF`: the Resume setting icon.
- Setting-row icon tile: 64×64 (1b: 44×44), radius 14, icon in the role colour, background in the role colour at 14% alpha (`<hex>24`).

## Screens

### Home — Favourites
- Title "Favorites", 34px/500 (1b: 30px), letter-spacing −0.015em.
- **1a:** a grid of 3×2 (or 4×2, a tweak) tiles, gap 16, rows fill the height. Each tile: radius 14, padding 18, `surface` background, `shadow-sm`. A logo square at the top left (104px; 80px in the 4-column grid, radius 10). Name 26px/500, genre 18px neutral-400. A heart at the top right (30px pink), with the orange waveform next to it on the station that's playing.
- **1b:** a 2-column list-tile grid, 3 rows, gap 14. Each tile: a 96px logo, name 22px/500, genre 16px, and a heart plus waveform in a column on the right (26px).
- **Station that's playing:** background `live-tile` (dark `linear-gradient(150deg,#4A2A3C,#2B2135)` / light `linear-gradient(150deg,#FFE7DC,#FFF6F1)`), ring `inset 0 0 0 2px #FF6A3D` plus `0 12px 36px -12px rgba(255,106,61,.6)`, genre text in `live-sub` (dark #FF9A73 / light #C8431A).
- **1a mini player bar:** 104px high, radius 14, `surface`, padding 12. An 80px logo; station name 24px/500; "Artist · Title" 18px neutral-400 (single line, truncated with an ellipsis); then skip back, play/pause and skip forward at 80px.

### Now playing
- A large artwork square on the left: 420 (1a) / 460 (1b), radius 18, shadow `0 0 0 1px neutral-800, 0 24px 80px -16px rgba(255,106,61,.35)`.
- **Artwork rule:** show the cover art from the stream metadata if it exists; otherwise show the station logo in the same square.
- To the right: a 48px station logo, the station name (20px/500), then the LIVE badge and genre (14px uppercase neutral-300). Then the **track title** at 50–52px/500 (letter-spacing −0.02em, line-height 1.05, wraps nicely), the **artist** at 28px neutral-300, and then the controls row 36–40px lower: skip back, play/pause, skip forward and the heart.
- Background: `radial-gradient(ellipse at 20% 35%, np, bg 62%)`.

### Stations (all stations)
- Header: the title "All stations" (34/30px, weight 500), the station count (20px, neutral-500), and a **Scan** button on the right: 64px high, radius 14, fill `#4C8DFF`, text `#0B1733` 20px/600, a 28px ph-broadcast icon. While scanning it reads "Scanning…" with a spinning ph-circle-notch icon.
- The list is grouped by first letter (numbers go under "#"). **Sticky letter headers**: 40px high, 18px/600, colour `az`, background `sticky` with a 12px backdrop blur, radius 10.
- Rows: 84px high. A 60px logo on the left (radius 10). If there's no logo, show a tile with the station's initials in its palette colour at 18% alpha (22px/600). Name 24px/500 (single line, truncated with an ellipsis), genre 17px neutral-400. The orange waveform shows on the station that's playing, and that station's name is in `live-sub`. A 64px heart tap target sits on the right. Dividers are 1px, start after the logo, and fade out at the right.
- **Scroll effect:** the bottom 64px of the list fades out (mask gradient), plus momentum scrolling with overscroll contained.
- **Swipe right to favourite:** the row follows your finger (max 200px) and a pink `#FF4D8A` strip appears underneath with a heart and "Favorite" (or a broken heart and "Remove"). Letting go past 120px toggles the favourite; the row springs back over 300ms (cubic-bezier(.2,.8,.2,1)). The swipe only starts after more than 10px of horizontal movement, and only when the gesture is mostly horizontal, so vertical scrolling still works.
- **A–Z scrubber:** a 52px wide pill on the right edge, `surface`, radius 26, 27 slots (# plus A–Z) spread over the full height, 15px. Letters with no stations are neutral-700; the current letter is bold and in `az`. Touching or dragging jumps to the nearest letter that has stations and shows a 120×120 amber bubble (`#FFB547`, radius 28, a 64px/600 letter) to the left of the scrubber. When you scroll the list normally, the current letter follows along.
- Tokens: `az` dark #FFB547 / light #B86E00; `sticky` dark rgba(22,24,38,.82) / light rgba(243,242,247,.86).

### Recent
- Rows: 96px high (1b: rows share the height evenly), radius 14, `surface`, gap 12. A 72–80px logo; name 24–26px/500; timestamp 17–18px neutral-400 ("Playing now", "Today, 08:14", "Yesterday, 17:40", "Monday"). Then the heart toggle and the orange play button.

### Settings
- Rows: `surface`, radius 14, each starting with a coloured icon tile.
  1. **Display**: a segmented control of Day / Auto / Night (each option 120×64, radius 9, track `bg` with 6px padding). Selected = amber fill.
  2. **Stream quality** ("Lower saves mobile data"): Low / Normal / High. Selected = green fill.
  3. **Resume last station on start**: a switch, 96×52, green track when on, 40px knob `#FFF5F0`.
  4. **Edit favorites**: a caret to open it (1a only).

## Themes (design tokens)
| Token | Night (dark, default) | Day (light) |
|---|---|---|
| bg | #161826 | #F3F2F7 |
| surface | #232532 | #FFFFFF |
| text | #E9E9ED | #1B1C28 |
| rail (1b) | #12131F | #FFFFFF |
| neutral-200/300/400/500 | #E4E7F5 / #CFD3E5 / #B2B6CA / #9397AB | #3F424D / #4A4E5C / #5D6170 / #6F7385 |
| neutral-800 | #3F424D | #DFE2EE |
| page glow g1 / g2 | #3D2244 / #232846 | #FFDCCB / #E4E6FF |
| panel (1b) | linear-gradient(165deg,#43264A,#2C2239 50%,#1C1E2C) | linear-gradient(165deg,#FFE3D5,#F7E8F3 55%,#FFFFFF) |
| np glow | #43264A | #FFDCCB |
| shadow-sm | 0 0 0 1px #3F424D | 0 1px 2px rgba(40,30,70,.06), 0 6px 18px rgba(40,30,70,.06) |
| shadow-md | 0 0 0 1px #595D6C, 0 6px 18px rgba(0,0,0,.55) | 0 2px 6px rgba(40,30,70,.08), 0 18px 48px rgba(40,30,70,.12) |

Page background (1a): `radial-gradient(ellipse at 90% 115%, g1, transparent 55%), radial-gradient(ellipse at 20% -10%, g2, bg 60%)`.
The accent colours are the same in both themes. Radii: 8 / 9 / 10 / 14 / 16 / 18, plus circles. Full token sheet: `_ds/.../styles.css` (Nocturne design system).
The Settings "Auto" option should follow the head unit's day/night signal (headlights or ambient light sensor) where it's available.

## Interactions & state
- `currentTab`: home | stations | now | recent | settings. Tapping a rail button switches tab instantly; no page transitions are needed. A short 150ms fade is fine.
- `player`: { station, isPlaying, buffering, track { artist, title, coverUrl? } }. Skip back/forward moves to the previous/next station in the favourites order.
- `favorites`: an ordered list of station IDs. The heart toggles membership everywhere.
- `recent`: a list of { stationId, lastPlayedAt }.
- `settings`: { displayMode: day|auto|night, quality: low|normal|high, resumeOnStart: bool }.
- Tapping a station tile or row starts that station straight away and keeps you on the current screen. The mini bar or panel updates.
- Press state: scale 0.96, plus the surface one step lighter or darker. There are no hover states (touch only).
- Loading: while the stream is buffering, show a spinner ring around the play button. If a stream fails, show an inline "Station unavailable" in the player area with a retry button.
- Artist/title update live from the ICY/stream metadata; cross-fade the artwork over 300ms when it changes.

## Stations screen (screens "1a Stations" / "1b Stations" in the HTML)
- Header: "All stations" (34/30px, 500) with the count ("46 stations", 20px neutral-500) next to it. A **Scan** button on the right: 64px tall, radius 14, background `#4C8DFF`, text `#0B1733` 20px/600, ph-broadcast icon. While scanning it shows "Scanning…" and a spinning ph-circle-notch.
- The list is plain rows, 84px tall, radius 14: a 60px logo on the left (radius 10; if there's no logo, a coloured initial tile), the station name (orange `live-sub` for the station that's playing), and a heart tap target on the right. Rows are grouped by first letter under small letter headers ("#" for names starting with a digit).
- Scroll effect: the top and bottom of the list fade out through a gradient mask; no scrollbar; `overscroll-behavior: contain`.
- **Swipe to favourite:** drag a row right. A pink (`#FF4D8A`) panel appears behind it with a heart and "Favorite" (or heart-break and "Remove" if it's already a favourite). Past 120px the panel brightens; releasing past that point toggles the favourite, and the row snaps back with `.3s cubic-bezier(.2,.8,.2,1)`. A drag counts as a swipe only when it's mostly horizontal; otherwise the list scrolls.
- **A–Z index:** a 52px-wide pill on the right edge of the list (radius 26), with 27 entries (# and A–Z) spaced evenly to fill the pill's height. Letters that have no stations are dimmed (neutral-700); the current letter is bold and highlighted. Touching or dragging along the pill jumps the list to the nearest letter that has stations, and shows a 120×120 letter bubble (radius 28) to the left of the pill while dragging. For the production app, consider 13 grouped entries (A·C·E…) on smaller screens so each hit target stays ≥ 32px.

## Assets
- Icons: Phosphor Icons (https://phosphoricons.com), fill weight for navigation and controls. The Android version is `com.adamglin:phosphor-icon` for Compose, or use SVGs.
- Station logos and cover art: fetched at runtime (logos from the web, cover art from the stream metadata). In the mocks these are the `image-slot` placeholders.
- Station names in the mock ("Coastline FM", "Metro 96.4", etc.) are placeholders.

## Files
- `Radio Head Unit v2.dc.html`: the design (layouts 1a and 1b, every screen, both themes via the `displayMode` prop).
- `support.js`, `image-slot.js`, `_ds/…`: needed to open the HTML in a browser.## Assets
- Icons: Phosphor Icons (https://phosphoricons.com), fill weight for navigation and controls. The Android version is `com.adamglin:phosphor-icon` for Compose, or use SVGs.
- Station logos and cover art: fetched at runtime (logos from the web, cover art from the stream metadata). In the mocks these are the `image-slot` placeholders.
- Station names in the mock ("Coastline FM", "Metro 96.4", etc.) are placeholders.

## Files
- `Radio Head Unit v2.dc.html`: the design (layouts 1a and 1b, every screen, both themes via the `displayMode` prop).
- `support.js`, `image-slot.js`, `_ds/…`: needed to open the HTML in a browser.
