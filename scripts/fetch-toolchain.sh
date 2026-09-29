#!/usr/bin/env bash
# fetch-toolchain.sh — bootstrap a Helios build environment on a normal machine.
# Installs: Temurin JDK 17, Android SDK (cmdline-tools, build-tools 36.0.0,
# android-36 platform), Kotlin 2.0.21 compiler.
# Everything lands in ./toolchain/ (gitignored). Re-run is safe.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="$ROOT/toolchain"
mkdir -p "$TC"
cd "$TC"

KOTLIN_VERSION="2.0.21"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo "==> JDK 17 (Temurin)"
if [ ! -d jdk-17 ]; then
  curl -sSL -o jdk17.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk-17 && tar xzf jdk17.tar.gz -C jdk-17 --strip-components=1
  rm jdk17.tar.gz
else echo "    already present"; fi

# sdkmanager needs a JVM now, not just at the end.
export JAVA_HOME="$TC/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> Android SDK cmdline-tools"
if [ ! -d android-sdk/cmdline-tools/latest ]; then
  curl -sSL -o cmdtools.zip "$CMDLINE_TOOLS_URL"
  mkdir -p android-sdk/cmdline-tools
  unzip -q cmdtools.zip -d android-sdk/cmdline-tools
  mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
  rm cmdtools.zip
  yes | android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1 || true
else echo "    already present"; fi

export ANDROID_HOME="$TC/android-sdk"
export ANDROID_SDK_ROOT="$TC/android-sdk"

echo "==> SDK packages: build-tools 36.0.0, android-36"
if [ -x "$ANDROID_HOME/build-tools/36.0.0/aapt2" ] && [ -d "$ANDROID_HOME/platforms/android-36" ]; then
  echo "    already present, skipping sdkmanager"
else
  # NOTE: sdkmanager's HTTP stack cannot tunnel through authenticating
  # proxies. If this fails in your environment, install the packages with
  # Android Studio (or another machine) and copy build-tools/36.0.0,
  # platforms/android-36, platform-tools and licenses/ into ./toolchain/android-sdk/.
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
    "build-tools;36.0.0" "platforms;android-36" "platform-tools"
fi

echo "==> Kotlin compiler $KOTLIN_VERSION"
if [ ! -x "$TC/kotlinc/bin/kotlinc" ]; then
  curl -sSL -o kotlin-compiler.zip \
    "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip"
  unzip -q kotlin-compiler.zip -d "$TC/kotlinc-tmp"
  mkdir -p "$TC/kotlinc" && cp -r "$TC/kotlinc-tmp/kotlinc/"* "$TC/kotlinc/"
  rm -rf "$TC/kotlinc-tmp" kotlin-compiler.zip
else echo "    already present"; fi

echo
echo "Done. Export for builds:"
echo "  export JAVA_HOME=$TC/jdk-17"
echo "  export ANDROID_HOME=$TC/android-sdk"
echo "  export PATH=\$JAVA_HOME/bin:$TC/kotlinc/bin:\$ANDROID_HOME/build-tools/36.0.0:\$PATH"
