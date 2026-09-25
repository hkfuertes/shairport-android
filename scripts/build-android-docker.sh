#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
gradle_home=${HOST_GRADLE_HOME:-$HOME/.gradle}
android_user_home=${HOST_ANDROID_USER_HOME:-$HOME/.android}
image=${ANDROID_BUILDER_IMAGE:-shairplay-android-builder:latest}

[ -d "$sdk" ] || {
  echo "Android SDK not found: $sdk" >&2
  exit 1
}
[ -d "$gradle_home" ] || {
  echo "Gradle cache not found: $gradle_home" >&2
  exit 1
}
[ -d "$android_user_home" ] || {
  echo "Android user home not found: $android_user_home" >&2
  exit 1
}

exec docker run --rm --network none \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp \
  -e ANDROID_USER_HOME=/tmp/.android \
  -e GRADLE_USER_HOME=/gradle \
  -e ANDROID_HOME=/opt/android-sdk \
  -e ANDROID_SDK_ROOT=/opt/android-sdk \
  -e ANDROID_NDK_HOME=/opt/android-ndk \
  -v "$root:/work" \
  -v "$sdk:/opt/android-sdk:ro" \
  -v "$gradle_home:/gradle" \
  -v "$android_user_home:/tmp/.android" \
  -w /work \
  "$image" \
  -c './scripts/build-nqptp-android.sh && ./gradlew --offline --no-daemon --console=plain :app:assembleDebug'
