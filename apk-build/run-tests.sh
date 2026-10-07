#!/bin/bash
# Helios — compile + run every JVM unit test (JUnit 4) in the tree.
# Test roots: helios/registry/test, omni/gateway/src/test,
#             helios/vision/test, helios/memory/test, nes/test
# Runs against classes-gateway + classes-app. Any red test fails the build.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AB="$ROOT/apk-build"
TC="$ROOT/toolchain"
DL="$AB/deps-local"
KOTLIN_LIBS="$TC/kotlinc/lib"

export JAVA_HOME="$TC/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"

KCE="$DL/kotlin-compiler-embeddable-2.0.21.jar"
[ -f "$KCE" ] || { echo "FATAL: missing $KCE (run scripts/fetch-deps.sh)"; exit 1; }
for j in junit-4.13.2 hamcrest-core-1.3 mockwebserver-4.12.0 kotlinx-coroutines-test-jvm-1.9.0 kotlin-test-junit-2.0.21; do
  [ -f "$DL/$j.jar" ] || { echo "FATAL: missing $DL/$j.jar (run scripts/fetch-deps.sh)"; exit 1; }
done
[ -d "$AB/classes-gateway" ] || { echo "FATAL: run apk-build/compile-gateway.sh first"; exit 1; }
[ -d "$AB/classes-app" ] || { echo "FATAL: run apk-build/compile-app.sh first"; exit 1; }

KOTLINC_CP="$KCE"
for j in kotlin-compiler kotlin-stdlib kotlinx-coroutines-core-jvm kotlin-reflect trove4j annotations-13.0 kotlin-daemon; do
  KOTLINC_CP="$KOTLINC_CP:$KOTLIN_LIBS/$j.jar"
done

TEST_CP="$AB/classes-gateway:$AB/classes-app"
TEST_CP="$TEST_CP:$DL/junit-4.13.2.jar:$DL/hamcrest-core-1.3.jar"
TEST_CP="$TEST_CP:$DL/mockwebserver-4.12.0.jar:$DL/kotlinx-coroutines-test-jvm-1.9.0.jar"
TEST_CP="$TEST_CP:$DL/kotlin-test-junit-2.0.21.jar"
TEST_CP="$TEST_CP:$KOTLIN_LIBS/kotlin-test.jar:$KOTLIN_LIBS/kotlin-stdlib.jar"
# android.jar (stub classes, loadable but not callable): app tests that touch
# Compose runtime state (mutableStateListOf etc.) need android.os.Looper on
# the classpath or class-init fails with NoClassDefFoundError. Additive —
# tests that never touch android classes are unaffected.
AJAR="$TC/android-sdk/platforms/android-36/android.jar"
[ -f "$AJAR" ] && TEST_CP="$TEST_CP:$AJAR"
for j in kotlinx-coroutines-core-jvm-1.9.0 kotlinx-serialization-core-jvm-1.7.3 \
         kotlinx-serialization-json-jvm-1.7.3 okhttp-4.12.0 okio-jvm-3.6.0; do
  TEST_CP="$TEST_CP:$DL/$j.jar"
done
# App tests reference Compose value types (e.g. androidx.compose.ui.graphics.Color,
# a pure-JVM value class) — the AAR extracts carry those classes.
for d in "$AB"/extract/*/; do
  [ -f "$d/classes.jar" ] && TEST_CP="$TEST_CP:$d/classes.jar"
done

echo "== compiling tests"
rm -rf "$AB/classes-test"; mkdir -p "$AB/classes-test"
TEST_SRCS="$(find "$ROOT/helios/registry/test" "$ROOT/omni/gateway/src/test" \
  "$ROOT/helios/vision/test" "$ROOT/helios/memory/test" "$ROOT/nes/test" \
  "$ROOT/helios/selfimprove/test" "$ROOT/omni/app/src/test" \
  -name '*.kt' 2>/dev/null | sort | tr '\n' ' ')"
[ -n "$TEST_SRCS" ] || { echo "FATAL: no test sources found"; exit 1; }
echo "   test files: $(echo "$TEST_SRCS" | wc -w)"
cd "$ROOT"
# shellcheck disable=SC2086
java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$TEST_CP" $TEST_SRCS -d "$AB/classes-test" 2>&1 | grep -E "error:|warning: unable" | head -20
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "TEST COMPILE FAILED"; exit 1; }

echo "== running tests"
cd "$AB/classes-test"
TEST_CLASSES="$(find . -name '*Test.class' ! -name '*$*' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort | tr '\n' ' ')"
echo "   test classes: $TEST_CLASSES"
# shellcheck disable=SC2086
java -cp "$TEST_CP:$AB/classes-test" org.junit.runner.JUnitCore $TEST_CLASSES 2>&1 | tee "$AB/junit.log" | tail -6

if grep -q "^OK (" "$AB/junit.log"; then
  echo "TESTS GREEN: $(grep -oP '^OK \(\K[0-9]+' "$AB/junit.log") tests passed"
else
  echo "TESTS RED — see $AB/junit.log"
  exit 1
fi
