# Patch layout

`make patch` applies these patches, in order, to the source trees fetched by `make fetch`:

1. `shairport-sync/android/`: Bionic library discovery, cooperative cancellation, and shared-memory compatibility. They were copied from `shairport-echo` commit `395aded`, before that project moved to static musl. `0004` replaces `bzero` (absent from Bionic) in the metadata socket sender.
2. `nqptp/`: Bionic library and shared-memory compatibility, including `NQPTP_SHM_DIRECTORY` for Android app storage.
3. `shairport-sync/0001-*`: TinySVCmDNS AirPlay 2 TXT registration.
4. `shairport-sync/0002-*`: configurable `general.model`.
5. `shairport-sync/0003-*`: TinySVCmDNS host name `shairport-<MAC>.local` when the system reports `localhost` (always on Android; the POCO kernel has no UTS namespaces).
6. `shairport-sync/0004-*`: `aaudio` output backend (`--with-aaudio`): plays from the root process through AAudio (`libaaudio.so` loaded at run time, API 26+) and reports the real output delay from `AAudioStream_getTimestamp()`, so Shairport keeps sync as with ALSA.

`reference/0003-echo-*` is **not applied**. It is the previous raw Echo ALSA backend, kept only as a small example of Shairport's audio-backend integration points (`0004` followed the same pattern for AAudio).

All active patches target the SHA-pinned upstream sources in `../upstream.env`; do not silently rebase them to another upstream revision.
