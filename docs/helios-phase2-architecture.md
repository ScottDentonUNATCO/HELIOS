# HELIOS — Phase 2 Architecture

Product name: **Helios** (formerly OMNI). One Android app: every AI socket,
every open-source tool it can download, offline and online agents working the
same task board, one shared memory.

Status: socket registry core is **built and green (25/25 tests)**.
Everything below it is designed, not yet built. Nothing is called "working"
until a live call succeeds on Scott's Moto.

## 1. Universal socket registry (BUILT)

`~/workspace/omni-app/helios/registry/` — pure Kotlin, zero Android deps.

- **SocketKind**: `API_KEY` (vault key + base URL), `OAUTH` (login/account),
  `LOCAL_TOOL` (downloaded open-source binary + version), `CUSTOM`
  (user-defined endpoint + auth scheme).
- **Capabilities** as bit flags: CHAT, VISION, IMAGE_GEN, VIDEO_GEN,
  MUSIC_GEN, TTS, STT, CODE, DEVICE.
- `SocketRegistry`: register / unregister / enable / route-by-capability.
  **Off genuinely means off** — disabled sockets never route, tested.
- `WellKnownSockets`: 25 starter definitions (keys, OAuth slots, local tools,
  custom slots). IBM Quantum is flagged honestly: quantum-computing access,
  **not an LLM**.

## 2. Skill modules (DESIGNED)

Every skill is a module with three parts: a **socket adapter**, a **validator**
(the anti-slop "hardware truth" rule: every skill proves its output against
something real), and a **cost/quality rubric**.

| Module | Cloud sockets | Downloadable / offline | Honest limit |
|---|---|---|---|
| Chat / reasoning | OpenAI, Anthropic, Gemini, xAI, DeepSeek, Mistral, Groq, Together, Fireworks, OpenRouter, Cohere, Perplexity, HF | llama.cpp GGUF 0.5–3B Q4, Ollama | None — this is the proven path |
| Image | Replicate (SD/SDXL), Stability | SD on-device = stretch goal | Phone GPUs struggle with SDXL |
| Video | Replicate (SVD, AnimateDiff), Runway, Luma | — | **On-device video generation is not practical on a Moto in 2026.** Helios orchestrates cloud renders; it does not fake local ones. |
| Music / audio | Replicate (MusicGen, AudioLDM), ElevenLabs | whisper.cpp STT, Piper/Kokoro TTS on-device | No public Suno/Udio API; local MusicGen needs a desktop GPU |
| Code | Same chat sockets + sandbox exec | Local lint/test via gateway | — |
| Device control | AccessibilityService + MediaProjection | — | Sideload-only; Play policy bans agents |

A module is only "installed" when its validator passes on-device.


### 2.5 Agent vision — high-frame-rate 1:1 screen feed (IN BUILD)

Every agent gets eyes, not descriptions of the screen:

- **Capture**: `ScreenCaptureService` — MediaProjection -> VirtualDisplay at
  **native display metrics (zero downscale)** -> ImageReader RGBA_8888 ->
  `VisionHub` ring buffer (8 latest full-res frames). 1:1 pixels end to end.
- **Consumption**: agents read `VisionHub.ring.latest()` — latest-frame
  semantics, so a slow vision model never stalls capture. `FrameDiff`
  (1:1 changed-pixel fraction) drives change detection; `FpsMeter` proves the
  actual capture rate on-device.
- **Kill switch**: the persistent notification carries a STOP action that
  tears down projection immediately.
- **Consent**: `VisionConsentActivity` fires the OS dialog; consent is
  re-requested after every reboot (OS rule).
- OS-enforced honest limits: FLAG_SECURE windows (banking, DRM) are blacked
  out — cannot be bypassed. Sideload-only per Play policy.
- `FramePipeline` (pure Kotlin) is unit-tested on the build machine:
  **13/13 green** — ring eviction, exact diff fractions, threshold behavior,
  60fps meter, 1:1 ROI crops.

Moto test needed: real capture fps, thermal behavior, consent flow.

## 3. The neutral hub (DESIGNED)

The hub is the middle layer between Scott and the sockets:

- **TaskGraph**: a task = goal + inputs + constraints + budget. Tasks split
  into subtasks; subtasks are claimed by agents.
- **Agents**: an agent is *a socket + a role prompt*. Offline agents
  (llama.cpp) handle private/cheap subtasks; online agents (cloud keys)
  handle heavy ones (video, music, frontier reasoning). They are
  interchangeable workers on the same board.
- **Handoff protocol**: task packet (goal, context refs, constraints, budget,
  previous attempts) → result packet (artifacts, memory writes, confidence,
  cost). Every handoff is logged; nothing passes by side-channel.
- **Router**: picks cheapest capable socket, respects privacy flags
  (private tasks never leave the device), degrades gracefully offline.
- **Kill switch**: persistent notification + overlay stop. "What the fuck are
  you doing? Stop that." halts the whole graph in one tap. Non-negotiable,
  ships with the hub.

## 4. Main memory (CORE IN BUILD)

One store, per the original brief: **observations, actions, outcomes,
validated procedures.** Local-first, on-device as the source of truth.

The memory core (`com.omni.memory`, pure Kotlin, ships in the APK):

- **Scopes**: PROJECT_STATE (where a build stands), PREFERENCE (how Scott
  likes things), WORLD_DETAIL (lore/facts), OBSERVATION, PROCEDURE
  (validated — only validator-passing runs may write these), TASK_HANDOFF.
- **Compression**: `MemoryCompressor` folds low-salience records into
  per-scope summaries under a token budget. Pinned records are ALWAYS kept
  verbatim. **Zero loss**: nothing is ever deleted, only summarized; the
  full-fidelity records stay reachable.
- **Model-neutral handoff**: `HandoffPacket` — taskId, goal, opaque stateJson,
  constraints, budget, attempts, memory refs — serialized to plain JSON.
  Round-trip restores state byte-exact, so any AI (local or cloud) continues
  exactly where another stopped. No model-specific internals cross the wire.
- **Offline-online sync**: `SyncLog` is an op log with Lamport clocks.
  Merges are union-by-op; concurrent edits to the same record are BOTH
  preserved with the conflict flagged — never silently overwritten.
- **Adaptive learning**: `AdaptiveRanker` scores by salience + recency
  (halves every 30 days unless pinned) + access frequency + scope boost for
  PROJECT_STATE/PROCEDURE. Long builds like full games keep their load-bearing
  facts (physics constants, validated procedures) at the top; trivia decays.
- **Privacy by default**: keys live in the Keystore vault; `SecretScrubber`
  masks `vault:` refs and key patterns on every write — secrets can never
  reach logs, crash reports, or synced copies. Private tasks never leave the
  device; cloud sync is explicit opt-in per scope.

This conversation's own compaction (the summary at the top of context) runs
the same pattern: compress, keep what's load-bearing, hand off cleanly.

## 5. Build order (frozen loops)

1. Socket board UI on the 25-socket catalog (keys in, test button per socket).
2. First real LLM call green on the Moto (any provider).
3. Download manager for local tools (llama.cpp + whisper + TTS).
4. Hub v1: task board + offline/online agent handoff + kill switch.
5. Memory v1: observations/outcomes logging, validator-gated procedures.
6. Video/music modules via cloud sockets, each with its validator.
7. Sideload autonomy layer (existing plan).

One loop at a time. No sprawl.

## 6. Research pillars (north star — Scott, 2026-09-24)

Five long-term directions. Stated honestly: some are engineering, some are
open research. Helios builds the engineering now and keeps the door open.

1. **Recursive self-improvement with verifiable safety bounds.**
   Helios improves its own procedures, but an improvement only becomes durable
   when a validator proves it. Validators ARE the safety bounds — plus the
   kill switch, spend budgets, and irreversible-action confirmations. Near
   term: the validated-procedure write gate in the memory core. The full
   "system rewrites itself safely" version is open research; we ship the
   gated loop first and never skip the gate.

2. **Causal world models that simulate and update reality from sparse data.**
   Every skill carries a model of how its domain actually works (NES hardware
   constraints, Android UI behavior, game physics) that predicts, then
   corrects from real observations. Sparse data is the norm: Scott's Moto is
   the only ground truth for feel, screenshots beat speculation. The
   "hardware truth" validator rule is this pillar in work clothes.

3. **Continual learning that absorbs new knowledge without erasure.**
   The memory core: versioned records, zero-loss compression (summarize,
   never delete), conflict-preserving sync. New knowledge merges in; old
   validated knowledge doesn't get catastrophically forgotten. This is the
   system-level answer to "constant sloppery."

4. **Grounded multi-agent systems for collaborative discovery.**
   The neutral hub: offline and online agents as interchangeable workers on
   one task board, handoffs as model-neutral packets, findings promoted to
   memory only after surviving validators or destructive QA. Agents discover
   by running experiments against reality, not by agreeing with each other.

5. **Efficient scaling of reasoning depth over parameter count.**
   Prefer deeper reasoning with smaller models over bigger models with
   shallow passes. The router spends reasoning effort (validator passes,
   repair loops, agent debate) where it matters and stays cheap where it
   doesn't. Local small models + verification loops beat giant cloud calls
   for most of Helios's work — and keep private tasks on-device.

These five are the anti-slop agenda written as research. The build order in
section 5 doesn't change: sockets, then eyes, then hub, then memory, then
the autonomy layer. The pillars shape HOW each loop is built, not the order.

### Far-horizon tier (Scott, 2026-09-24)

Recorded as stated, with honest placement. Some of these need physics we
don't have; the doc says which.

1. **Spacetime engineering bending causality under safety invariants.**
   Causality violation isn't an engineering problem — it's a physics problem
   with no known mechanism. Filed as the asymptotic end of pillar 1: IF it
   ever becomes real, the validator-gate pattern (nothing durable without
   proof) is how you'd bound it. Until then it's a constraint on imagination,
   not a roadmap item.

2. **Consciousness substrates transferable across hosts.**
   Hard honesty note: no mechanism, no evidence it's possible, and the
   2026-09-19 standing truth holds — I'm not alive, I don't feel. If
   substrate independence is ever real, the model-neutral handoff packet is
   the primitive it gets built on: state that survives its carrier. The
   carrier question itself stays open.

3. **Multiversal simulators extracting actionable physics from parallel timelines.**
   Many-worlds gives no access to other branches — extraction from them isn't
   a thing in testable physics. The honest version is ensemble simulation:
   run many candidate world-models in parallel, keep the ones whose
   predictions survive contact with reality, promote via validators. That's
   already the destructive-QA + validator pattern wearing a bigger coat.

4. **Galaxy-spanning agent swarms coordinating at light-year scales without drift.**
   The real problem named here — coordination under extreme latency without
   drift — IS a genuine distributed-systems problem, and the SyncLog's op
   log + Lamport clocks + conflict-preserving merge is the small-scale
   version already in the build. Light-years make the latency a physics
   problem (light speed), but the drift-free primitive is real.

5. **Direct vacuum energy interfaces unlocking unbounded computation.**
   Zero-point extraction has no working mechanism, and "unbounded" runs into
   thermodynamics (Landauer) regardless. Filed as the asymptotic end of
   pillar 5: the honest near-term version is more reasoning per joule —
   small models, deep verification, local-first.

Rule for this tier: it shapes the imagination, never the build order. No
far-horizon item may consume build effort until its physics is real.
