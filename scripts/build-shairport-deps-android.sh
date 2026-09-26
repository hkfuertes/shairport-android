#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${ANDROID_NDK_HOME:=/opt/android-ndk-r27c}"
[ -d /src/popt ] || {
  echo "run through scripts/build-android-docker.sh" >&2
  exit 1
}

api=25
host=aarch64-linux-android
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
prefix="$root/build/deps/arm64-v8a"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

[ -x "$toolchain/${host}${api}-clang" ] || {
  echo "missing Android NDK compiler" >&2
  exit 1
}
# ponytail: armeabi-v7a deps come prebuilt in the image at /opt/armv7-android.
# Resumable: each package is skipped once its library is installed (rm -rf the prefix to rebuild).
mkdir -p "$prefix"

export CC="$toolchain/${host}${api}-clang"
export CXX="$toolchain/${host}${api}-clang++"
export AR="$toolchain/llvm-ar"
export RANLIB="$toolchain/llvm-ranlib"
export STRIP="$toolchain/llvm-strip"
export CFLAGS='-O2 -fPIC'
export CXXFLAGS="$CFLAGS"
export CPPFLAGS="-I$prefix/include"
export LDFLAGS="-L$prefix/lib"
export PKG_CONFIG=pkg-config
export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
export PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"

copy_source() {
  rm -rf "$work/$1"
  cp -R --preserve=mode,timestamps "/src/$1" "$work/$1"
  cd "$work/$1"
  make distclean >/dev/null 2>&1 || true
  # /src trees were built in place for armv7; drop leftovers distclean missed.
  find . -type f \( -name '*.o' -o -name '*.lo' -o -name '*.a' -o -name '*.la' \
    -o -name '*.so' -o -name '*.so.*' \) -delete
}

if [ ! -s "$prefix/lib/libpopt.a" ]; then
  copy_source popt
  autoreconf -fi
  ac_cv_header_glob_h=no ./configure \
    --build=x86_64-pc-linux-gnu --host="$host" --prefix="$prefix" \
    --disable-shared --enable-static
  make -j"${JOBS:-2}"
  make install
fi

if [ ! -s "$prefix/lib/libconfig.a" ]; then
  copy_source libconfig
  autoreconf -fi
  ./configure --build=x86_64-pc-linux-gnu --host="$host" --prefix="$prefix" \
    --disable-cxx --disable-shared --enable-static
  make -j"${JOBS:-2}"
  make install
fi

if [ ! -s "$prefix/lib/libsodium.a" ]; then
  copy_source libsodium
  ./configure --build=x86_64-pc-linux-gnu --host="$host" --prefix="$prefix" \
    --disable-shared --enable-static
  make -j"${JOBS:-2}"
  make install
fi

if [ ! -s "$prefix/lib/libgpg-error.a" ]; then
  copy_source libgpg-error
  # ponytail: 1.51 ships Bionic lock metadata only for 32-bit ARM; arm64's mutex is 40 bytes.
  sed 's/_priv\[4\]/_priv[40]/' src/syscfg/lock-obj-pub.arm-unknown-linux-androideabi.h \
    > src/syscfg/lock-obj-pub.aarch64-unknown-linux-android.h
  ./configure --build=x86_64-pc-linux-gnu --host="$host" --prefix="$prefix" \
    --disable-shared --enable-static --disable-nls --disable-doc
  make -j"${JOBS:-2}"
  make install
  printf '%s\n' '#include <pthread.h>' '#include <gpg-error.h>' \
    '_Static_assert(sizeof(pthread_mutex_t) == sizeof(((gpgrt_lock_t *)0)->u._priv), "gpg-error lock size");' \
    | "$CC" -I"$prefix/include" -x c -c -o "$work/gpg-error-lock-check.o" -
fi

if [ ! -s "$prefix/lib/libgcrypt.a" ]; then
  copy_source libgcrypt
  PATH="$prefix/bin:$PATH" ./configure \
    --build=x86_64-pc-linux-gnu --host="$host" --prefix="$prefix" \
    --disable-shared --enable-static --disable-doc --disable-tests --disable-asm
  make -j"${JOBS:-2}"
  make install
fi

if [ ! -s "$prefix/lib/libplist-2.0.a" ]; then
  copy_source libplist
  ./configure --build=x86_64-pc-linux-gnu --host="$host" --prefix="$prefix" \
    --disable-shared --enable-static --without-cython
  make -j"${JOBS:-2}"
  make install
fi

if [ ! -s "$prefix/lib/libssl.a" ]; then
  copy_source openssl
  ANDROID_NDK_ROOT="$ANDROID_NDK_HOME" ./Configure android-arm64 \
    -D__ANDROID_API__="$api" no-shared no-tests no-zlib \
    --prefix="$prefix" --openssldir="$prefix/ssl"
  make -j"${JOBS:-2}" build_libs
  make install_dev
fi

if [ ! -s "$prefix/lib/libavformat.a" ]; then
  copy_source ffmpeg
  ./configure --prefix="$prefix" --target-os=android --arch=aarch64 --cpu=armv8-a \
    --enable-cross-compile --cc="$CC" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" \
    --disable-shared --enable-static --disable-programs --disable-doc --disable-debug \
    --disable-everything --disable-avdevice --disable-avfilter --disable-swscale --disable-network \
    --disable-jni --disable-mediacodec --disable-zlib --disable-bzlib --disable-lzma \
    --disable-iconv --disable-sdl2 --enable-decoder=alac --enable-decoder=aac \
    --enable-parser=aac --enable-protocol=file --enable-small --extra-libs='-lm -ldl'
  make -j"${JOBS:-2}"
  make install
fi

if [ ! -s "$prefix/lib/libuuid.a" ]; then
  copy_source util-linux-2.40.4
  ./configure --build=x86_64-pc-linux-gnu --host="$host" --prefix="$prefix" \
    --disable-all-programs --enable-libuuid --disable-shared --enable-static --disable-year2038
  make -j"${JOBS:-2}"
  make install
fi

for library in libpopt.a libconfig.a libsodium.a libgpg-error.a libgcrypt.a \
  libplist-2.0.a libssl.a libcrypto.a libavutil.a libavcodec.a libavformat.a \
  libswresample.a libuuid.a; do
  test -s "$prefix/lib/$library"
done
printf 'built Android dependencies in %s\n' "$prefix"
