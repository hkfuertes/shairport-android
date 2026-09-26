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
- 03:45 Auto-restart when Shairport dies after >30 s uptime (verified: kill -9 ->
  new engine in ~3 s). Wi-Fi reconnect/IP change restarts the engine; ALSO the
  MulticastLock must be released+re-acquired: after `svc wifi disable/enable` the POCO
  received zero multicast (InMcastPkts frozen) despite the held lock. Verified fixed
  (mDNS 3/3 after toggle, TCP OK screen off).
- 03:50 **AAudio backend** (patch 0004, `--with-aaudio`): the stdout pipe gives Shairport
  no output-delay feedback -> DAC vs sender clock drift over long sessions + multiroom
  offset. AAudio works from the root native process (silent probe: 44.1k/2ch/I16,
  timestamps OK). Backend dlopen()s libaaudio.so (binary stays API 25), blocking writes
  into a 16384-frame deep buffer, delay() = written - presented (getTimestamp,
  CLOCK_MONOTONIC), flush/stop close the stream (clean counters), reopen on DISCONNECTED.
  `tests/aaudio/run.sh` = device check (zeros only): PASS, delay ~347 ms stable.
  App: API>=26 -> output_backend "aaudio" (stdout just signals exit); API 25 -> old pipe.
- pyatv can't exercise Shairport at all: AP2 uses NTP timing (unsupported), classic
  sends L16 which this build fails to decode (AVERROR_INVALIDDATA). Needs an Apple sender.
- A pyatv session that never tears down makes SIGTERM hang Shairport (seen twice): the
  watcher's SIGKILL covers the app engine; manual test instances need kill -9.
- 04:10 "Audio output" preference (AAudio default on 8+, AudioTrack pipe fallback) so the
  user can A/B in the morning. Docs rewritten (README/HANDOFF); obsolete config example
  removed. DACP volume sends are coalesced (holding a key queued 5 s resolves each).
- owntones (AP2 sender) only understands `sendMediaRemoteCommand` play/paus/nitm/pitm
  from speakers over the event channel; no volume. Upstream Shairport has no AP2 volume
  notification. => Android->iPhone volume stays DACP, UNVERIFIED (open item in HANDOFF).
- 04:20 Reproducibility: `git archive HEAD` + `make fetch patch` in /tmp/fresh gives a
  source tree identical to the one built here (only generated files differ). Full
  from-scratch Docker build started in /tmp/fresh (log /tmp/fresh-build.log).
- Security note: metadata UDP on loopback is unauthenticated, but a local app could only
  set the music volume (any app can) or trigger a fixed-format DACP GET (any app with
  INTERNET can do more). Accepted.
- Shairport answers GET_PARAMETER volume / /info initialVolume with its last AirPlay
  volume; possible future route for Android->iPhone sync if DACP fails in AP2.
- 04:45 From-scratch build in a clean clone (/tmp/fresh): arm64 deps ~16 min, then both
  Shairport ABIs, both NQPTP ABIs and the APK: BUILD SUCCESSFUL. Pipeline reproducible.
- 04:50 Idle cost measured over 60 s: Shairport 1 tick, NQPTP 14 ticks (0.14 s/min),
  app 0. Removed all test files from the POCO's /data/local/tmp.

## Para la mañana (estado + qué probar)

Instalado en el POCO: receptor "Shairport AP2 Android", backend **AAudio** por defecto.
1. Reproducir desde el iPhone. Si algo suena raro: Ajustes de la app -> *Audio output* ->
   "App AudioTrack" (el camino que ya funcionó) y comparar.
2. Volumen iPhone -> Android: mover el slider; el volumen multimedia del POCO debe seguirlo.
3. (Eliminado a petición: el teléfono NO manda su volumen al iPhone; ver entrada 05:30.)
4. Sincronía: las líneas de estadísticas de Shairport salen en el mismo logcat.
5. Pantalla apagada: debería seguir visible y conectable (hi-perf Wi-Fi forzado).
- 05:00 **Shairport never exited on SIGTERM**, even idle (every stop needed the 3 s
  SIGKILL, so no mDNS goodbyes): exit_function -> activity_monitor_stop joins a thread
  in pthread_cond_wait (Bionic: the shim's SIGUSR1 only causes a spurious wake-up, the
  loop waits again); same for the metadata queue thread; classic AirPlay receivers block
  in recv(). Patch android/0005 adds testcancel around those waits and reuses the AP2
  poll-with-ceiling helper for the AP1 receivers. Verified: idle and with a live AP1
  session (pyatv, output to /dev/null) SIGTERM exits in <500 ms.
- Clean exit then hit SIGABRT (Scudo "misaligned pointer") in mdnsd_stop: tinysvcmdns
  rr_create_aaaa() kept a pointer into getifaddrs() memory (freed after registration =
  use-after-free in AAAA answers) and free()d it at shutdown. Patch 0005 copies it.
  Verified: engine restart logs "shairport-sync exited: 0" in ~70 ms; A/AAAA OK.
- 05:05 Checked the "Audio output" plumbing silently: stdout mode -> config "stdout" +
  app AudioTrack (44.1k, USAGE_MEDIA) created; switched back to aaudio (default).
  AP2 session-teardown paths already have cancellation points (player loop, buffered
  reader, AP2 receivers); aaudio play() blocks at most 1 s before the player sees it.
- 05:30 User decision: sender -> Android volume stays; Android -> sender (DACP) removed
  entirely, no toggle. Receiver-to-sender volume isn't in a stable Shairport release (dev
  only) and misbehaves in multi-room. Re-add when upstream stable has it. VolumeSync is
  now just the `pvol` listener.

## 2026-09-26 (day) — branch feat/ui-polish

- Dark theme (`Theme.Material`).
- Sender volume changes now use FLAG_SHOW_UI: the system volume bar shows the master
  (STREAM_MUSIC) volume moving. Verified with a simulated `pvol` (screen on).
- Magisk prompt: `su` from the app shows Magisk's native "Superuser request" only while
  Magisk has no saved answer. The prompt auto-denies after ~10 s with "Forever" selected
  (reproduced by accident: my tap came late -> policy=1), and toggling the app off in
  Magisk's Superuser list also stores a deny; then Magisk refuses silently forever. That
  is what happened to the renamed app at night (prompt raised while the screen was off).
  The app now tells apart granted / denied / no su in the "Root access" summary (no custom
  dialog, per user). Reset for testing: `magisk --sqlite "DELETE FROM policies WHERE uid=<uid>"`.
- Morning AAudio session log: "AirPlay 2 Buffered playback ... AAC/48000/F24/2 -> 48000/S16_LE/2",
  first stats line Av Sync Error 3.2 ms, Net Sync 993 ppm (first line only, buffer rotated;
  need a longer session to judge).

## 2026-09-26 (day) — branch feat/device-name-adb-config

- Renamed everything to "Shairport": label, notification channel, log tag `Shairport`,
  namespace/applicationId/Kotlin package `com.hkfuertes.shairport`, Gradle root project.
  Old package uninstalled from the POCO (Magisk dropped its policy); new UID 10208 granted
  through Magisk's prompt.
- `Prefs.kt`: single source of keys/defaults (XML defaultValues removed). Default advertised
  name = Settings.Global.DEVICE_NAME ("Xiaomi Pocophone F1"; `ro.product.name` is
  `lineage_beryllium` here), fallback manufacturer + model.
- "Root access" row is disabled once root is granted.
- adb configuration like jqssun/android-airplay-server#46: `SettingsReceiver` (DUMP-protected),
  CONFIGURE_SETTINGS / LIST_SETTINGS; rejects unknown keys, wrong types, bad ports and values
  outside list choices. The service listens to the preferences and applies changes itself.
  Verified: rename -> engine restarts cleanly, mDNS shows the new name; receiver_enabled
  false stops the service.
