#!/bin/bash
# Helios — reproducible resource re-link (no Gradle).
# Recompiles all resources (AAR res dirs + app res/) and re-links the base APK
# from extract/merged-AndroidManifest.xml. Idempotent; safe to re-run.
#
# After link, regenerates:
#   gen-r/com/omni/app/R.java  (aapt2 --java) -> javac -> classes-r (app R)
#   gen-r-lib/                  (gen_lib_r.py from linked resource table)
#
# ROOT-relative: works from any checkout location.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AB="$ROOT/apk-build"
TC="$ROOT/toolchain"
SDK="$TC/android-sdk"
BT="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-36/android.jar"

export JAVA_HOME="$TC/jdk-17"
export PATH="$JAVA_HOME/bin:$BT:$PATH"

MANIFEST="$AB/extract/merged-AndroidManifest.xml"
[ -f "$MANIFEST" ] || { echo "FATAL: missing $MANIFEST (run scripts/fetch-deps.sh)"; exit 1; }
grep -q "Helios" "$MANIFEST" || echo "WARN: manifest label is not Helios"

FLATS="$AB/flats"
rm -rf "$FLATS"; mkdir -p "$FLATS"

echo "== aapt2 compile (app res + AAR res dirs)"
count=0
i=0
for resdir in "$AB/res" "$AB"/extract/*/res; do
  [ -d "$resdir" ] || continue
  # NOTE: each res dir gets its own output dir. aapt2 names flats only by
  # source filename (values_values.arsc.flat), so sharing one dir lets 40
  # libraries overwrite each other's values and the link fails with
  # "resource not found".
  mkdir -p "$FLATS/d$i"
  aapt2 compile --dir "$resdir" -o "$FLATS/d$i" 2>"$FLATS/d$i.compile.log" || {
    echo "COMPILE FAILED: $resdir"; cat "$FLATS/d$i.compile.log"; exit 1; }
  count=$((count+1)); i=$((i+1))
done
echo "  compiled $count res dirs -> $(find "$FLATS" -name "*.flat" | wc -l) flat files"

echo "== aapt2 link"
rm -f "$AB/omni-unsigned.apk"
# Stage app assets (e.g. chinaskar-v1/chinaskar-v1.html) into the linked APK.
ASSETS="$AB/assets"
rm -rf "$ASSETS"; mkdir -p "$ASSETS"
SRC_ASSETS="$ROOT/omni/app/src/main/assets"
[ -d "$SRC_ASSETS" ] && cp -r "$SRC_ASSETS/." "$ASSETS/"
echo "  staged $(find "$ASSETS" -type f | wc -l) asset files"
aapt2 link -o "$AB/omni-unsigned.apk" \
  -I "$AJAR" \
  --manifest "$MANIFEST" \
  --min-sdk-version 28 --target-sdk-version 36 \
  --version-code 9 --version-name "0.8.1-helios" \
  -A "$ASSETS" \
  --java "$AB/gen-r" \
  "$FLATS"/*/*.flat
echo "  linked -> $AB/omni-unsigned.apk ($(stat -c%s "$AB/omni-unsigned.apk") bytes)"

echo "== resource dump (for gen_lib_r.py)"
aapt2 dump resources "$AB/omni-unsigned.apk" > "$AB/res-dump.txt"
echo "  $(wc -l < "$AB/res-dump.txt") lines"

echo "== app R.java -> classes-r"
rm -rf "$AB/classes-r/com/omni"
mkdir -p "$AB/classes-r"
javac -source 8 -target 8 -nowarn -cp "$AJAR" \
  -d "$AB/classes-r" "$AB/gen-r/com/omni/app/R.java" 2>/dev/null
echo "  $(find "$AB/classes-r/com/omni" -name "*.class" | wc -l) app R classes"

echo "== library R classes (gen_lib_r.py)"
ls "$AB/extract/EXTRACT.tsv" >/dev/null 2>&1 || { echo "FATAL: extract/EXTRACT.tsv missing"; exit 1; }
python3 "$AB/gen_lib_r.py" --dump "$AB/res-dump.txt" --extract "$AB/extract" --out "$AB/gen-r-lib"
javac -source 8 -target 8 -nowarn -cp "$AJAR:$AB/classes-r" \
  -d "$AB/classes-r" $(find "$AB/gen-r-lib" -name "*.java") 2>/dev/null
echo "  total classes-r: $(find "$AB/classes-r" -name "*.class" | wc -l)"

echo "== badging"
aapt2 dump badging "$AB/omni-unsigned.apk" | grep -E "package:|application:|launchable|uses-permission" | head -12
echo "RELINK COMPLETE"
