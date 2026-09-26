# Handoff: Shairport

## State

The app is `com.hkfuertes.shairport` ("Shairport"; renamed from `com.hkfuertes.shairportap2`, so Magisk had to grant it again). It is a working AirPlay 2 receiver on the rooted POCO F1 (`40e396f`, arm64, Android 15, Magisk), verified from an iPhone with both audio outputs. Its settings can be changed over adb (README, "Configure from ADB"), and the advertised name defaults to Android's device name. Do not touch the Echo device.

`diario.md` has the chronological log with measurements; read it before changing the engine lifecycle or Wi-Fi handling.

## Architecture decisions

1. **Shairport runs as a root executable, not through JNI.** It needs root anyway (NQPTP binds 319/320, TinySVCmDNS 5353, Wi-Fi hi-perf), and a JNI-linked Shairport would need its `main()`/`exit()`/global state reworked. The JNI bridge was removed.
2. **Audio:** AAudio from the root process (patch `0004`) gives Shairport a real `delay()`, which AirPlay 2 sync and multi-room need. The `stdout` → app `AudioTrack` pipe remains for API 25 and as a user-selectable fallback; it has no delay feedback, so the DAC clock drifts against the sender over long sessions.
3. **Lifecycle:** one `su` supervisor per engine. Magisk passes the app's pipes straight to the root shell, but its `su` client keeps its own stdout open until the root shell exits, so the supervisor must exit when Shairport does. Its stdin is the stop signal (EOF on normal stop or app death); Shairport then exits on SIGTERM in well under a second (SIGKILL after 3 s remains as a safety net). SIGPIPE is ignored so cleanup survives the app. Leftover engines are killed by name (`pidof`; never `pkill -f`, which matches the supervisor's own command line).
4. **NQPTP shared memory** lives in external app storage (`/data/media/<user>/Android/data/<pkg>/files/nqptp-shm` for root): Magisk root cannot write the app's `filesDir` under SELinux, and the app UID cannot bind port 319.
5. **Wi-Fi:** with the screen off the POCO stops answering TCP/ARP (mDNS still works), and app Wi-Fi locks are downgraded to screen-on-only low-latency locks on API 34+, so the supervisor enables `cmd wifi force-hi-perf-mode`; service stop disables it (a crash leaves it on until the next normal stop). After a Wi-Fi reconnect the POCO filters multicast again despite the held lock, so the lock is re-acquired and the engine restarted.

## Open items

- **Android → iPhone volume is deliberately not implemented** (a DACP `dmcp.device-volume` attempt was removed). Receiver-to-sender volume in AirPlay 2 is not in a stable Shairport release (upstream only declares `ap2_event_send_unit_volume_notification()`), and pushing volume from one speaker misbehaves in multi-room sessions. Add it once Shairport ships it stable.
- Shairport sync statistics are logged (`diagnostics.statistics`) until AAudio is proven; drop them afterwards.
- Validate AAudio with real AirPlay 2 realtime and buffered streams, multi-room next to another speaker, track skips (flush closes/reopens the stream), output device changes (Bluetooth), and hour-long sessions.
- pyatv cannot exercise this build (AP2 uses NTP timing; classic RAOP sends L16, which this build fails to decode). Testing needs an Apple sender.
- Boot start is not implemented (Android 15 restricts media-playback foreground services from `BOOT_COMPLETED`).

## Patch decisions

See `patches/README.md` for order and provenance. `patches/reference/0003-echo-*` is only an example of backend integration points and must not be applied. Never copy code from `echo-airplay2` (non-commercial upstream licence).
