#!/bin/bash
# Helios — full kotlinc compile of the app (Compose UI + registry + NES toolkit)
# with the Compose compiler plugin. Proven zero-Gradle pattern. ROOT-relative.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AB="$ROOT/apk-build"
TC="$ROOT/toolchain"
DL="$AB/deps-local"
SDK="$TC/android-sdk"
AJAR="$SDK/platforms/android-36/android.jar"

KCE="$DL/kotlin-compiler-embeddable-2.0.21.jar"
KCPLUGIN="$DL/kotlin-compose-compiler-plugin-embeddable-2.0.21.jar"
KOTLIN_LIBS="$TC/kotlinc/lib"
[ -f "$KCE" ] || { echo "FATAL: missing $KCE (run scripts/fetch-deps.sh)"; exit 1; }
[ -f "$KCPLUGIN" ] || { echo "FATAL: missing $KCPLUGIN (run scripts/fetch-deps.sh)"; exit 1; }

export JAVA_HOME="$TC/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"

KOTLINC_CP="$KCE"
for j in kotlin-compiler kotlin-stdlib kotlinx-coroutines-core-jvm kotlin-reflect trove4j annotations-13.0 kotlin-daemon; do
  KOTLINC_CP="$KOTLINC_CP:$KOTLIN_LIBS/$j.jar"
done

# App classpath: android.jar + kotlin-stdlib (explicit: kotlin-home
# auto-detection fails when the compiler runs via java -cp)
# + gateway classes + AAR classes + jvm libs.
# Regenerated every run — never stale.
APP_CP="$AJAR:$KOTLIN_LIBS/kotlin-stdlib.jar:$AB/classes-gateway"
for d in "$AB"/extract/*/; do
  [ -f "$d/classes.jar" ] && APP_CP="$APP_CP:$d/classes.jar"
done
for j in kotlinx-coroutines-core-jvm-1.9.0 kotlinx-serialization-core-jvm-1.7.3 \
         kotlinx-serialization-json-jvm-1.7.3 okhttp-4.12.0 okio-jvm-3.6.0; do
  APP_CP="$APP_CP:$DL/$j.jar"
done
# Plain-JAR deps (lifecycle-common etc.) — the AAR extractor skips these.
while IFS= read -r j; do
  APP_CP="$APP_CP:$j"
done < <(find "$AB/deps" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' | sort)
echo "$APP_CP" > "$AB/compile-cp.txt"

echo "== kotlinc app full compile"
rm -rf "$AB/classes-app"; mkdir -p "$AB/classes-app"
cd "$ROOT"
# shellcheck disable=SC2086
java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -Xplugin="$KCPLUGIN" \
  -cp "$APP_CP" \
  $(find "$ROOT/omni/app/src/main/java" "$ROOT/nes/src" "$ROOT/helios/registry/src" \
      -name '*.kt' -not -path '*/src/test/*' -not -path '*/test/*' | sort | tr '\n' ' ') \
  -d "$AB/classes-app" 2>&1 | tee "$AB/kotlinc-app.log" | tail -5
echo "== app classes: $(find "$AB/classes-app" -name '*.class' | wc -l)"
echo "APP COMPILE COMPLETE"
