#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source="$root/third_party/nqptp"
out="$root/build/nqptp/armeabi-v7a"
: "${ANDROID_NDK_HOME:?run this inside the Android Docker builder}"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
cc="$toolchain/armv7a-linux-androideabi25-clang"

[ -d "$source" ] || {
  echo "missing $source; run make fetch patch first" >&2
  exit 1
}
[ -x "$cc" ] || {
  echo "missing Android NDK compiler: $cc" >&2
  exit 1
}

cd "$source"
make distclean >/dev/null 2>&1 || true
autoreconf -fi
# ponytail: Autoconf cannot run this Bionic malloc probe while cross-compiling.
ac_cv_func_malloc_0_nonnull=yes ac_cv_func_realloc_0_nonnull=yes \
  CC="$cc" AR="$toolchain/llvm-ar" RANLIB="$toolchain/llvm-ranlib" \
  ./configure --host=armv7a-linux-androideabi --build=x86_64-linux-gnu --prefix="$out"
make -j"${JOBS:-2}"
mkdir -p "$out"
cp nqptp "$out/nqptp"
printf 'built %s\n' "$out/nqptp"
