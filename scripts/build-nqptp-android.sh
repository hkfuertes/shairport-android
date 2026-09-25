#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source="$root/third_party/nqptp"
: "${ANDROID_NDK_HOME:?run this inside the Android Docker builder}"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

[ -d "$source" ] || {
  echo "missing $source; run make fetch patch first" >&2
  exit 1
}

for abi in armeabi-v7a arm64-v8a; do
  case "$abi" in
    armeabi-v7a) host=armv7a-linux-androideabi ;;
    arm64-v8a) host=aarch64-linux-android ;;
  esac
  cc="$toolchain/${host}25-clang"
  out="$root/build/nqptp/$abi"

  [ -x "$cc" ] || {
    echo "missing Android NDK compiler: $cc" >&2
    exit 1
  }

  cd "$source"
  make distclean >/dev/null 2>&1 || true
  autoreconf -fi
  # ponytail: Autoconf cannot run Bionic's malloc probes while cross-compiling.
  ac_cv_func_malloc_0_nonnull=yes ac_cv_func_realloc_0_nonnull=yes \
    CC="$cc" AR="$toolchain/llvm-ar" RANLIB="$toolchain/llvm-ranlib" \
    ./configure --host="$host" --build=x86_64-linux-gnu --prefix="$out"
  make -j"${JOBS:-2}"
  mkdir -p "$out"
  install -m 755 nqptp "$out/libnqptp.so"
  printf 'built %s\n' "$out/libnqptp.so"
done
