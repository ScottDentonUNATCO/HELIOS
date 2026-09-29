# Chinaskar v1 Easter Egg — Source Provenance

## Which source was used, and why

**Used:** `~/workspace/ts-spaces/chin-nascar/index.html` (the current build's
top-down 2D mode), bundled as `assets/chinaskar-v1/chinaskar-v1.html`.

**Not used:** the earliest audit snapshot
`~/workspace/ts-spaces/chin-nascar/audits/2026-09-16T23-36-21Z-123637581`
(day-one build). That directory contains only QA artifacts —
`act-*.png` / `observe-*.png` screenshots, `screenshot.png`,
`report.json`, `probe-network.json` — and no playable game bundle
(no index.html, no JS). The screenshots confirm the original was the
top-down 2D game (CHIN NASCAR menu, "100 / FAST & FERAL", "420 / MAXIMUM
CHIN", "DROP THE FLAG" over a top-down oval), but there is nothing
executable to resurrect. No other audit directory contained a bundle either.

## Why the current file is still "the original top-down"

The current build keeps the original top-down renderer intact as the
`city` (CITY MAP) camera mode — the code comment at the top-down transform
reads "CITY MAP is the stable tactical overview", and the `render()` dispatch
still routes this mode to the classic `drawTrack()` + 2D car-sprite path,
unchanged in spirit from day one. Later modes (pseudo-3D `chase`,
`2.5d`, orbit, on-foot, etc.) are additive.

## Deltas applied to the bundled copy (asset dir only, original untouched)

1. Default race camera changed from `chase` (newer pseudo-3D) to `city`
   (original top-down) in two places:
   - boot default: `cameraMode='city', lastRaceCamera='city'`
   - race reset path: `cameraMode='city';lastRaceCamera='city'`
   The player can still switch cameras with the in-game VIEW button —
   the toggle genuinely works, per the standing rule.
2. Injected an Easter-egg overlay before `</body>`: a fixed label reading
   **"CHINASKAR v1 — the original top-down. Easter egg."** (top-left,
   non-blocking) and a small ✕ close button (top-right) wired to
   `ChinaskarBridge.closeEgg()`.
3. No network dependencies: the file contains zero external URLs
   (verified via grep — no https:// strings), so it runs fully offline
   from `file:///android_asset`. Options persist via WebView DOM storage
   (enabled in `ChinaskarV1Activity`).
