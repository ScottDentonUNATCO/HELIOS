#!/bin/bash
# Helios — d8 dex step (no Gradle). Inputs: classes-app, classes-gateway,
# classes-r + AAR classes + jvm lib jars (android.jar passed as --lib).
# Dex input list is regenerated every run with duplicate-artifact elimination
# (keeps the highest version per artifact — e.g. drops a stale
# lifecycle-viewmodel-ktx 2.6.1 when 2.8.6 is present). ROOT-relative.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AB="$ROOT/apk-build"
TC="$ROOT/toolchain"
DL="$AB/deps-local"
BT="$TC/android-sdk/build-tools/36.0.0"
AJAR="$TC/android-sdk/platforms/android-36/android.jar"

export JAVA_HOME="$TC/jdk-17"
export PATH="$JAVA_HOME/bin:$BT:$PATH"

for d in "$AB/classes-app" "$AB/classes-gateway" "$AB/classes-r"; do
  [ -d "$d" ] || { echo "FATAL: missing $d"; exit 1; }
done

echo "== jar up class dirs (d8 takes jars, not dirs)"
rm -rf "$AB/dex-jars"; mkdir -p "$AB/dex-jars"
for d in classes-app classes-gateway classes-r; do
  ( cd "$AB/$d" && zip -q -r "$AB/dex-jars/$d.jar" . -i "*.class" )
done

echo "== build dex input list (dedup by artifact, highest version wins)"
INPUTS="$AB/dex-jars/classes-app.jar
$AB/dex-jars/classes-gateway.jar
$AB/dex-jars/classes-r.jar"
# AAR classes jars, deduped: extract/<group>__<artifact>__<version>/classes.jar
declare -A BEST
for d in "$AB"/extract/*/; do
  [ -f "$d/classes.jar" ] || continue
  base="$(basename "$d")"
  key="${base%__*}"
  ver="${base##*__}"
  prev="${BEST[$key]:-}"
  if [ -z "$prev" ] || [ "$(printf '%s\n%s' "$prev" "$ver" | sort -V | tail -1)" = "$ver" ]; then
    BEST[$key]="$ver"
  fi
done
for key in "${!BEST[@]}"; do
  INPUTS="$INPUTS
$AB/extract/${key}__${BEST[$key]}/classes.jar"
done
# JVM libs (no version conflicts in this set)
for j in kotlinx-coroutines-core-jvm-1.9.0 kotlinx-serialization-core-jvm-1.7.3 \
         kotlinx-serialization-json-jvm-1.7.3 okhttp-4.12.0 okio-jvm-3.6.0; do
  INPUTS="$INPUTS
$DL/$j.jar"
done
echo "$INPUTS" | grep -v '^$' | sort > "$AB/dex-inputs.txt"
echo "  $(wc -l < "$AB/dex-inputs.txt") dex inputs"

echo "== d8 --min-api 28"
rm -rf "$AB/dex-out"; mkdir -p "$AB/dex-out"
# shellcheck disable=SC2086
d8 --min-api 28 --lib "$AJAR" --output "$AB/dex-out/" \
  $(tr '\n' ' ' < "$AB/dex-inputs.txt") 2>&1 | grep -vi "warning" | head -10
echo "== dex outputs"
ls -la "$AB/dex-out/"
echo "DEX COMPLETE"
