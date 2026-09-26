#!/bin/sh
# Shairport Sync and NQPTP for one Android ABI, as the APK's jniLibs (/out/<ABI>/lib*.so).
# Runs inside the Dockerfile: patched sources in /src, deps in /opt/deps. Usage: build-engine.sh ABI
set -eu

case "${1:-}" in
  arm64-v8a) host=aarch64-linux-android cc=aarch64-linux-android25-clang ;;
  armeabi-v7a) host=arm-linux-androideabi cc=armv7a-linux-androideabi25-clang ;;
  *) echo "usage: $0 arm64-v8a|armeabi-v7a" >&2; exit 2 ;;
esac
prefix=/opt/deps/$1
out=/out/$1
mkdir -p "$out"
export CC=$cc CXX=$cc++ AR=llvm-ar RANLIB=llvm-ranlib
# ponytail: Autoconf cannot run Bionic's malloc probes while cross-compiling.
export ac_cv_func_malloc_0_nonnull=yes ac_cv_func_realloc_0_nonnull=yes

rm -rf /tmp/nqptp && cp -a /src/nqptp /tmp/nqptp && cd /tmp/nqptp
./configure --build=x86_64-pc-linux-gnu --host="$host"
make -j"$(nproc)"
llvm-strip -o "$out/libnqptp.so" nqptp

rm -rf /tmp/shairport-sync && cp -a /src/shairport-sync /tmp/shairport-sync && cd /tmp/shairport-sync
# Static libgcrypt needs gpg-error; libc++ is linked statically (the APK ships no libc++_shared).
PKG_CONFIG_LIBDIR=$prefix/lib/pkgconfig PKG_CONFIG_PATH=$prefix/lib/pkgconfig \
  CFLAGS='-O2 -fPIC' CPPFLAGS=-I$prefix/include \
  LDFLAGS="-L$prefix/lib -static-libstdc++ -Wl,--as-needed" LIBS=-lgpg-error \
  ./configure --build=x86_64-pc-linux-gnu --host="$host" \
    --with-airplay-2 --with-tinysvcmdns --with-stdout --with-aaudio --with-metadata-multicast \
    --with-ssl=openssl
make -j"$(nproc)"
llvm-strip -o "$out/libshairport_sync.so" shairport-sync
if llvm-readelf -d "$out/libshairport_sync.so" | grep -q libc++_shared; then
  echo "$1: shairport-sync still needs libc++_shared.so" >&2
  exit 1
fi
