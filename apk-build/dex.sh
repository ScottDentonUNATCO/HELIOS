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
# Anchors: our own compiled jars — never dropped by dedupe.
ANCHORS="$AB/dex-jars/classes-app.jar
$AB/dex-jars/classes-gateway.jar
$AB/dex-jars/classes-r.jar"
# Candidates: every library jar. Anything fully shadowed by the rest
# (stale absorbed artifacts like collection-ktx or lifecycle-viewmodel-ktx,
# whose classes all live in the newer artifact now) is dropped.
: > "$AB/dex-candidates.txt"
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
  # Mirrors the original curated dex-inputs-v3.txt: lifecycle-viewmodel-ktx
  # 2.6.1 is excluded — its ViewModelKt duplicates the 2.8.6 one, and its only
  # other class (old-location CloseableCoroutineScope) is a benign metadata
  # reference inside 2.8.6. The v8 APK shipped exactly this way.
  case "$key" in
    androidx.lifecycle__lifecycle-viewmodel-ktx) continue ;;
  esac
  echo "$AB/extract/${key}__${BEST[$key]}/classes.jar" >> "$AB/dex-candidates.txt"
done
# AAR-bundled repackaged libs (e.g. emoji2 1.3.0's libs/repackaged.jar carries
# the FlatBuffer runtime its own classes need at startup). d8 must see these
# or the app dies with NoClassDefFoundError on launch (androidx.startup runs
# EmojiCompatInitializer before anything else).
for lj in "$AB"/extract/*/libs/*.jar; do
  [ -f "$lj" ] && echo "$lj" >> "$AB/dex-candidates.txt"
done
# JVM libs
for j in kotlinx-coroutines-core-jvm-1.9.0 kotlinx-serialization-core-jvm-1.7.3 \
         kotlinx-serialization-json-jvm-1.7.3 okhttp-4.12.0 okio-jvm-3.6.0; do
  echo "$DL/$j.jar" >> "$AB/dex-candidates.txt"
done
# Plain-JAR deps (lifecycle-common etc.) — same set as the compile classpaths.
find "$AB/deps" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' | sort >> "$AB/dex-candidates.txt"
echo "$ANCHORS" | grep -v '^$' | sort > "$AB/dex-anchors.txt"
DEDUPED="$(python3 "$AB/dedupe_dex_inputs.py" --anchors "$AB/dex-anchors.txt" --candidates "$AB/dex-candidates.txt")"
INPUTS="$ANCHORS
$DEDUPED"
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
