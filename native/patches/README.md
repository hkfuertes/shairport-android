# Patch layout

The Dockerfile's `engine-src` stage applies these patches, in order, to the upstream sources it downloads:

1. `shairport-sync/android/`: Bionic library discovery, cooperative cancellation, and shared-memory compatibility. They were copied from `shairport-echo` commit `395aded`, before that project moved to static musl. The cancellation shim wakes threads with `SIGRTMIN` (ART keeps `SIGUSR1` blocked in an app process). `0004` replaces `bzero` (absent from Bionic) in the metadata socket sender. `0005` adds the cancellation checks Bionic needs for AirPlay 1 receivers, the activity monitor, the metadata queue and the player's frame wait (otherwise a stop, or a sender ending its session, never finishes).
2. `nqptp/`: Bionic library and shared-memory compatibility, including `NQPTP_SHM_DIRECTORY` for Android app storage.
3. `shairport-sync/0001-*`: configurable `general.model`.
4. `shairport-sync/0002-*`: `aaudio` output backend (`--with-aaudio`): plays through AAudio (`libaaudio.so` loaded at run time) and reports the real output delay from `AAudioStream_getTimestamp()`, so Shairport keeps sync as with ALSA.
5. `shairport-sync/0003-*`: Shairport Sync as the app's JNI library (`android.c`: `main()` becomes `shairport_main()`, stderr goes to logcat, the process ends with `_exit()` after Shairport's own cleanup) with the `android` mDNS backend, which registers the services with the app's `NsdManager`. `get_device_id()` no longer waits 10 s for a MAC address, which an app can never read.

All patches target the commits pinned (with their SHA-256) in the Dockerfile; do not silently rebase them to another upstream revision.
