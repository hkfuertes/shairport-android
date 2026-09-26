#!/bin/sh
# Shairport Sync's static dependencies for one Android ABI, into /opt/deps/<ABI>.
# Runs inside the Dockerfile: tarballs in /dl, NDK clang on PATH. Usage: build-deps.sh ABI
set -eu

api=25 # the app's minSdk
case "${1:-}" in
  arm64-v8a) host=aarch64-linux-android cc=aarch64-linux-android$api-clang
    openssl=android-arm64 cpu='--arch=aarch64 --cpu=armv8-a' ;;
  armeabi-v7a) host=arm-linux-androideabi cc=armv7a-linux-androideabi$api-clang
    openssl=android-arm cpu='--arch=arm --cpu=armv7-a' ;;
  *) echo "usage: $0 arm64-v8a|armeabi-v7a" >&2; exit 2 ;;
esac
prefix=/opt/deps/$1
jobs=$(nproc)
export CC=$cc CXX=$cc++ AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip
export CFLAGS='-O2 -fPIC' CXXFLAGS='-O2 -fPIC' CPPFLAGS=-I$prefix/include LDFLAGS=-L$prefix/lib
export PKG_CONFIG_LIBDIR=$prefix/lib/pkgconfig PKG_CONFIG_PATH=$prefix/lib/pkgconfig
static="--build=x86_64-pc-linux-gnu --host=$host --prefix=$prefix --disable-shared --enable-static"

# A fresh tree per ABI.
unpack() {
  rm -rf "/tmp/$1" && mkdir "/tmp/$1" && cd "/tmp/$1"
  tar -xf /dl/"$1".tar.* --strip-components=1
}

unpack popt
autoreconf -fi
ac_cv_header_glob_h=no ./configure $static # Bionic's glob() needs API 28
make -j"$jobs" && make install

unpack libconfig
autoreconf -fi
./configure $static --disable-cxx
make -j"$jobs" && make install

unpack libsodium
./configure $static
make -j"$jobs" && make install

unpack libgpg-error
# ponytail: 1.51 ships Bionic lock metadata only for 32-bit ARM; arm64's mutex is 40 bytes.
[ "$1" = armeabi-v7a ] || sed 's/_priv\[4\]/_priv[40]/' src/syscfg/lock-obj-pub.arm-unknown-linux-androideabi.h \
  > src/syscfg/lock-obj-pub.aarch64-unknown-linux-android.h
./configure $static --disable-nls --disable-doc
make -j"$jobs" && make install
printf '%s\n' '#include <pthread.h>' '#include <gpg-error.h>' \
  '_Static_assert(sizeof(pthread_mutex_t) == sizeof(((gpgrt_lock_t *)0)->u._priv), "gpg-error lock size");' \
  | $CC -I"$prefix/include" -x c -c -o /dev/null -

unpack libgcrypt
PATH=$prefix/bin:$PATH ./configure $static --disable-doc --disable-tests --disable-asm
make -j"$jobs" && make install

unpack libplist
./configure $static --without-cython
make -j"$jobs" && make install

unpack openssl
./Configure "$openssl" -D__ANDROID_API__=$api no-shared no-tests no-zlib --prefix="$prefix" --openssldir="$prefix/ssl"
make -j"$jobs" build_libs && make install_dev

unpack ffmpeg
./configure --prefix="$prefix" --target-os=android $cpu --enable-cross-compile \
  --cc="$CC" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" \
  --disable-shared --enable-static --disable-programs --disable-doc --disable-debug \
  --disable-everything --disable-avdevice --disable-avfilter --disable-swscale --disable-network \
  --disable-jni --disable-mediacodec --disable-zlib --disable-bzlib --disable-lzma \
  --disable-iconv --disable-sdl2 --enable-decoder=alac --enable-decoder=aac \
  --enable-parser=aac --enable-protocol=file --enable-small --extra-libs='-lm -ldl'
make -j"$jobs" && make install

unpack util-linux
./configure $static --disable-all-programs --enable-libuuid --disable-year2038
make -j"$jobs" && make install
