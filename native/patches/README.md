# Patch layout

The Dockerfile's `engine` stage applies these patches, in order, to the upstream sources it downloads:

1. `shairport-sync/android/`: Bionic library discovery, cooperative cancellation, and shared-memory compatibility. They were copied from `shairport-echo` commit `395aded`, before that project moved to static musl. `0004` replaces `bzero` (absent from Bionic) in the metadata socket sender. `0005` adds the cancellation checks Bionic needs for AirPlay 1 receivers, the activity monitor and the metadata queue (otherwise SIGTERM never finishes).
2. `nqptp/`: Bionic library and shared-memory compatibility, including `NQPTP_SHM_DIRECTORY` for Android app storage.
3. `shairport-sync/0001-*`: TinySVCmDNS AirPlay 2 TXT registration.
4. `shairport-sync/0002-*`: configurable `general.model`.
5. `shairport-sync/0003-*`: TinySVCmDNS host name `shairport-<MAC>.local` when the system reports `localhost` (always on Android; the POCO kernel has no UTS namespaces).
6. `shairport-sync/0004-*`: `aaudio` output backend (`--with-aaudio`): plays from the root process through AAudio (`libaaudio.so` loaded at run time, API 26+) and reports the real output delay from `AAudioStream_getTimestamp()`, so Shairport keeps sync as with ALSA.
7. `shairport-sync/0005-*`: TinySVCmDNS keeps its own copy of AAAA addresses. Upstream stores a pointer into the caller's `getifaddrs()` list (freed right after registration: use-after-free in AAAA answers) and frees that interior pointer at shutdown, which Android's Scudo allocator turns into SIGABRT.

All patches target the commits pinned (with their SHA-256) in the Dockerfile; do not silently rebase them to another upstream revision.
