# Handoff: Shairport AP2 Android

## State

Branch `feat/android-foreground-service`. The app is a working AirPlay 2 receiver on the rooted POCO F1 (`40e396f`, arm64, Android 15, Magisk): discovery and playback from an iPhone were confirmed by the user with the AudioTrack pipe; the AAudio backend (now the default on Android 8+) passed the silent device check but still needs a real AirPlay session. Do not touch the Echo device.

`diario.md` has the chronological log with measurements; read it before changing the engine lifecycle or Wi-Fi handling.

## Architecture decisions

1. **Shairport runs as a root executable, not through JNI.** It needs root anyway (NQPTP binds 319/320, TinySVCmDNS 5353, Wi-Fi hi-perf), and a JNI-linked Shairport would need its `main()`/`exit()`/global state reworked. The JNI bridge was removed.
2. **Audio:** AAudio from the root process (patch `0004`) gives Shairport a real `delay()`, which AirPlay 2 sync and multi-room need. The `stdout` → app `AudioTrack` pipe remains for API 25 and as a user-selectable fallback; it has no delay feedback, so the DAC clock drifts against the sender over long sessions.
3. **Lifecycle:** one `su` supervisor per engine. Magisk passes the app's pipes straight to the root shell, but its `su` client keeps its own stdout open until the root shell exits, so the supervisor must exit when Shairport does. Its stdin is the stop signal (EOF on normal stop or app death); Shairport then exits on SIGTERM in well under a second (SIGKILL after 3 s remains as a safety net). SIGPIPE is ignored so cleanup survives the app. Leftover engines are killed by name (`pidof`; never `pkill -f`, which matches the supervisor's own command line).
4. **NQPTP shared memory** lives in external app storage (`/data/media/<user>/Android/data/<pkg>/files/nqptp-shm` for root): Magisk root cannot write the app's `filesDir` under SELinux, and the app UID cannot bind port 319.
5. **Wi-Fi:** with the screen off the POCO stops answering TCP/ARP (mDNS still works), and app Wi-Fi locks are downgraded to screen-on-only low-latency locks on API 34+, so the supervisor enables `cmd wifi force-hi-perf-mode`; service stop disables it (a crash leaves it on until the next normal stop). After a Wi-Fi reconnect the POCO filters multicast again despite the held lock, so the lock is re-acquired and the engine restarted.

## Open items

- **Android → iPhone volume** uses DACP `dmcp.device-volume` (what Shairport's own D-Bus/MPRIS use). Shairport documents DACP remote control as Classic-AirPlay-only; upstream declares `ap2_event_send_unit_volume_notification()` but no branch implements it. If iOS ignores DACP in AirPlay 2 sessions, the next step is reverse-engineering the AP2 event-channel volume notification.
- Shairport sync statistics are logged (`diagnostics.statistics`) until AAudio is proven; drop them afterwards.
- Validate AAudio with real AirPlay 2 realtime and buffered streams, multi-room next to another speaker, track skips (flush closes/reopens the stream), output device changes (Bluetooth), and hour-long sessions.
- pyatv cannot exercise this build (AP2 uses NTP timing; classic RAOP sends L16, which this build fails to decode). Testing needs an Apple sender.
- Boot start is not implemented (Android 15 restricts media-playback foreground services from `BOOT_COMPLETED`).

## Patch decisions

See `patches/README.md` for order and provenance. `patches/reference/0003-echo-*` is only an example of backend integration points and must not be applied. Never copy code from `echo-airplay2` (non-commercial upstream licence).
