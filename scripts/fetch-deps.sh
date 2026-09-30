#!/usr/bin/env bash
# fetch-deps.sh — download every build dependency from Maven Central / Google Maven.
# Writes to apk-build/deps-local/ (compiler + jvm jars) and apk-build/deps/ +
# apk-build/extract/ (AARs, extracted). Re-run is safe; skips present files.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AB="$ROOT/apk-build"
DL="$AB/deps-local"
mkdir -p "$DL"

MC="https://repo1.maven.org/maven2"
fetch() { # fetch <url> <dest>
  if [ -f "$2" ]; then echo "  present: $(basename "$2")"; return; fi
  echo "  downloading: $(basename "$2")"
  curl -sSL --retry 3 -o "$2" "$1"
}

echo "==> Kotlin compiler + plugins (2.0.21)"
fetch "$MC/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21/kotlin-compiler-embeddable-2.0.21.jar" \
      "$DL/kotlin-compiler-embeddable-2.0.21.jar"
fetch "$MC/org/jetbrains/kotlin/kotlin-compose-compiler-plugin-embeddable/2.0.21/kotlin-compose-compiler-plugin-embeddable-2.0.21.jar" \
      "$DL/kotlin-compose-compiler-plugin-embeddable-2.0.21.jar"
fetch "$MC/org/jetbrains/kotlin/kotlin-serialization-compiler-plugin-embeddable/2.0.21/kotlin-serialization-compiler-plugin-embeddable-2.0.21.jar" \
      "$DL/kotlin-serialization-compiler-plugin-embeddable-2.0.21.jar"

echo "==> JVM libraries"
fetch "$MC/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.jar" \
      "$DL/kotlinx-coroutines-core-jvm-1.9.0.jar"
fetch "$MC/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3.jar" \
      "$DL/kotlinx-serialization-core-jvm-1.7.3.jar"
fetch "$MC/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.7.3/kotlinx-serialization-json-jvm-1.7.3.jar" \
      "$DL/kotlinx-serialization-json-jvm-1.7.3.jar"
fetch "$MC/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar" \
      "$DL/okhttp-4.12.0.jar"
fetch "$MC/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar" \
      "$DL/okio-jvm-3.6.0.jar"

echo "==> Test libraries (JUnit 4)"
fetch "$MC/junit/junit/4.13.2/junit-4.13.2.jar" \
      "$DL/junit-4.13.2.jar"
fetch "$MC/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" \
      "$DL/hamcrest-core-1.3.jar"

echo "==> AndroidX AARs (resolve_deps.py: BOM 2024.10.00 + pinned roots)"
python3 "$AB/resolve_deps.py" --out "$AB/deps"

echo "==> Extract AARs + merge manifests"
python3 "$AB/extract_aars.py" \
  --deps "$AB/deps" \
  --app-manifest "$ROOT/omni/app/src/main/AndroidManifest.xml" \
  --out "$AB/extract" \
  --package com.omni.app

echo "DEPS COMPLETE"
