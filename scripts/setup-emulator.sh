#!/usr/bin/env bash
# Install everything needed to run the Android emulator in this sandbox.
#
# There is no root and no /dev/kvm here, so:
#   * SDK packages go under $HOME/.local/android-sdk (already used by the build)
#   * the X11/pulse libraries the emulator links against are unpacked from Ubuntu
#     .deb archives into $HOME/.local/emulator-sysroot instead of being installed
#   * the guest runs on QEMU's TCG backend (software), which is slow: expect a
#     4-8 minute cold boot and a very slow first frame.
#
# Usage:  ./scripts/setup-emulator.sh [avd-name]
set -euo pipefail

ANDROID_HOME=${ANDROID_HOME:-$HOME/.local/android-sdk}
JAVA_HOME=${JAVA_HOME:-$HOME/.local/opt/jdk-21.0.13+9}
AVD=${1:-atd30}
IMAGE="system-images;android-30;aosp_atd;x86_64"
SYSROOT="$HOME/.local/emulator-sysroot"
DEB_DIR=${DEB_DIR:-$PWD/artifacts/emulator/debs}
IDX_DIR=${IDX_DIR:-$PWD/artifacts/emulator/idx}
UBUNTU=${UBUNTU:-http://archive.ubuntu.com/ubuntu}
RELEASE=${RELEASE:-noble}

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "==> SDK packages"
# Keep the license prompt out of the pipeline: `yes | sdkmanager` dies of SIGPIPE when
# sdkmanager stops reading, which pipefail turns into a script failure.
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "platform-tools" "emulator" "$IMAGE" >/dev/null

echo "==> shared libraries (unpacked, not installed)"
mkdir -p "$SYSROOT" "$DEB_DIR" "$IDX_DIR"
for component in main universe; do
    index="$IDX_DIR/$component.gz"
    [ -f "$index" ] || curl -sS --max-time 180 -o "$index" \
        "$UBUNTU/dists/$RELEASE/$component/binary-amd64/Packages.gz"
done

# Everything the emulator binary and its pulse dependency chain ask for.
packages=(
    libpulse0 libnss3 libnspr4 libxi6 libpng16-16t64 libxkbfile1
    libsndfile1 libasyncns0 libflac12t64 libvorbis0a libvorbisenc2
    libogg0 libopus0 libmpg123-0t64 libmp3lame0
)
for package in "${packages[@]}"; do
    # awk reads the whole index on purpose: exiting early gives zcat SIGPIPE, and
    # pipefail would turn that into a failed script.
    filename=$(zcat "$IDX_DIR"/*.gz 2>/dev/null | awk -v pkg="$package" \
        '$1=="Package:"{current=$2} current==pkg && $1=="Filename:" && found==""{found=$2} END{print found}')
    if [ -z "$filename" ]; then
        echo "!! package not found in the index: $package" >&2
        exit 1
    fi
    deb="$DEB_DIR/$(basename "$filename")"
    [ -s "$deb" ] || curl -sS --max-time 180 -o "$deb" "$UBUNTU/$filename"
    dpkg-deb -x "$deb" "$SYSROOT"
done

echo "==> emulator binary"
SR="$SYSROOT/usr/lib/x86_64-linux-gnu"
LD_LIBRARY_PATH="$ANDROID_HOME/emulator/lib64:$ANDROID_HOME/emulator/lib:$ANDROID_HOME/emulator/lib64/qt/lib:$ANDROID_HOME/emulator/lib64/gles_swiftshader:$SR:$SR/pulseaudio" \
    "$ANDROID_HOME/emulator/emulator" -version | head -1

echo "==> AVD $AVD"
if [ ! -f "$HOME/.android/avd/$AVD.ini" ]; then
    echo no | avdmanager create avd -n "$AVD" -k "$IMAGE" -d pixel_2 --force >/dev/null
fi
config="$HOME/.android/avd/$AVD.avd/config.ini"
sed -i -e 's/^hw\.lcd\.width=.*/hw.lcd.width=480/' \
       -e 's/^hw\.lcd\.height=.*/hw.lcd.height=800/' \
       -e 's/^hw\.lcd\.density=.*/hw.lcd.density=160/' \
       -e 's/^hw\.ramSize=.*/hw.ramSize=2048/' "$config"

echo
echo "Ready. Start it with:  ./scripts/emulator-run.sh $AVD"
echo "Then wait for boot:     adb wait-for-device shell 'while [ \"\$(getprop sys.boot_completed)\" != 1 ]; do sleep 5; done'"
