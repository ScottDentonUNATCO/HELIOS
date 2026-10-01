# Socket / plugin expansion report — 2026-10-01

Track: socket & plugin expansion (sibling track owns router integration —
`omni/gateway/*.kt` core, `SocketStore`, `SocketBoardViewModel`,
`AndroidVault`, `ChatViewModel`, `HubViewModel`, `MainActivity` were not
touched except the two gateway vision edits flagged below).

**Build state:** gateway compiles (56 classes), app compiles (629 classes),
**360/360 JVM tests green** (`bash apk-build/run-tests.sh`), including 39 new
tests written for this track. No new AARs. Minify untouched. GPL stays
server-side only.

## Per-socket verdicts

### comfyui — SERVER-DRIVEN, code path tested (needs Scott's server)
- Catalog: `comfyui`, kind CUSTOM, **disabled**, baseUrl placeholder
  `http://YOUR-SERVER:8188`, auth "none".
- New `ComfyUiClient` (pure Kotlin + OkHttp): POST /prompt with workflow JSON
  (validates JSON, surfaces ComfyUI `node_errors` verbatim), GET
  /history/{id} polling (queued/running → null, server error → throw),
  GET /view download with proper query encoding. ComfyUI is GPL-3.0 — it is
  never bundled; the phone is only a remote control.
- Studio tab: `ComfyStudioCard` — when the socket is enabled and a real server
  URL is set, GENERATE genuinely renders (submit → 2s poll/10min timeout →
  download → `filesDir/studio/comfyui/`). Otherwise the honest "NEEDS SERVER"
  label and no generate button. Workflow template ships with a
  `YOUR_CHECKPOINT.safetensors` placeholder and an "edit for your server's
  models" note — nothing is invented about what the server has installed.
- Tests: `ComfyUiClientTest`, 12 tests (mock interceptor backend — the
  sandbox blocks loopback TCP, so the in-process OkHttp interceptor pattern
  from the repo's own `MockOpenAiBackend` docs is used; identical code paths).
- **Scott must supply:** a reachable ComfyUI server URL (his machine or a
  rented GPU box), enable the socket, set the URL in Studio.

### ffmpeg-kit-next — SIDECAR, not bundled (evidence-backed)
1. No published AAR exists: arthenica/ffmpeg-kit-next BUILD.md states
   binaries are "not published to npm and does not download native binaries
   from any remote repository (Maven Central / CocoaPods trunk)"; GitHub
   releases v9.0.0/v8.1.1/v7.1.0 carry zero assets (verified 2026-10-01 via
   the GitHub API).
2. The pipeline couldn't package JNI anyway: `extract_aars.py` extracts only
   classes.jar/res/AndroidManifest/libs/*.jar — no `jni/*.so` handling;
   `assemble.sh`/`relink.sh` have no lib/ step; the app has zero
   `loadLibrary` calls. Dex-clean or not, it would die with
   UnsatisfiedLinkError.
- Catalog: sidecar entry (`installPath="fetchlink:…"`, disabled), notes say a
  user-installed FFmpeg sidecar is required.

### llmedge — SIDECAR (experimental)
- Repo is now `Sapn-AI/llmedge` (Apache-2.0, verified 2026-10-01 via GitHub
  API); not on Maven Central (`ai/sapn/llmedge`, `com/sapn-ai/llmedge` → 404).
- Catalog: sidecar entry, disabled, experimental.

### lmstudio — catalog entry, honestly labeled
- `lmstudio`, kind API_KEY, baseUrl `http://localhost:1234/v1`, no key
  bundled. Enabled only when Scott saves a key.

### llamacpp / whispercpp / piper-tts — HONESTLY-LABELED (runtime not built)
- No JNI in the app and the pipeline can't package `.so` (see ffmpeg-kit
  evidence) — vendoring is impossible today, so these are relabeled, not
  faked: `installPath="not-installed"`, `toolVersion="runtime-not-built"`,
  `enabled=false`, notes say "ON-DEVICE RUNTIME NOT YET BUILT".
- `OfflineModelManager` (new, `offline/`): one-at-a-time policy
  (unload-before-load) + `ModelLoadGate` RAM policy (≤50% of total RAM per
  model, ≥512 MB free afterwards) enforced TODAY, behind the
  `OfflineRuntime` interface. `NotBuiltRuntime` fails loads honestly. The
  Offline tab shows a "NOT BUILT" runtime card — downloaded model FILES are
  labeled as files, never as runnable.
- Tests: `ModelLoadGateTest`, 5 tests.

### FetchLink — MVP shipped (inspect → license confirm → resumable download)
- New `LinkClassifier` (pure: GITHUB_REPO / HUGGINGFACE_MODEL / DIRECT_FILE /
  DOCS_PAGE / UNKNOWN; **rejects credentialed URLs**), `LinkInspector`
  (GitHub API → code license SPDX + size + archive URL; HF API → weights
  license + GGUF sibling pick; HEAD for direct files → size/type with "NO
  license information" honesty), `FetchLinkDownloader` (Range-resume,
  progress, cancel; publisher `<url>.sha256` → VERIFIED / NOT_PUBLISHED /
  MISMATCH deletes the file and throws).
- App: `FetchLinkSection` on the Offline tab — INSPECT shows kind, code AND
  weights license, size, requirements BEFORE download; download requires the
  license checkbox; on success the file registers as a LOCAL_TOOL socket with
  capability INFERRED from the filename and labeled a guess in the notes.
- Tests: `FetchLinkTest`, 15 tests (classifier + inspector + downloader:
  full/resume/checksum-verified/mismatch/cancel).

### Eyes "Ask about screen" — wired (needs vision socket + key)
- `EyesAskViewModel` (`eyes/`): latest fresh frame (<2s, pipeline running),
  downscaled ≤1024px → JPEG → `VisionImage`; first enabled VISION-capable
  API_KEY/CUSTOM socket with baseUrl; model = `def.model` or the
  `defaultTestModel` mapping; `KillSwitch` checked before sending;
  `actionableGatewayError` on failure. Honest empty states (no capture, no
  vision socket, no key).
- Implementation note: `AiGateway.generate()` has no image parameter and
  AiGateway is sibling-owned, so the ask uses `OpenAiCompatClient` directly
  for the single chosen provider. A future gateway-level images parameter
  (sibling's call) would restore routing/spend-tracking. This required two
  gateway edits: `Models.kt` (`VisionImage`, `ChatRequest.images`) and
  `OpenAiCompatClient.kt` (vision content-parts serialization via okio base64
  on the last user message; minSdk 21 so no `java.util.Base64`).
- Tests: `VisionRequestTest`, 3 tests (data-URI part on last user message,
  multiple images, text-only legacy shape).

### NES one-tap build — shipped
- `checkConsoleContract(plugin)` (pure Kotlin, `gamemaker/core/`): 6
  adversarial checks — READY status (honest label quoted), validate() never
  throws, build/test/export/generate refuse bad input with clear messages.
- `NesMakerViewModel.oneTap()`: contract-gates `NesConsolePlugin()` FIRST
  (new `CONTRACT_CHECK` timeline stage), then the existing pipeline, then
  auto-export + `BuildLabels` stamping (`nes-roms/<slug>.labels.json`: draft
  source, validator pass/fail, fired rules, attempts, repair actions, ROM
  SHA-256). Screen shows the gate card and labels card.
- Tests: `ConsoleContractCheckTest`, 4 tests.

## What Scott must supply (nothing works without these)
- ComfyUI: his server URL + socket enabled.
- Vision asks: a VISION-capable socket enabled with a real saved key.
- On-device inference: nothing to supply — the runtime is not yet built; do
  not expect downloaded models to run.
- Keys: no real keys were used or stored anywhere in this work; all tests use
  dummy keys / mock interceptors.

## Known limitations / follow-ups
- The two gateway vision edits (`Models.kt`, `OpenAiCompatClient.kt`) touch
  sibling-owned files — kept because a wrapper couldn't reach the request
  serializer; the coordinator should reconcile with the router track.
- `AiGateway.generate()` still has no images parameter — EyesAsk bypasses it
  (documented above).
- Enabling the `comfyui` CUSTOM socket also exposes it to chat-provider
  enumeration in MainActivity's `buildGateway`; chat traffic to it would 404
  honestly, but the router track may want to exclude non-CHAT sockets from
  chat routing.
- `SocketRegistryTest.catalog includes offline local tools` was updated to
  check capability coverage rather than enabled-ness, matching the deliberate
  honest-disabled relabeling.
