#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
gradle_home=${HOST_GRADLE_HOME:-$HOME/.gradle}
image=${ANDROID_BUILDER_IMAGE:-shairplay-android-builder:latest}

[ -d "$sdk" ] || {
  echo "Android SDK not found: $sdk" >&2
  exit 1
}
[ -d "$gradle_home" ] || {
  echo "Gradle cache not found: $gradle_home" >&2
  exit 1
}

exec docker run --rm --network none \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp \
  -e ANDROID_USER_HOME=/tmp/android-user \
  -e GRADLE_USER_HOME=/gradle \
  -e ANDROID_HOME=/opt/android-sdk \
  -e ANDROID_SDK_ROOT=/opt/android-sdk \
  -e ANDROID_NDK_HOME=/opt/android-ndk \
  -v "$root:/work" \
  -v "$sdk:/opt/android-sdk:ro" \
  -v "$gradle_home:/gradle" \
  -w /work \
  "$image" \
  -c './gradlew --offline --no-daemon --console=plain :app:assembleDebug'
