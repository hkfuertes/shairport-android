# Handoff: Shairport AP2 Android / JNI AudioTrack

## Goal

Port Shairport Sync AirPlay 2 to an Android app that owns audio through JNI + Java `AudioTrack`. The intended first target is the rooted Android 7.1.2 / API 25 Echo hardware, but root is a deployment aid, not a substitute for an app-process audio design.

## Seed state

Repository: `/home/hkfuertes/projects/shairport-ap2-android`

The initial seed is committed on `main`; current implementation work is on `feat/android-foreground-service`. It now has a Kotlin Android app, JNI/AudioTrack bridge, and an APK-packaged NQPTP timing process; a native Shairport binary is still absent.

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
2. `patches/nqptp/0001-*`, `0002-*`: Bionic compatibility for NQPTP, including an `NQPTP_SHM_DIRECTORY` override so it can share state with the app.
3. `patches/shairport-sync/0001-*`: TinySVCmDNS AirPlay 2 TXT registration.
4. `patches/shairport-sync/0002-*`: `general.model` configuration.

`patches/reference/0003-echo-add-raw-ALSA-backend-with-external-controls.patch` is copied only as a compact example of Shairport audio-backend integration points. It must **not** be applied: it depends on raw Echo ALSA, Rust artifacts and hardware routing that do not belong in an AudioTrack port.

Do not revive the old AP2 reverse-event or embedded Echo-controls patches. The prior project deliberately removed them.

## Architecture constraints

1. **AudioTrack has to live in the app process.** A root external `shairport-sync` daemon cannot directly create a Java `AudioTrack`. The simplest viable architecture is Shairport + the new audio backend as native threads loaded by the app's JNI library. A root daemon would need an explicit Binder/JNI audio proxy and is not the first implementation.
2. **NQPTP runs as a separate rooted executable.** It binds privileged UDP 319/320, so the app UID cannot run it. `libnqptp.so` is extracted from the APK, NQPTP receives a physical `/data/media/<user>/...` path, and JNI uses the app-visible external path for the same shared-memory file. A root supervisor polls the app PID to kill NQPTP after force-stop; Magisk otherwise orphans it.
3. **Keep real latency feedback.** Multi-room depends on the output backend's delay/latency accounting. Do not replace it with a pipe. Use `AudioTrack.getPlaybackHeadPosition()` with 32-bit wrap handling, and establish the fixed device/output latency by measurement.
4. **Multicast must be explicit.** If using TinySVCmDNS, the Java foreground service needs a `WifiManager.MulticastLock`; otherwise Android Wi-Fi filtering can break discovery. Java `NsdManager` is an alternative, but would require a different native mDNS integration.
5. **API 25 Bionic lacks normal pthread cancellation.** The copied cancellation patch is intentionally active. Test teardown, buffered AP2 teardown and sender-kill behaviour early; do not assume the previous static-musl result transfers to an NDK `.so`.
6. **Root is still useful** for installation, diagnostics and possibly NQPTP permissions, but not as the AudioTrack ownership model.

## First implementation order

1. **Done:** the API-25 Kotlin Gradle app, classic XML preferences, `su` gate, foreground service, notification and Wi-Fi multicast lock exist. The `ndk-build` JNI library loads an `AudioTrack` bridge and runs a silent create/play/write/release probe; native Shairport start/stop remains.
2. **Done:** Docker reproducibly builds NQPTP for `armeabi-v7a` and `arm64-v8a`, packages it in the APK, launches it as root, and verifies its shared memory from JNI on the POCO F1.
3. **Done:** the POCO F1 demonstrated the app UID cannot bind UDP 319; Magisk cannot write `filesDir`; external app storage works for shared memory; and the root supervisor removes NQPTP after force-stop.
4. Cross-build Shairport AP2 dependencies and link its core into `libshairport_ap2.so` without copying upstream sources.
5. Add a new Shairport patch named `audio_audiotrack`: configure switch, `Makefile.am` registration, `audio.c` registration and `audio_audiotrack.c`. Start with 48 kHz, stereo, S16_LE and blocking writes.
6. Add an `android-nsd` mDNS backend: Shairport must generate and pass both `_raop._tcp` and `_airplay._tcp` names/TXT records over JNI; do not advertise a hand-built partial AP2 record.
7. Only after local playback works, tune delay reporting and validate AP2 realtime and buffered streams, multi-room, sender kill and Android activity/service death.

## Known references

- Source patch provenance and exact order: `patches/README.md`.
- Current working Echo project: `/home/hkfuertes/projects/shairport-echo`, main at the `v0.1.0` release.
- The Android patch source was `shairport-echo` commit `395aded`; current portable patches are from its main branch.
- Never copy code from `echo-airplay2`: it derives from a non-commercial licensed upstream. Ideas may be reimplemented independently.

## Suggested next-session skills

Use `diagnose` for the first NQPTP/NDK/AudioTrack device spike. Use normal code work for the minimal Gradle/JNI skeleton; do not attempt the entire port in one pass.
