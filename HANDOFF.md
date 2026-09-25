# Handoff: Shairport AP2 Android / JNI AudioTrack

## Goal

Port Shairport Sync AirPlay 2 to an Android app that owns audio through JNI + Java `AudioTrack`. The intended first target is the rooted Android 7.1.2 / API 25 Echo hardware, but root is a deployment aid, not a substitute for an app-process audio design.

## Seed state

Repository: `/home/hkfuertes/projects/shairport-ap2-android`

It was initialized as a new local Git repository on `main`; it has no commit or remote yet. No Android app, JNI implementation, or compiled binary has been started.

The seed deliberately does **not** track upstream source trees. Its current useful entry points are:

- `README.md`: scope and intentionally omitted Echo-specific code.
- `upstream.env`: exact Shairport Sync 5.5.1 and NQPTP 1.2.8 archive pins and SHA-256 values.
- `Makefile`, `scripts/fetch-upstream.sh`, `scripts/apply-patches.sh`: download and patch sources.
- `config/shairport-sync.conf.example`: desired eventual `output_backend = "audiotrack"` shape.
- `patches/README.md`: ownership and application order for every patch.

Validation already performed from this repository:

```sh
make fetch patch
```

It checksum-verified both archives and cleanly applied all seven active patches. Run `make clean` before a fresh re-application; `third_party/` is ignored and should not be committed.

## Patch decisions

Active patches are copied from `shairport-echo`:

1. `patches/shairport-sync/android/0001-*` through `0003-*`: Bionic/API-25 discovery, cooperative cancellation, and shared-memory compatibility.
2. `patches/nqptp/0001-*`, `0002-*`: Bionic compatibility for NQPTP.
3. `patches/shairport-sync/0001-*`: TinySVCmDNS AirPlay 2 TXT registration.
4. `patches/shairport-sync/0002-*`: `general.model` configuration.

`patches/reference/0003-echo-add-raw-ALSA-backend-with-external-controls.patch` is copied only as a compact example of Shairport audio-backend integration points. It must **not** be applied: it depends on raw Echo ALSA, Rust artifacts and hardware routing that do not belong in an AudioTrack port.

Do not revive the old AP2 reverse-event or embedded Echo-controls patches. The prior project deliberately removed them.

## Architecture constraints

1. **AudioTrack has to live in the app process.** A root external `shairport-sync` daemon cannot directly create a Java `AudioTrack`. The simplest viable architecture is Shairport + the new audio backend as native threads loaded by the app's JNI library. A root daemon would need an explicit Binder/JNI audio proxy and is not the first implementation.
2. **NQPTP is the main platform spike.** AP2 needs its timing service. Decide whether it can run as an in-process library/thread or as a separately launched rooted executable. Android app packages cannot freely `exec()` native binaries from the APK, and app-UID SELinux/socket limits must be measured. Do this before writing a complete AudioTrack backend.
3. **Keep real latency feedback.** Multi-room depends on the output backend's delay/latency accounting. Do not replace it with a pipe. Use `AudioTrack.getPlaybackHeadPosition()` with 32-bit wrap handling, and establish the fixed device/output latency by measurement.
4. **Multicast must be explicit.** If using TinySVCmDNS, the Java foreground service needs a `WifiManager.MulticastLock`; otherwise Android Wi-Fi filtering can break discovery. Java `NsdManager` is an alternative, but would require a different native mDNS integration.
5. **API 25 Bionic lacks normal pthread cancellation.** The copied cancellation patch is intentionally active. Test teardown, buffered AP2 teardown and sender-kill behaviour early; do not assume the previous static-musl result transfers to an NDK `.so`.
6. **Root is still useful** for installation, diagnostics and possibly NQPTP permissions, but not as the AudioTrack ownership model.

## First implementation order

1. Create the smallest Android Gradle app: API 25 minimum, a foreground service, JNI library loading, Wi-Fi multicast permission/lock, and no UI beyond start/stop/log visibility.
2. Build a dependency spike for `armv7a-linux-androideabi25`: Shairport AP2 dependencies plus NQPTP. Keep the fetched source trees untracked and introduce CMake/ndk-build only after the dependency list is reproducible.
3. Run an NQPTP permission/lifecycle probe under the actual app UID and, separately if useful, under `su`. Record ports, shared-memory path and SELinux failures.
4. Add a new Shairport patch named `audio_audiotrack`: configure switch, `Makefile.am` registration, `audio.c` registration and `audio_audiotrack.c`. Start with 48 kHz, stereo, S16_LE and blocking writes.
5. Define one narrow Java bridge owned by a dedicated audio thread: create/start, blocking write of direct PCM buffers, flush/stop/release, playback-head query and error reporting. Hold global references safely and attach native-created threads to the JVM.
6. Only after local playback works, tune delay reporting and validate AP2 realtime and buffered streams, multi-room, sender kill and Android activity/service death.

## Known references

- Source patch provenance and exact order: `patches/README.md`.
- Current working Echo project: `/home/hkfuertes/projects/shairport-echo`, main at the `v0.1.0` release.
- The Android patch source was `shairport-echo` commit `395aded`; current portable patches are from its main branch.
- Never copy code from `echo-airplay2`: it derives from a non-commercial licensed upstream. Ideas may be reimplemented independently.

## Suggested next-session skills

Use `diagnose` for the first NQPTP/NDK/AudioTrack device spike. Use normal code work for the minimal Gradle/JNI skeleton; do not attempt the entire port in one pass.
