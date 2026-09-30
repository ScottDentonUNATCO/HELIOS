#!/bin/bash
# Helios — final APK assembly: dex insertion -> zipalign -> sign -> verify.
# Assumes dex-out/classes*.dex (from dex.sh) and omni-unsigned.apk (from
# relink.sh) are fresh. No Gradle. ROOT-relative.
#
# Signing: uses apk-build/debug.keystore if present (auto-detects its first
# alias via keytool -list -v); otherwise generates a fresh debug key. Whoever
# builds drops their own keystore here — keystores are gitignored, NEVER
# committed. The v8-matching keystore lives at apk-build/debug.keystore with
# alias androiddebugkey, so fresh builds install as upgrades over v8.
# Env overrides: KEYSTORE, KEY_ALIAS, KEYSTORE_PASS, KEY_PASS.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AB="$ROOT/apk-build"
TC="$ROOT/toolchain"
BT="$TC/android-sdk/build-tools/36.0.0"
AJAR="$TC/android-sdk/platforms/android-36/android.jar"

export JAVA_HOME="$TC/jdk-17"
export PATH="$JAVA_HOME/bin:$BT:$PATH"

BASE_APK="$AB/omni-unsigned.apk"
DEX_DIR="$AB/dex-out"

KEYSTORE="${KEYSTORE:-$AB/debug.keystore}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_PASS="${KEY_PASS:-android}"
OUT_APK="$AB/helios-v11-debug.apk"

[ -f "$BASE_APK" ] || { echo "FATAL: missing $BASE_APK (run apk-build/relink.sh)"; exit 1; }

if [ ! -f "$KEYSTORE" ]; then
  echo "== generating fresh debug keystore"
  keytool -genkeypair -keystore "$KEYSTORE" -alias helios-debug \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KEYSTORE_PASS" -keypass "$KEY_PASS" \
    -dname "CN=Helios Debug, OU=UNATCO, O=Helios, L=San Diego, C=US" 2>/dev/null
fi

if [ -z "${KEY_ALIAS:-}" ]; then
  KEY_ALIAS="$(keytool -list -v -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" 2>/dev/null \
    | grep -i "Alias name:" | head -1 | sed 's/.*Alias name: //;s/ *$//')"
  [ -n "$KEY_ALIAS" ] || { echo "FATAL: no alias found in $KEYSTORE"; exit 1; }
fi
echo "== signing with keystore alias: $KEY_ALIAS"

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
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" \
  --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASS" \
  --out "$OUT_APK" "$AB/omni-aligned.apk"
echo "  signed -> $OUT_APK"

echo "== step 4: verify"
apksigner verify --print-certs "$OUT_APK" | head -6
aapt2 dump badging "$OUT_APK" | grep -E "^package:|^application:" | head -3
echo "ASSEMBLE COMPLETE: $OUT_APK ($(stat -c%s "$OUT_APK") bytes)"
