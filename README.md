# Shairport AP2 Android

A rooted Android app that turns the phone into an AirPlay 2 receiver: Shairport Sync 5.5.1 (AirPlay 2) and NQPTP 1.2.8, cross-compiled with the NDK and run as root children of the app. Verified on a rooted POCO F1 (LineageOS, Android 15, Magisk): discovered and played from an iPhone.

This is deliberately **not** an upstream source mirror: `make fetch` downloads pinned sources into ignored `third_party/` and `make patch` applies the Android patch stack.

## How it works

- `MainActivity` (classic XML preferences) asks Magisk for `su`; without root every setting stays disabled.
- `ReceiverService` (foreground, media playback) writes `shairport-sync.conf` to external app storage and starts one root supervisor shell through `su`. The supervisor runs the APK-packaged `libnqptp.so` and `libshairport_sync.so` executables and lives exactly as long as Shairport. Closing its stdin (service stop, or the app dying) stops everything; Shairport gets SIGKILL if SIGTERM hangs (Bionic can't cancel threads blocked in `recvfrom`).
- Audio: on Android 8+ Shairport plays through **AAudio** itself (patch `0004`), reporting the real output delay so AirPlay 2 timing and multi-room stay in sync. On Android 7 (or when *Audio output* is set to AudioTrack) Shairport's `stdout` backend is piped into an `AudioTrack` in the app, which has no delay feedback.
- Discovery: TinySVCmDNS inside Shairport, as root, with the app's Wi-Fi `MulticastLock`. Its host name is `shairport-<MAC>.local` because Android reports `localhost` (patch `0003`).
- Volume: Shairport runs with `ignore_volume_control` and sends metadata over loopback UDP. The sender's slider sets Android's music volume; Android volume keys are sent back with DACP `setproperty?dmcp.device-volume=` (unverified with iOS AirPlay 2 sessions, see HANDOFF).
- Wi-Fi: while running, the supervisor forces Wi-Fi hi-perf mode (`cmd wifi force-hi-perf-mode`): with the screen off the POCO otherwise stops answering TCP/ARP. A Wi-Fi reconnect or address change restarts the engine and re-acquires the multicast lock; a crash after 30 s of uptime restarts it too.

## Build

Everything compiles in Docker (`shairport-echo-deps:local` for Shairport and its static dependencies, `shairplay-android-builder:latest` for NQPTP and Gradle), offline:

```sh
make fetch patch
./scripts/build-android-docker.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first run builds the arm64 dependency prefix (`build/deps/arm64-v8a`, resumable); armv7 dependencies come prebuilt in the deps image. Shairport and NQPTP builds are incremental. `~/.android` is mounted so the debug signing key stays stable across builds.

`tests/aaudio/run.sh [serial]` checks the AAudio backend on a rooted API 26+ device, writing only zeros.

## Layout

- `app/`: Kotlin app (`MainActivity`, `ReceiverService`, `VolumeSync`).
- `patches/`: Android patch stack, in order; see [`patches/README.md`](patches/README.md).
- `scripts/`: fetch/patch, Docker builds (`build-android-docker.sh`, `build-shairport-android.sh`, `build-shairport-deps-android.sh`, `build-nqptp-android.sh`).
- `diario.md`: night log of decisions, measurements and dead ends.

The Echo raw-ALSA backend, Echo controls, LED controller, Rust artifacts, installer and TWRP files belong to the device-specific `shairport-echo` project and are intentionally excluded.
