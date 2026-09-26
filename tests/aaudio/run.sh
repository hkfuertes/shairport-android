#!/bin/sh
# Build tests/aaudio/check.c against the patched audio_aaudio.c and run it as root on the
# adb device (API 26+). Silent: it only writes zeros. Usage: tests/aaudio/run.sh [serial]
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
image=${SHAIRPORT_DEPS_IMAGE:-shairport-echo-deps:local}

# The backend's #include "common.h" must find the stand-in, so compile a copy next to it.
src="$root/build/aaudio-check-src"
mkdir -p "$src"
cp "$root/third_party/shairport-sync/audio_aaudio.c" "$root/third_party/shairport-sync/audio.h" \
  "$root/tests/aaudio/common.h" "$src/"
docker run --rm --network none --user "$(id -u):$(id -g)" -v "$root:/work" -w /work "$image" \
  sh -c '/opt/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android25-clang \
    -O1 -Wall -Wextra -I build/aaudio-check-src -I build/deps/arm64-v8a/include \
    -o build/aaudio-check tests/aaudio/check.c -ldl'
adb ${1:+-s "$1"} push "$root/build/aaudio-check" /data/local/tmp/aaudio-check >/dev/null
adb ${1:+-s "$1"} shell 'chmod 755 /data/local/tmp/aaudio-check && su -c /data/local/tmp/aaudio-check'
