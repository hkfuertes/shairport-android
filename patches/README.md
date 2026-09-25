# Patch layout

`make patch` applies these patches, in order, to the source trees fetched by `make fetch`:

1. `shairport-sync/android/`: Bionic library discovery, cooperative cancellation, and shared-memory compatibility. They were copied from `shairport-echo` commit `395aded`, before that project moved to static musl.
2. `nqptp/`: Bionic library and shared-memory compatibility, from the same commit.
3. `shairport-sync/0001-*`: TinySVCmDNS AirPlay 2 TXT registration.
4. `shairport-sync/0002-*`: configurable `general.model`.

`reference/0003-echo-*` is **not applied**. It is the previous raw Echo ALSA backend and is useful only as a small, concrete example of Shairport's audio-backend integration points. Replace it with a new `audio_audiotrack` patch.

All active patches target the SHA-pinned upstream sources in `../upstream.env`; do not silently rebase them to another upstream revision.
