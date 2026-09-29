# Eyes seam — watching test gameplay (DESIGN ONLY)

**Status:** design doc for a future build. No code here beyond the
references; nothing is wired yet. Track E scope: define the seam so the
game-maker's claims can later carry *dynamic* proof ("the game visibly runs
on this device") alongside today's *static* proof (validator report +
PPU-rendered frames).

## 1. Goal

Close the loop from "the ROM validates" to "the game visibly runs":

- Static proof (already wired via `GameMakerHubBridge`): NesValidator GREEN
  + headless PPU frames hashed on the build machine.
- Dynamic proof (this doc): the generated ROM booted in the on-device test
  player while the eyes service watched the screen, and the pixels provably
  animated.

Both land on the *same* claim in the sibling's ledger, so one claim carries
the full evidence chain: brief → ROM metadata → validator report → rendered
frames → on-device observation.

## 2. Existing pieces (read-only inventory — do not modify)

| Piece | Location | What it gives the seam |
|---|---|---|
| `ScreenCaptureService` | `com.omni.vision` | MediaProjection → VirtualDisplay at **native** metrics (no downscale) → ImageReader RGBA_8888 → `VisionHub.ring`. Persistent-notification STOP kill switch. |
| `FramePipeline` | `com.omni.vision` | `FrameRing(8)` (latest-frame semantics), `FrameDiff.changedFraction` (1:1 per-channel threshold), `FpsMeter`, `Frame.crop(Roi)`. Pure Kotlin, 1:1 pixel truth, no downscaling in the decision path. |
| `VisionConsentActivity` | `com.omni.vision` | OS screen-capture consent; re-requested after every reboot. Cannot be bypassed — the seam must route through it. |
| `EyesViewModel` / `EyesScreen` | `com.omni.app.eyes` | The honest "flowing" check: `VisionHub.capturing &&` latest frame exists `&&` fresher than 2s. Stale frames are not treated as live. |
| Manifest | app | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` already declared; service + consent activity already registered. **No new permissions needed.** |
| `ClaimSink.appendEvidence` | this package | Where the observation lands (see §5). |

Honest limits inherited from the OS (cannot be bypassed): FLAG_SECURE
windows are blacked out by the OS (the test player must NOT be FLAG_SECURE);
sideload-only (Play policy bans autonomous agents via this API).

## 3. Future pieces to build

1. **On-device NES test player (Track B).** Runs the generated ROM's real
   CPU+PPU on device and renders the 256×240 framebuffer into a dedicated
   `GameTestActivity` (SurfaceView/TextureView, integer-scaled, centered).
   This is the thing being watched; it does not exist yet.
2. **`GameWatchSession`** (new; natural home is this package or a
   `gamemaker.test` package). Lifecycle:
   - Started explicitly ("watch test play") AFTER the user grants capture
     consent via `VisionConsentActivity`.
   - Knows the game viewport ROI inside the activity (reported by the
     player; falls back to full frame).
   - Samples `VisionHub.ring.latest()` on a fixed cadence (e.g. every
     500 ms) for a watch window (e.g. 10–15 s), crops each sample to the
     ROI 1:1, and records: per-pair `changedFraction`, FPS from
     `VisionHub.fps`, frame dims, and 2–3 sample crops saved as PNGs under
     the app's filesDir (`gamemaker_evidence/<claimId>/watch-*.png`).
   - Emits an observation verdict (see §4) and appends it to the game's
     claim via `ClaimSink.appendEvidence`.
3. **Trigger/UI.** A "watch test play" affordance on the future game-maker
   screen that starts capture → launches the player → runs the session →
   stops capture. Out of scope for this doc's code, in scope for its
   contract.

## 4. What "the agent sees the game running" means — concretely

A watch session emits one verdict. All four conditions are checkable from
`VisionHub` + `FramePipeline` alone; no vibes:

- **OBSERVED-RUNNING** — (i) the honest flowing check passes for the whole
  window (`capturing &&` fresh frame < 2 s old, same as `EyesViewModel`);
  (ii) a majority of sampled consecutive pairs show
  `changedFraction ≥ 0.005` at per-channel threshold 16 — pixels are
  provably animating, not a frozen frame; (iii) all crops are 1:1 (no
  downscale in the decision path — `FramePipeline` guarantee); (iv) at
  least one crop is non-uniform content (not a solid/backdrop fill —
  guards against "animating but all black").
- **OBSERVED-FROZEN** — (i) holds but (ii) fails: frames flow, nothing moves.
- **OBSERVED-BLACK** — (iv) fails: frames flow but show no game content
  (player crashed to black, or ROM renders nothing on device).
- **OBSERVED-INTERRUPTED** — capture died mid-watch (consent revoked, STOP
  pressed, service killed): verdict carries the reason; never silently
  treated as a pass.

Thresholds (0.5%, 500 ms, 10–15 s) are starting points to be tuned against
real captures, not constants of nature — the doc fixes the *shape* of the
check, not the numbers.

## 5. Where the observation lands

As **evidence on the game's existing claim**, via
`ClaimSink.appendEvidence(claimId, …)`:

```
--- eyes observation (dynamic proof) ---
verdict: OBSERVED-RUNNING
watch: 12.0s, 24 samples, roi=512x480@384,360 (2x integer scale)
changed-fraction: min 0.000 max 0.214 median 0.031 (threshold 0.005)
fps: 58.7 (VisionHub)
frames: gamemaker_evidence/<claimId>/watch-00.png (crc=…), watch-12.png (crc=…)
capture: ScreenCaptureService, consent <timestamp>, 1:1 native frames
```

**Status rules (honest, non-negotiable):**

- An eyes observation NEVER promotes a validator-RED game to VALIDATED.
  A RED game observed running keeps status PROPOSED with "observed running
  on device" appended as evidence — interesting, but the validator is the
  gate and it said no.
- A validator-GREEN (VALIDATED) game observed FROZEN/BLACK keeps VALIDATED
  **and** gets the observation appended. The seam must NOT set FALSIFIED
  unilaterally: in the sibling's model FALSIFIED is a verifier's verdict
  ("the claim is false"), while "won't animate on this device build" is a
  distinct, weaker signal. Conflating them would be dishonest labeling.
- If the sibling later wants a first-class observation flag, that is GAP-4:
  a structured `observation` field on `Claim` plus a public
  `attachObservation(id, …)` API (same shape as GAP-1/GAP-2 in
  `HubLedgerClaimSink.kt`). Until then, evidence text is the record.

## 6. Consent, privacy, safety

- Capture starts only after the OS consent dialog (`VisionConsentActivity`);
  consent is per-reboot. The watch session refuses to start without a
  fresh flowing check.
- The STOP action in the capture notification kills projection immediately;
  a session interrupted this way emits OBSERVED-INTERRUPTED, never a pass.
- Capture is whole-screen: test play runs in a dedicated activity and all
  saved evidence is ROI-cropped to the game viewport, so stored PNGs
  contain the game, not the user's notifications or other apps.
- Evidence PNGs stay in the app's filesDir; they are referenced by path +
  CRC32 in claim evidence and never uploaded anywhere by this track.
- No new permissions: the service, consent activity, and both
  media-projection permissions are already declared (see §2).

## 7. Open gaps / handoff

1. **Track B**: the on-device NES test player does not exist; without it
   there is nothing to watch. It should report its viewport ROI to the
   future session and must not set FLAG_SECURE.
2. **Sibling (Track H)**: GAP-1 (no public status/evidence mutation +
   persist), GAP-2 (no binary attachments), GAP-4 (no structured
   observation field) — all documented in `HubLedgerClaimSink.kt`. The
   evidence-text path works today; the structured path needs sibling API.
3. **Tuning**: the §4 thresholds need calibration against real captures
   once the player exists; record the chosen values in the session output
   so they stay auditable.
4. **Determinism note**: headless PPU frames (static proof) are
   deterministic per ROM (CRC32 hashes); eyes observations are inherently
   non-deterministic (real device, real timing) — the claim should carry
   both, labeled as such, and never let the latter override the former's
   verdict.
