# Diario — Shairport AP2 Android port

Goal: functional AirPlay 2 receiver app on the rooted POCO F1 (arm64, API 35).
Never touch the Echo device. Builds only via Docker. Commit each milestone on
`feat/android-foreground-service`.

## State at start of night session (2026-09-26 01:33)

Done & committed (eb61697):
- Kotlin app, classic PreferenceActivity, root gate via `su`, foreground service.
- JNI lib `libshairport_ap2.so` (bridge + AudioTrack probe), both ABIs.
- NQPTP packaged as `libnqptp.so`, root-launched via supervisor shell,
  shm in `/data/media/0/Android/data/<pkg>/files/nqptp-shm` (external app storage),
  JNI maps it fine on POCO. Supervisor dies with app.

Found:
- Shairport AP2 (dummy backend, tinysvcmdns) **links for armv7** inside
  `shairport-echo-deps:local` with `LDFLAGS=-L/opt/armv7-android/lib LIBS=-lgpg-error`
  and `ac_cv_func_malloc_0_nonnull=yes ac_cv_func_realloc_0_nonnull=yes`.
- `shairport-echo-deps:local` has verified sources in /src and armv7 static deps in
  /opt/armv7-android. arm64 deps must be built: `scripts/build-shairport-deps-docker.sh`.
- libgpg-error 1.51 lacks aarch64 Android lock header -> generated from arm one
  with `_priv[40]` (+ static assert).
- OpenSSL arm64 failed linking `providers/legacy.so` against stale armv7 objects
  in copied /src tree -> copy_source now deletes *.o/*.a etc; use `install_dev`.

## Plan
1. arm64 deps (in progress).
2. Build shairport-sync executable for both ABIs (dummy/stdout backend) and just
   run it as root child like NQPTP -> fastest path to a *working* receiver.
   Audio: Shairport `stdout`/pipe backend -> app-side AudioTrack? Or ALSA? Decide.
3. mDNS: tinysvcmdns inside Shairport as root (binds 5353) — try first; else NSD.
4. Validate on POCO with iPhone/Mac discovery (user asleep -> check with
   `avahi-browse`/`dns-sd` from host if possible).

## Log
- 01:33 arm64 deps build running (container 6a167c5028c0).
- 01:46 arm64 deps done (resumable script). Shairport 5.5.1 AP2 built for both ABIs
  (`build/shairport-bin/<abi>/libshairport_sync.so`, NEEDED only libc/libm/libdl
  thanks to `-static-libstdc++ -Wl,--as-needed`).
- 01:47 Kernel has no CONFIG_UTS_NS (unshare -u fails) and Android hostname is
  "localhost" -> patch 0003: tinysvcmdns uses `shairport-<MAC>.local`.
- 01:49 Manual root run on POCO (/data/local/tmp, run.sh): NQPTP found ->
  "Startup in AirPlay 2 mode", device id = wlan0 MAC 76:60:75:51:a7:ec.
  avahi-browse on host sees `_airplay._tcp` + `_raop._tcp` "SP Test POCO" at
  192.168.77.237:7000 with AP2 TXT. tinysvcmdns binds 5353 fine as root.
  Gotchas: `pkill -f` kills its own su shell; `log_output_to` is obsolete in 5.5.1.
- Decision: drop JNI. Engine = root child (`su`), audio = Shairport `stdout`
  backend (S16_LE/44100/2ch) piped through su's stdout into Kotlin AudioTrack.
  Supervisor exits when its stdin (pipe from app) hits EOF -> no PID files/polling.
- 01:58 APK (root engine + Kotlin AudioTrack pump, no JNI) installed on POCO:
  supervisor sh -> libnqptp.so + libshairport_sync.so, :7000 + :5353, advertised as
  "Shairport AP2 Android" with AP2 TXT. Force-stop cleans both (stdin EOF works).
  pyatv can't test AP2 (uses NTP timing; Shairport: "can not handle NTP streams").
- 02:00 **USER CONFIRMED: audio from iPhone works great.** User asleep: DO NOT PLAY
  ANY SOUND (no pyatv/stream tests). Next request: bidirectional volume sync
  (Android volume keys -> iPhone slider, iPhone slider -> Android stream volume).
- 02:05 Volume sync design: Shairport `ignore_volume_control` + metadata over UDP
  (`--with-metadata-multicast`, socket 127.0.0.1:<app port>) -> `pvol` sets
  STREAM_MUSIC; Android VOLUME_CHANGED -> DACP `setproperty?dmcp.device-volume=`
  (same command Shairport's D-Bus/MPRIS use) to `clip` IP + port resolved from
  `iTunes_Ctrl_<DACP-ID>._dacp._tcp` via NsdManager. Upstream has no AP2 event-channel
  volume notification (`ap2_event_send_unit_volume_notification` declared, never
  implemented in any branch). Android->iPhone UNVERIFIED (needs iPhone; Shairport docs
  say DACP remote control is "Classic AirPlay only").
- Patch android/0004: Bionic has no `bzero` (metadata/multicast.c).
- Bugs fixed: (1) Shairport hangs on SIGTERM with a stuck session (pthread_join chain
  waiting on threads in recvfrom; Bionic can't cancel) -> watcher SIGKILLs after 3 s.
  (2) Magisk su client keeps stdout open -> app sees EOF only when supervisor exits ->
  supervisor now lives exactly as long as Shairport (`wait $shairport`). (3) NQPTP
  inherited fd 3 (audio pipe). (4) SIGPIPE on echo after app death killed the
  supervisor before it killed NQPTP -> `trap '' PIPE`. (5) getLoopbackAddress() = ::1.
- Verified (no sound): kill -9 shairport -> supervisor exits, NQPTP gone, app notices;
  force-stop -> nothing left; simulated `pvol -15` -> music 13/25; simulated
  daid/acre + volume change -> NSD resolve attempted. Volume restored to 25/25.
- Shell `cmd media_session volume --set` is blocked by appops on this ROM; use `su -c`.
- 03:05 Found: screen off -> Wi-Fi power save, host ping 100% loss / 60-860 ms RTT,
  mDNS unanswered (not Doze: charging, deviceidle ACTIVE; kernel wakelock didn't help).
  Adding WIFI_MODE_FULL_HIGH_PERF WifiLock.
- 03:20 App-held WifiLock HIGH_PERF is silently downgraded to LOW_LATENCY on API 34+
  (type=4, screen-on only) -> useless. Root `cmd wifi force-hi-perf-mode enabled`
  fixes it: screen off, TCP :7000 went from 5/5 FAIL to 5/5 OK (~123 ms), mDNS OK.
  (Without it mDNS still answered but TCP/ARP didn't: senders would see us, not connect.)
  Supervisor enables it; service onDestroy disables it (crash/force-stop leaves it on).
- 03:25 Race: new engine started while the killed app's old NQPTP was still dying ->
  port 319 busy -> Shairport fell back to classic mode. Supervisor now kills leftovers
  by name (`pidof`, never `pkill -f`: it matches the supervisor's own command line).
