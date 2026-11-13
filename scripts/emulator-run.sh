#!/usr/bin/env bash
# Boot the headless emulator used for smoke tests in this sandbox.
#
# The sandbox runs as an unprivileged user with no /dev/kvm access, so the guest
# is emulated by QEMU's TCG (software) backend: expect a slow boot. The X11/pulse
# libraries the emulator links against are unpacked under $HOME by
# scripts/setup-emulator.sh and injected through LD_LIBRARY_PATH.
set -euo pipefail

ANDROID_HOME=${ANDROID_HOME:-$HOME/.local/android-sdk}
AVD=${1:-avd30}
shift || true

SR="$HOME/.local/emulator-sysroot/usr/lib/x86_64-linux-gnu"
export LD_LIBRARY_PATH="$ANDROID_HOME/emulator/lib64:$ANDROID_HOME/emulator/lib:$ANDROID_HOME/emulator/lib64/qt/lib:$ANDROID_HOME/emulator/lib64/gles_swiftshader:$SR:$SR/pulseaudio"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_AVD_HOME="$HOME/.android/avd"

exec "$ANDROID_HOME/emulator/emulator" \
    -avd "$AVD" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -no-snapshot \
    -accel off \
    -gpu swiftshader_indirect \
    -memory 2048 \
    -cores 4 \
    -no-metrics \
    "$@"
