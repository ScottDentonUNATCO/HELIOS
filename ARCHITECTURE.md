# OMNI — the super app
*Working title. "The VLC of AI apps." One Android app, every AI socket, online + offline, phone-operating autonomy, kill switch, and a memory that learns how the world actually works.*

Captured 2026-09-20 from Scott's brief. Research crews: provider/routing, on-device runtimes, Android autonomy — all landed 2026-09-20.

## The honest shape

**Sockets (the easy part, relatively).** Nearly every AI provider now speaks the OpenAI `/v1/chat/completions` wire protocol — one HTTP client shape (`base_url + api_key + model`) covers OpenAI, Anthropic-via-adapter, Gemini, DeepSeek, Groq, Ollama, llama.cpp server, and hundreds more. Adding a provider = adding a config row, not writing code. Keys live in Android Keystore (EncryptedSharedPreferences, StrongBox-backed). Routing logic ports from LiteLLM's design: latency/cost/usage strategies, fallback chains, cooldowns for sick providers, per-key spend budgets. Study RikkaHub's Android architecture (ProviderManager factory, Room-backed Memory) for patterns — AGPL, so patterns only, no pasted code.

**Offline brain (honest numbers for the Moto g stylus 2025).** llama.cpp via a Maven AAR, no NDK build needed. Realistic: 1–3B Q4_K_M GGUFs at ~8–14 tok/s (1B) / 3–8 tok/s (3B) on CPU. On-device vision: SmolVLM2 / Qwen2.5-VL-3B for single-image Q&A. Voice: whisper.cpp + Piper, both proven offline on phones. On-device image gen (SD 1.5) is a stretch goal needing NPU work — minutes per image on CPU. **On-device video generation is off the table for this phone in 2026** — cloud or PC offload only. Gemini Nano is not available on this device. Termux+Ollama is a dev toy, not a shippable backend.

**Phone autonomy (buildable, with one big caveat).** AccessibilityService gives hands and eyes: tap/swipe/pinch, read the UI tree, per-step screenshots, set-text, Back/Home/Recents. Launcher replacement is a manifest declaration. Talk-while-working: MediaProjection stream + TTS narration + Vosk wake word, all proven patterns. **The caveat: Google Play banned autonomous agents in Oct 2025** — any app that "autonomously initiates, plans, and executes actions" is prohibited. So: sideload-first APK (Scott already sideloads), Play flavor ships without the agent. Open-source projects (Nexior, Hermes, Aura) already use this exact flavor split. Shizuku (wireless-debugging privilege escalation, no root) unlocks system-level extras for power users. Android 16/17 Advanced Protection Mode can revoke accessibility access — the app must detect and degrade gracefully.

**Kill switch (only the out-of-band kind is real).** A stop button the agent interprets is not a kill switch (there's a documented incident of an agent ignoring "STOP" typed into chat). Real design: persistent notification Stop action + floating overlay button that kills the orchestrator loop directly and cancels in-flight gestures — never routed through the agent. Plus confirm-gates for irreversible actions and automatic step/time budgets that halt the agent without a human noticing.

**Memory / anti-hallucination (the genuinely hard part — the real research).** Nothing surveyed does shared checks-and-balances memory. The pattern that works: every skill gets a **"hardware truth" validator** — an external check the model can't argue with. The NES module's validator (does this 6502 assemble? does it respect NROM limits? does it boot in the emulator?) is the template for everything: physics checks, real-world tests, cross-model verification. Local layer: Room event log of observations → actions → outcomes = the learning loop. Shared/decentralized layer: later, once the local loop proves itself.

## Module 0: the NES game maker (first skill)

Scott's existing AI-assisted NES game maker plan slots in as the super app's first skill module — it proves the whole pattern: prompt → generation (any provider, routed) → hardware-truth validation (6502 assembler check, NROM limits, emulator boot test) → playtest → learn. The emulator core is still the long pole (clean-room, multi-week). The skill framework built for NES then generalizes: N64, PlayStation, and beyond.

## Phased build

- **Phase 0 — Scaffold.** Kotlin project, `AiGateway` module (provider registry, router, Keystore vault), one provider working end-to-end, minimal chat UI. Proves the socket board.
- **Phase 1 — NES skill.** Generation pipeline + validator + web prototype of editors; emulator core begins (the long pole, runs in parallel).
- **Phase 2 — Offline + voice.** llama.cpp backend as just another provider entry, cloud-fallback routing, whisper/Piper voice, talk-while-working.
- **Phase 3 — Autonomy.** AccessibilityService portal, launcher mode, kill switch, Shizuku extras. Sideload flavor.
- **Phase 4 — Memory.** Local learning loop → shared memory design, more modalities (image adapters per family — no clean standard like chat).

## Distribution

Sideload APK first and always for the full-power build. A neutered Play flavor (no autonomous agent) only if/when wanted. No Play review roulette for the real thing.

## Open questions for Scott

1. Name — OMNI is a working title. His call.
2. Sideload-first confirmed? (He already sideloads his APKs — assumed yes.)
3. Start with Phase 0 scaffold now?
