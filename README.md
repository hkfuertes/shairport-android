# Shairport AP2 Android

Seed repository for running Shairport Sync AirPlay 2 on Android through JNI and `AudioTrack`.

This is deliberately **not** an upstream source mirror. `app/` is a buildable Kotlin skeleton: a classic XML preference activity requests `su` on launch, keeps its settings disabled without root, writes `shairport-sync.conf`, and controls a foreground service with a Wi-Fi multicast lock. It does not yet include JNI, NQPTP, or a working AirPlay receiver.

`make fetch` downloads pinned Shairport Sync and NQPTP source trees into ignored `third_party/`; `make patch` applies only the Android-compatible patch stack.

```sh
make fetch
make patch
```

## Included

- Shairport Sync 5.5.1 and NQPTP 1.2.8 pins in [`upstream.env`](upstream.env).
- Bionic compatibility patches needed for API 25, retained from the pre-musl Echo tree.
- TinySVCmDNS AirPlay 2 TXT and configurable advertised-model patches.
- An `audiotrack` configuration example and an explicit patch order.
- A minimal API-25 Kotlin app, XML preferences, root gate, foreground notification, and generated runtime configuration.

## Intentionally excluded

The Echo raw-ALSA backend, Echo controls, LED controller, Rust artifacts, installer and TWRP files belong to the device-specific project and do not help an `AudioTrack` port. Its raw-ALSA patch remains under `patches/reference/` only as an audio-backend integration example; do not apply it.

See [`HANDOFF.md`](HANDOFF.md) for the first implementation plan and Android-specific constraints.
