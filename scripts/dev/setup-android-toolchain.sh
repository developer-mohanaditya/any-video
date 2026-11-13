#!/usr/bin/env bash
# User-local Android toolchain for building this project (no root in the sandbox).
# Installs cmdline-tools, platform-tools, platform 35, build-tools 35, and Gradle 8.9
# under $HOME/.local, then leaves ANDROID_HOME/JAVA_HOME instructions.
set -euo pipefail

SDK_ROOT="${HOME}/.local/android-sdk"
JDK="${HOME}/.local/opt/jdk-21.0.13+9"
GRADLE_VER=8.9
mkdir -p "$SDK_ROOT/cmdline-tools" "$HOME/.local/opt"

if [ ! -d "$SDK_ROOT/cmdline-tools/latest" ]; then
  curl -fsSL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -oq /tmp/cmdtools.zip -d "$SDK_ROOT/cmdline-tools"
  mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi

export ANDROID_HOME="$SDK_ROOT"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$SDK_ROOT/cmdline-tools/latest/bin:$PATH"

yes | sdkmanager --sdk_root="$SDK_ROOT" --licenses > /dev/null
sdkmanager --sdk_root="$SDK_ROOT" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

if [ ! -x "$HOME/.local/opt/gradle-$GRADLE_VER/bin/gradle" ]; then
  curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip"
  unzip -oq /tmp/gradle.zip -d "$HOME/.local/opt"
fi

echo "ANDROID_HOME=$ANDROID_HOME"
echo "JAVA_HOME=$JAVA_HOME"
"$HOME/.local/opt/gradle-$GRADLE_VER/bin/gradle" --version | head -5
