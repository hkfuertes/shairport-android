#!/bin/sh
# Build tests/aaudio/check.c against the patched audio_aaudio.c (Dockerfile target aaudio-check)
# and run it as root on the adb device (API 26+). Silent: it only writes zeros.
# Usage: tests/aaudio/run.sh [serial]
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
docker build --target aaudio-check --output "$root/build/aaudio" "$root"
adb ${1:+-s "$1"} push "$root/build/aaudio/aaudio-check" /data/local/tmp/aaudio-check >/dev/null
adb ${1:+-s "$1"} shell 'chmod 755 /data/local/tmp/aaudio-check && su -c /data/local/tmp/aaudio-check'
