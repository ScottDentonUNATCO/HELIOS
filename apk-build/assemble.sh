#!/bin/bash
# Helios — final APK assembly: dex insertion -> zipalign -> sign -> verify.
# Assumes dex-out/classes*.dex (from dex.sh) and omni-unsigned.apk (from
# relink.sh) are fresh. No Gradle. ROOT-relative.
#
# Signing: uses apk-build/debug.keystore if present; otherwise generates a
# fresh debug key (keytool). For your own releases, drop in your keystore
# and update KEYSTORE/aliases below — the debug key is NOT the v8 release key.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AB="$ROOT/apk-build"
TC="$ROOT/toolchain"
BT="$TC/android-sdk/build-tools/36.0.0"

export JAVA_HOME="$TC/jdk-17"
export PATH="$JAVA_HOME/bin:$BT:$PATH"

BASE_APK="$AB/omni-unsigned.apk"
DEX_DIR="$AB/dex-out"
KEYSTORE="$AB/debug.keystore"
OUT_APK="$AB/helios-v8-debug.apk"

[ -f "$BASE_APK" ] || { echo "FATAL: missing $BASE_APK (run apk-build/relink.sh)"; exit 1; }

if [ ! -f "$KEYSTORE" ]; then
  echo "== generating fresh debug keystore (not the v8 release key)"
  keytool -genkeypair -keystore "$KEYSTORE" -alias helios-debug \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=Helios Debug, OU=UNATCO, O=Helios, L=San Diego, C=US" 2>/dev/null
fi

echo "== step 1: insert dex into base APK"
rm -f "$AB/omni-with-dex.apk"
cp "$BASE_APK" "$AB/omni-with-dex.apk"
for dex in "$DEX_DIR"/classes*.dex; do
  [ -f "$dex" ] || { echo "FATAL: no dex in $DEX_DIR (run apk-build/dex.sh)"; exit 1; }
  zip -q -j "$AB/omni-with-dex.apk" "$dex"
  echo "  added $(basename "$dex") ($(stat -c%s "$dex") bytes)"
done

echo "== step 2: zipalign"
zipalign -p -f 4 "$AB/omni-with-dex.apk" "$AB/omni-aligned.apk"
zipalign -c -p 4 "$AB/omni-aligned.apk" && echo "  alignment check: OK"

echo "== step 3: sign"
rm -f "$OUT_APK"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT_APK" "$AB/omni-aligned.apk"
echo "  signed -> $OUT_APK"

echo "== step 4: verify"
apksigner verify --print-certs "$OUT_APK" | head -6
aapt2 dump badging "$OUT_APK" | grep -E "^package:|^application:" | head -3
echo "ASSEMBLE COMPLETE: $OUT_APK ($(stat -c%s "$OUT_APK") bytes)"
