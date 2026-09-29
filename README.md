# HELIOS

**The omnipotent builder.** One Android app that unifies every AI socket —
API keys, logins, open-source models, offline and online — behind a single
router, with a learning memory layer, full phone-operating autonomy with a
real kill switch, and an AI-assisted NES game maker as its first skill module.

> Sideload-first. The Play Store bans autonomous agents, so Helios ships as a
> signed APK you install yourself. Package `com.omni.app`, launcher label
> **Helios**.

Punk-rock zine aesthetic throughout: cut-and-paste, photocopied, high-voltage
collage. Four switchable skins — acid neon, beige collage, ransom note,
minimal grunge.

## Status

| Item | State |
|---|---|
| Latest build | v8 — `versionCode` 7, `0.7.0-helios` (see Releases) |
| Socket registry | 25/25 tests green |
| Vision frame pipeline | 13/13 tests green |
| Memory core | 41/41 tests green |
| NES validator | 103/103 tests green |
| Socket-router stress | 200,000 coordinated iterations, zero failures (caught 2 real concurrency bugs) |

On-device verification (Moto) is the ground truth for feel, thermals, and
real provider routing — agent-side QA covers logic and rendering.

## Repo layout

```
helios/          Pure-Kotlin modules: socket registry, vision frame pipeline,
                 memory core (each with tests)
omni/            The Android app: Compose UI (13 screens), socket board,
                 memory, eyes/vision, hub, safety, game maker, CHINASCAR egg
nes/             NES toolkit: 6502 CPU, approximate 2C02 PPU, iNES ROM
                 assembler/disassembler, validator, AI game-generation loop
stress/          Destructive QA harnesses (socket churn, memory, NES, vision,
                 claim-ledger fuzz) — these caught real thread-safety bugs
apk-build/       The zero-Gradle build pipeline: kotlinc → D8 → AAPT2 →
                 zipalign → apksigner scripts + build manifests
icon-work/       Launcher icon generator (icons regenerate from make_icon.py)
editor/          Standalone NES tile editor (single HTML file)
docs/            Architecture, socket checklist, build pipeline notes
scripts/         Toolchain bootstrap (fetch-toolchain.sh)
```

## Build it

Gradle is not required. The pipeline is `kotlinc` + Compose compiler plugin →
D8 → AAPT2 → zipalign → apksigner, driven by the scripts in `apk-build/`.

```bash
# 1. Fetch the toolchain: Temurin JDK 17, Android SDK
#    (build-tools 36.0.0, android-36), Kotlin 2.0.21 compiler
./scripts/fetch-toolchain.sh
source toolchain/env.sh   # or export JAVA_HOME/ANDROID_HOME yourself

# 2. Regenerate launcher icons (needs Python + Pillow)
python3 icon-work/make_icon.py

# 3. Decode the proof ROM artifacts (base64 → .nes)
for f in nes/roms/*.nes.b64; do base64 -d "$f" > "${f%.b64}"; done

# 4. Compile + assemble (see docs/build-pipeline.md for the full sequence)
./apk-build/compile-v8.sh
./apk-build/assemble-v8.sh
```

The APK is signed with a debug key. For your own releases, swap in your
keystore in `assemble-*.sh`.

## Honest boundaries

- An installed APK **cannot rewrite and re-sign itself**. Live
  self-modification happens in a hot-reloadable scripting/plugin layer;
  native changes ship in the next signed build you make.
- **Eyes** (screen capture) needs OS consent every boot; `FLAG_SECURE`
  windows are a blackout by design and cannot be bypassed.
- The NES maker does **guided generation + export**, mapper 0, with an
  honest template fallback labeled as such when no socket key is present.
- Emulator/play-your-backup path: you supply your own dumped BIOS and your
  own game backups. No piracy facilitation, ever.
- GPL components stay as separate user-installed plugins/sidecars — never
  compiled into this APK. Every socket carries its license and requires
  explicit per-socket, per-version acceptance at load time.

## Sockets

Each socket supports **N labeled key instances** with round-robin routing,
failover, 429 rotation, and per-instance spend attribution. Keys live in the
Android vault, masked, never in logs. See `docs/helios-socket-checklist.md`
for the full key-source list (API keys, OAuth where genuinely supported,
offline models, custom endpoints).

## License

All rights reserved for now — license terms are being finalized. The open
source below is a catalog of *supported* tools, not a grant: every socket's
own license applies when you load it. Third-party components keep their own
licenses (see each socket's terms at load time).
