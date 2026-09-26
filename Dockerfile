# syntax=docker/dockerfile:1
# The whole build. Upstream sources are downloaded here at pinned versions and checked against
# the checksums below; the repository keeps only its own code and patches (native/patches).
#
#   make                  APK + Kiosk Satellite plugin in build/ (default target: out)
#   make jnilibs          engine only, in build/jniLibs, for Gradle outside Docker
#
# Stages: ndk -> deps -> engine -> apk, and sdk -> plugin.

ARG DEBIAN=debian:bookworm@sha256:f37a335e82bca302e955fa39f9dfe28f1be618f016f8a2b56318e5a5111afc26

FROM ${DEBIAN} AS ndk
RUN apt-get update && apt-get install -y --no-install-recommends \
      autoconf automake autopoint bison bzip2 ca-certificates curl flex gettext libplist-utils \
      libtool make patch perl pkg-config python3 texinfo unzip xxd xz-utils \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL https://dl.google.com/android/repository/android-ndk-r27c-linux.zip -o /tmp/ndk.zip \
    && echo "090e8083a715fdb1a3e402d0763c388abb03fb4e  /tmp/ndk.zip" | sha1sum -c - \
    && unzip -q /tmp/ndk.zip -d /opt && rm /tmp/ndk.zip
ENV ANDROID_NDK_ROOT=/opt/android-ndk-r27c \
    PATH=/opt/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH

# Shairport Sync's static dependencies, for both ABIs.
FROM ndk AS deps
RUN <<'EOF'
set -eu
mkdir /dl
get() { curl -fsSL "$2" -o "/dl/$1" && echo "$3  /dl/$1" | sha256sum -c -; }
get popt.tar.gz https://github.com/rpm-software-management/popt/archive/refs/tags/popt-1.19-release.tar.gz 6eb40d650526cb9fe63eb4415bcecdf9cf306f7556e77eff689abc5a44670060
get libconfig.tar.gz https://github.com/hyperrealm/libconfig/archive/refs/tags/v1.8.1.tar.gz e95798d2992a66ecd547ce3651d7e10642ff2211427c43a7238186ff4c372627
get libsodium.tar.gz https://download.libsodium.org/libsodium/releases/libsodium-1.0.20.tar.gz ebb65ef6ca439333c2bb41a0c1990587288da07f6c7fd07cb3a18cc18d30ce19
get libgpg-error.tar.bz2 https://gnupg.org/ftp/gcrypt/libgpg-error/libgpg-error-1.51.tar.bz2 be0f1b2db6b93eed55369cdf79f19f72750c8c7c39fc20b577e724545427e6b2
get libgcrypt.tar.bz2 https://gnupg.org/ftp/gcrypt/libgcrypt/libgcrypt-1.10.3.tar.bz2 8b0870897ac5ac67ded568dcfadf45969cfa8a6beb0fd60af2a9eadc2a3272aa
get libplist.tar.bz2 https://github.com/libimobiledevice/libplist/releases/download/2.7.0/libplist-2.7.0.tar.bz2 7ac42301e896b1ebe3c654634780c82baa7cb70df8554e683ff89f7c2643eb8b
get openssl.tar.gz https://www.openssl.org/source/openssl-3.0.15.tar.gz 23c666d0edf20f14249b3d8f0368acaee9ab585b09e1de82107c66e1f3ec9533
get ffmpeg.tar.gz https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n8.1.2.tar.gz 9fd092511605bbebafe095ea6d38d9e40f34d12f7386e1258372df8be0576eb7
get util-linux.tar.xz https://www.kernel.org/pub/linux/utils/util-linux/v2.40/util-linux-2.40.4.tar.xz 5c1daf733b04e9859afdc3bd87cc481180ee0f88b5c0946b16fdec931975fb79
EOF
COPY native/build-deps.sh /usr/local/bin/
RUN build-deps.sh arm64-v8a
RUN build-deps.sh armeabi-v7a

# Shairport Sync and NQPTP: pinned upstream commits plus our patches, for both ABIs.
FROM deps AS engine
RUN <<'EOF'
set -eu
get() {
  curl -fsSL "https://github.com/mikebrady/$1/archive/$2.tar.gz" -o /tmp/src.tar.gz
  echo "$3  /tmp/src.tar.gz" | sha256sum -c -
  mkdir -p "/src/$1" && tar -xzf /tmp/src.tar.gz -C "/src/$1" --strip-components=1 && rm /tmp/src.tar.gz
}
get shairport-sync 7bad231c18368dbd26f298577f6210e36e4b0797 eab1fa095e34676d05f68e38d86501d5afa3fc46f83f044859d4d125d526daec
get nqptp c925f27c1fd12e4033ac477e5a405969b0b0260b d2c2fe5d2574d447a817b1585e82c38f4c98774dac8284e5a3f17e188a3a75f9
EOF
COPY native/patches /patches
# Order: shairport-sync/android/* (Bionic) before shairport-sync/*; see native/patches/README.md.
RUN for p in /patches/shairport-sync/android/*.patch /patches/shairport-sync/*.patch; do \
      patch -d /src/shairport-sync -p1 < "$p" || exit 1; done \
    && for p in /patches/nqptp/*.patch; do patch -d /src/nqptp -p1 < "$p" || exit 1; done \
    && (cd /src/shairport-sync && autoreconf -fi) && (cd /src/nqptp && autoreconf -fi)
COPY native/build-engine.sh /usr/local/bin/
RUN build-engine.sh arm64-v8a && build-engine.sh armeabi-v7a

FROM scratch AS jnilibs
COPY --from=engine /out /

# tests/aaudio/run.sh: the patched AAudio backend against a stand-in common.h.
FROM engine AS aaudio-check-build
COPY tests/aaudio /tests
RUN mkdir /tmp/check \
    && cp /src/shairport-sync/audio_aaudio.c /src/shairport-sync/audio.h /tests/common.h /tmp/check/ \
    && aarch64-linux-android25-clang -O1 -Wall -Wextra -I/tmp/check -I/opt/deps/arm64-v8a/include \
         -o /aaudio-check /tests/check.c -ldl

FROM scratch AS aaudio-check
COPY --from=aaudio-check-build /aaudio-check /

# JDK and Android SDK, for Gradle and the plugin's DEX build.
FROM ${DEBIAN} AS sdk
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl openjdk-17-jdk-headless python3 unzip \
    && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
RUN curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o /tmp/tools.zip \
    && echo "7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee  /tmp/tools.zip" | sha256sum -c - \
    && unzip -q /tmp/tools.zip -d /tmp && rm /tmp/tools.zip \
    && mkdir -p "$ANDROID_HOME/cmdline-tools" && mv /tmp/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest" \
    && yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null \
    && "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-35' 'build-tools;35.0.0' >/dev/null

FROM sdk AS apk
WORKDIR /work
COPY gradlew build.gradle settings.gradle gradle.properties ./
COPY gradle gradle
COPY app app
COPY --from=engine /out build/jniLibs
# make passes the host's debug keystore, so the signature (and adb install -r) stays stable.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=secret,id=debug_keystore,target=/root/.android/debug.keystore \
    ./gradlew --no-daemon --console=plain :app:assembleDebug

# Kiosk Satellite plugin: SDK interfaces and build tool from the pinned template repository.
FROM sdk AS plugin
RUN curl -fsSL https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/archive/8a070aca0815346fc2e68b29ed52e07c240d480e.tar.gz -o /tmp/ks.tar.gz \
    && echo "89c49fb096f5539ae8ffe65c32fe6f075f7109fb57e09014d454a1afa14efbbf  /tmp/ks.tar.gz" | sha256sum -c - \
    && mkdir /ks && tar -xzf /tmp/ks.tar.gz -C /ks --strip-components=1 && rm /tmp/ks.tar.gz
ENV LANG=C.UTF-8
COPY kiosk-plugin /plugin
ARG PLUGIN_VERSION
RUN mkdir /tmp/test \
    && javac --release 8 -d /tmp/test $(find /ks/sdk/src /plugin/src /plugin/test -name '*.java') \
    && java -ea -cp /tmp/test com.hkfuertes.shairport.kiosk.ShairportPluginTest \
    && python3 /ks/tools/build.py /plugin --android-platform 35 ${PLUGIN_VERSION:+--version "$PLUGIN_VERSION"}

FROM scratch AS plugin-out
COPY --from=plugin /plugin/dist/*.zip /plugin/dist/*.zip.sha256 /plugin/dist/kiosk-satellite-plugin.json /

FROM scratch AS out
COPY --from=apk /work/app/build/outputs/apk/debug/app-debug.apk /
COPY --from=plugin-out / /kiosk-plugin/
