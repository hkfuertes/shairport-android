# Shairport AP2 Android

Seed repository for running Shairport Sync AirPlay 2 on Android through JNI and `AudioTrack`.

This is deliberately **not** an upstream source mirror and is not buildable yet. `make fetch` downloads pinned Shairport Sync and NQPTP source trees into ignored `third_party/`; `make patch` applies only the Android-compatible patch stack. The future `audio_audiotrack` backend and Android app/service do not exist yet.

```sh
make fetch
make patch
```

## Included

- Shairport Sync 5.5.1 and NQPTP 1.2.8 pins in [`upstream.env`](upstream.env).
- Bionic compatibility patches needed for API 25, retained from the pre-musl Echo tree.
- TinySVCmDNS AirPlay 2 TXT and configurable advertised-model patches.
- An `audiotrack` configuration example and an explicit patch order.

## Intentionally excluded

The Echo raw-ALSA backend, Echo controls, LED controller, Rust artifacts, installer and TWRP files belong to the device-specific project and do not help an `AudioTrack` port. Its raw-ALSA patch remains under `patches/reference/` only as an audio-backend integration example; do not apply it.

See [`HANDOFF.md`](HANDOFF.md) for the first implementation plan and Android-specific constraints.
