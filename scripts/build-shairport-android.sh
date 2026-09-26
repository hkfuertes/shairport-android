#!/bin/sh
# Runs inside shairport-echo-deps:local (see build-android-docker.sh).
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source="$root/third_party/shairport-sync"
: "${ANDROID_NDK_HOME:=/opt/android-ndk-r27c}"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

[ -d "$source" ] || {
  echo "missing $source; run make fetch patch first" >&2
  exit 1
}
"$root/scripts/build-shairport-deps-android.sh" # resumable; no-op once built

cd "$source"
[ -x configure ] || autoreconf -fi

for abi in armeabi-v7a arm64-v8a; do
  case "$abi" in
    armeabi-v7a) host=arm-linux-androideabi cc=armv7a-linux-androideabi25-clang prefix=/opt/armv7-android ;;
    arm64-v8a) host=aarch64-linux-android cc=aarch64-linux-android25-clang prefix="$root/build/deps/arm64-v8a" ;;
  esac
  build="$root/build/shairport/$abi"
  out="$root/build/shairport-bin/$abi"
  mkdir -p "$build" "$out"
  cd "$build"
  # Incremental: configure once per ABI (bump the stamp when changing flags);
  # automake reruns configure itself if configure.ac changes.
  stamp='airplay-2 tinysvcmdns stdout aaudio metadata-multicast openssl v3'
  if [ "$(cat .android-configure 2>/dev/null)" != "$stamp" ]; then
    # ponytail: Bionic malloc probes cannot run while cross-compiling; static libgcrypt needs gpg-error.
    PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_PATH="$prefix/lib/pkgconfig" \
      ac_cv_func_malloc_0_nonnull=yes ac_cv_func_realloc_0_nonnull=yes \
      CC="$toolchain/$cc" CXX="$toolchain/$cc++" \
      AR="$toolchain/llvm-ar" RANLIB="$toolchain/llvm-ranlib" \
      CFLAGS='-O2 -fPIC' CPPFLAGS="-I$prefix/include" \
      LDFLAGS="-L$prefix/lib -static-libstdc++ -Wl,--as-needed" LIBS=-lgpg-error \
      "$source/configure" --host="$host" --build=x86_64-pc-linux-gnu \
        --with-airplay-2 --with-tinysvcmdns --with-stdout --with-aaudio --with-metadata-multicast \
        --with-ssl=openssl
    printf '%s\n' "$stamp" > .android-configure
  fi
  make -j"${JOBS:-2}"
  "$toolchain/llvm-strip" -o "$out/libshairport_sync.so" shairport-sync
  if "$toolchain/llvm-readelf" -d "$out/libshairport_sync.so" | grep -q 'libc++_shared'; then
    echo "$abi: shairport-sync still needs libc++_shared.so" >&2
    exit 1
  fi
  printf 'built %s\n' "$out/libshairport_sync.so"
done
