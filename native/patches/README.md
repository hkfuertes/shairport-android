# Patches

The Dockerfile's `engine-src` stage applies each directory's patches, in file-name order, to the upstream commit it pins (with its SHA-256). Don't rebase them onto another revision silently.

## shairport-sync

- `0001`–`0003`: Bionic library discovery (`AC_SEARCH_LIBS`), cooperative pthread cancellation and shared-memory compatibility, copied from `shairport-echo` commit `395aded` (before that project moved to static musl). The cancellation shim wakes threads with `SIGRTMIN`: ART keeps `SIGUSR1` blocked in an app process.
- `0004`: the metadata socket sender without `bzero`, which Bionic lacks.
- `0005`: the cancellation checks Bionic needs in AirPlay 1 receivers, the activity monitor, the metadata queue and the player's frame wait. Without them a stop, or a sender ending its session, never finishes.
- `0006`: configurable `general.model`.
- `0007`: `aaudio` output backend (`--with-aaudio`): plays through AAudio (`libaaudio.so`, loaded at run time) and reports the real output delay from `AAudioStream_getTimestamp()`, so Shairport keeps sync as with ALSA.
- `0008`: Shairport Sync as the app's JNI library (`android.c`: `main()` becomes `shairport_main()`, stderr goes to logcat, the process ends with `_exit()` after Shairport's own cleanup) and the `android` mDNS backend, which registers the services with the app's `NsdManager`. `get_device_id()` no longer waits 10 s for a MAC address, which an app can never read.
- `0009`: the `aaudio` backend hands every timed buffer, with the time Shairport says it is heard, to the app's Snapcast server for satellites (`Engine.satelliteAudio`, on Java's clock), and an empty one on a flush. The AAudio buffer holds the whole desired length, the satellites' head start. Off unless the app turns it on (`Engine.relayAudio`).

## nqptp

- `0001`–`0002`: Bionic library discovery and shared-memory compatibility, including `NQPTP_SHM_DIRECTORY` for Android app storage.

Never copy code from `echo-airplay2`: its licence forbids commercial use.
