# Shairport AP2 Android

Seed repository for running Shairport Sync AirPlay 2 on Android through JNI and `AudioTrack`.

This is deliberately **not** an upstream source mirror. `app/` is a buildable Kotlin skeleton: a classic XML preference activity requests `su` on launch, keeps its settings disabled without root, writes `shairport-sync.conf`, and controls a foreground service with a Wi-Fi multicast lock. It packages and runs NQPTP as a rooted native executable for both supported ABIs; JNI verifies its shared-memory mapping and probes `AudioTrack`. It does **not** yet link or run Shairport, so it does not advertise or receive AirPlay.

`make fetch` downloads pinned Shairport Sync and NQPTP source trees into ignored `third_party/`; `make patch` applies only the Android-compatible patch stack.

```sh
make fetch
make patch
```

Build the APK only through Docker:

```sh
./scripts/build-android-docker.sh
```

The script mounts `~/.android`, preserving the debug signing key so `adb install -r` works across builds.

## Included

- Shairport Sync 5.5.1 and NQPTP 1.2.8 pins in [`upstream.env`](upstream.env).
- Bionic compatibility patches needed for API 25, retained from the pre-musl Echo tree.
- TinySVCmDNS AirPlay 2 TXT and configurable advertised-model patches.
- An `audiotrack` configuration example and an explicit patch order.
- A minimal API-25 Kotlin app, XML preferences, root gate, foreground notification, and generated runtime configuration.
- JNI and `AudioTrack` bridges for `armeabi-v7a` and `arm64-v8a`.
- [`scripts/build-android-docker.sh`](scripts/build-android-docker.sh), the Docker-only APK build entry point.
- [`scripts/build-nqptp-android.sh`](scripts/build-nqptp-android.sh), which cross-builds NQPTP for `armeabi-v7a` and `arm64-v8a`; Docker packages it as an extracted native executable.

## Intentionally excluded

The Echo raw-ALSA backend, Echo controls, LED controller, Rust artifacts, installer and TWRP files belong to the device-specific project and do not help an `AudioTrack` port. Its raw-ALSA patch remains under `patches/reference/` only as an audio-backend integration example; do not apply it.

See [`HANDOFF.md`](HANDOFF.md) for the first implementation plan and Android-specific constraints.
