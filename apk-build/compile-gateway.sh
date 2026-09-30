#!/bin/bash
# Helios — kotlinc compile of the gateway + socket registry (pure JVM).
# Output: apk-build/classes-gateway. ROOT-relative.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AB="$ROOT/apk-build"
TC="$ROOT/toolchain"
DL="$AB/deps-local"
SDK="$TC/android-sdk"
AJAR="$SDK/platforms/android-36/android.jar"

export JAVA_HOME="$TC/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"

KCE="$DL/kotlin-compiler-embeddable-2.0.21.jar"
SERPLUGIN="$DL/kotlin-serialization-compiler-plugin-embeddable-2.0.21.jar"
KOTLIN_LIBS="$TC/kotlinc/lib"
[ -f "$KCE" ] || { echo "FATAL: missing $KCE (run scripts/fetch-deps.sh)"; exit 1; }

KOTLINC_CP="$KCE"
for j in kotlin-compiler kotlin-stdlib kotlinx-coroutines-core-jvm kotlin-reflect trove4j annotations-13.0 kotlin-daemon; do
  KOTLINC_CP="$KOTLINC_CP:$KOTLIN_LIBS/$j.jar"
done

# Gateway classpath: android.jar + kotlin-stdlib (explicit: kotlin-home
# auto-detection fails when the compiler runs via java -cp) + AAR classes + jvm libs
GATEWAY_CP="$AJAR:$KOTLIN_LIBS/kotlin-stdlib.jar"
for d in "$AB"/extract/*/; do
  [ -f "$d/classes.jar" ] && GATEWAY_CP="$GATEWAY_CP:$d/classes.jar"
done
for j in kotlinx-coroutines-core-jvm-1.9.0 kotlinx-serialization-core-jvm-1.7.3 \
         kotlinx-serialization-json-jvm-1.7.3 okhttp-4.12.0 okio-jvm-3.6.0; do
  GATEWAY_CP="$GATEWAY_CP:$DL/$j.jar"
done

echo "== kotlinc gateway + registry"
rm -rf "$AB/classes-gateway"; mkdir -p "$AB/classes-gateway"
cd "$ROOT"
# shellcheck disable=SC2086
java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -Xplugin="$SERPLUGIN" \
  -cp "$GATEWAY_CP" \
  $(find "$ROOT/omni/gateway" "$ROOT/helios/registry/src" -name '*.kt' \
      -not -path '*/src/test/*' -not -path '*/test/*' | sort | tr '\n' ' ') \
  -d "$AB/classes-gateway" 2>&1 | tail -5
echo "== gateway classes: $(find "$AB/classes-gateway" -name '*.class' | wc -l)"
echo "GATEWAY COMPILE COMPLETE"
