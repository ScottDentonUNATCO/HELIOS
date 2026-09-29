# Build pipeline

Helios builds with **zero Gradle**. The chain is:

```
kotlinc (+ Compose compiler plugin) → D8 → AAPT2 → zipalign → apksigner
```

Run it all with `./scripts/build.sh`, or step by step below. Every script is
**repo-relative** — clone anywhere, no `$HOME` assumptions.

## Prerequisites

- Linux or macOS, `curl`, `unzip`, `zip`, Python 3 (+ Pillow for icons)
- Everything else is fetched by script into `./toolchain/` (gitignored)

## Steps

### 1. Toolchain — `./scripts/fetch-toolchain.sh`

Installs into `./toolchain/`:

- Temurin JDK 17
- Android SDK: cmdline-tools, `build-tools;36.0.0`, `platforms;android-36`
- Kotlin compiler 2.0.21 (full distribution; `kotlinc/lib` supplies the
  compiler classpath jars)

### 2. Icons — `python3 icon-work/make_icon.py`

Regenerates `apk-build/res/mipmap-*/ic_launcher.png` from the scripted master
icon (glowing neural node-ring). Deterministic — no binary assets in git.

### 3. Dependencies — `./scripts/fetch-deps.sh`

- Downloads from Maven Central into `apk-build/deps-local/`:
  `kotlin-compiler-embeddable`, `kotlin-compose-compiler-plugin-embeddable`,
  `kotlin-serialization-compiler-plugin-embeddable` (all 2.0.21),
  `kotlinx-coroutines-core-jvm`, `kotlinx-serialization-core-jvm`,
  `kotlinx-serialization-json-jvm`, `okhttp`, `okio-jvm`
- Runs `resolve_deps.py` — resolves the transitive AAR closure **without
  Gradle** (Compose BOM 2024.10.00 + pinned roots: activity-compose 1.9.3,
  lifecycle-viewmodel-compose 2.8.6, security-crypto 1.1.0-alpha06) from
  Google Maven / Maven Central into `apk-build/deps/`
- Runs `extract_aars.py` — unzips each AAR into
  `apk-build/extract/<group>__<artifact>__<version>/`
  (`classes.jar`, `res/`, `AndroidManifest.xml`), writes `EXTRACT.tsv` and
  `merged-AndroidManifest.xml` (app manifest + library contributions)

### 4. Resources — `./apk-build/relink.sh`

`aapt2 compile` per res dir (**each into its own output dir** — aapt2 names
flats only by source filename, so sharing one dir lets 40 libraries clobber
each other's `values_values.arsc.flat` and the link fails), then `aapt2 link`
against `android.jar` with the merged manifest, minSdk 28 / targetSdk 36,
app assets staged in. Generates `gen-r/` + `classes-r` (app and library R
classes via `gen_lib_r.py`).

### 5. Gateway — `./apk-build/compile-gateway.sh`

kotlinc (embeddable 2.0.21 + serialization plugin): `omni/gateway` +
`helios/registry/src` → `classes-gateway`.

### 6. App — `./apk-build/compile-app.sh`

kotlinc (embeddable 2.0.21 + Compose plugin):
`omni/app/src/main/java` + `nes/src` + `helios/registry/src` → `classes-app`.
Regenerates `compile-cp.txt` every run (android.jar + classes-gateway +
every AAR `classes.jar` + jvm libs) — never stale.

### 7. Dex — `./apk-build/dex.sh`

Jars up the three class dirs, rebuilds the dex input list with
**duplicate-artifact elimination** (highest version wins per artifact —
this is what bit us with a stale `lifecycle-viewmodel-ktx:2.6.1` shadowing
2.8.6), then `d8 --min-api 28 --lib android.jar`.

### 8. Assemble — `./apk-build/assemble.sh`

Inserts `classes*.dex` into the linked APK, `zipalign`, signs (generates a
fresh `debug.keystore` via keytool if none exists — **not** the v8 release
key; swap in your own for releases), `apksigner verify`, badging dump.

Output: `apk-build/helios-v8-debug.apk`.

## Why not Gradle?

The sandbox this project was born in intercepts local TCP, which breaks the
Gradle daemon. The manual chain is fully deterministic, faster to debug, and
every intermediate is inspectable. Gradle files (`omni/gateway/build.gradle.kts`)
remain as dependency documentation.

## Versioning

`versionCode` / `versionName` live in `omni/app/src/main/AndroidManifest.xml`
(currently `7` / `0.7.0-helios`, label **Helios**, package `com.omni.app` —
the package stays `com.omni.app` so installs upgrade in place).
