#!/usr/bin/env bash
# build.sh — full Helios APK build from a clean checkout. No Gradle.
#
#   ./scripts/build.sh
#
# Chain:
#   1. fetch-toolchain.sh   JDK 17 + Android SDK + kotlinc   (skips if present)
#   2. icons                regenerate launcher mipmaps from icon-work/make_icon.py
#   3. fetch-deps.sh        Maven jars + AARs, extracted
#   4. relink.sh            aapt2 compile + link -> omni-unsigned.apk, R classes
#   5. compile-gateway.sh   kotlinc gateway + registry -> classes-gateway
#   6. compile-app.sh       kotlinc app (+compose plugin) -> classes-app
#   7. run-tests.sh         compile + run all JVM unit tests (red fails build)
#   8. dex.sh               d8 -> dex-out/classes*.dex
#   9. assemble.sh          zipalign + sign + verify -> helios-v8-debug.apk
#
# Pass --skip-fetch to skip steps 1-3 when the toolchain/deps already exist.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKIP_FETCH=0
[ "${1:-}" = "--skip-fetch" ] && SKIP_FETCH=1

step() { echo; echo "########## $1 ##########"; }

if [ "$SKIP_FETCH" = 0 ]; then
  step "1/9 toolchain"
  "$ROOT/scripts/fetch-toolchain.sh"

  step "2/9 launcher icons"
  python3 "$ROOT/icon-work/make_icon.py"

  step "3/9 dependencies"
  "$ROOT/scripts/fetch-deps.sh"
else
  echo "(skipping fetch steps)"
fi

step "4/9 relink resources"
"$ROOT/apk-build/relink.sh"

step "5/9 compile gateway"
"$ROOT/apk-build/compile-gateway.sh"

step "6/9 compile app"
"$ROOT/apk-build/compile-app.sh"

step "7/9 unit tests"
"$ROOT/apk-build/run-tests.sh"

step "8/9 dex"
"$ROOT/apk-build/dex.sh"

step "9/9 assemble + sign + verify"
"$ROOT/apk-build/assemble.sh"

echo
echo "BUILD COMPLETE: $ROOT/apk-build/helios-v8-debug.apk"
