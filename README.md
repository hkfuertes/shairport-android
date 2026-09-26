# Shairport

A rooted Android app that turns the phone into an AirPlay 2 receiver: Shairport Sync 5.5.1 (AirPlay 2) and NQPTP 1.2.8, cross-compiled with the NDK and run as root children of the app. Verified on a rooted POCO F1 (LineageOS, Android 15, Magisk): discovered and played from an iPhone.

This is deliberately **not** an upstream source mirror: `make fetch` downloads pinned sources into ignored `third_party/` and `make patch` applies the Android patch stack.

## How it works

- `MainActivity` (classic XML preferences) asks Magisk for `su`; without root every setting stays disabled.
- `ReceiverService` (foreground, media playback) writes `shairport-sync.conf` to external app storage and starts one root supervisor shell through `su`. The supervisor runs the APK-packaged `libnqptp.so` and `libshairport_sync.so` executables and lives exactly as long as Shairport. Closing its stdin (service stop, or the app dying) stops everything: Shairport exits cleanly on SIGTERM (Bionic cancellation fixes in patch `android/0005`), with SIGKILL after 3 s as a safety net.
- Audio: on Android 8+ Shairport plays through **AAudio** itself (patch `0004`), reporting the real output delay so AirPlay 2 timing and multi-room stay in sync. On Android 7 (or when *Audio output* is set to AudioTrack) Shairport's `stdout` backend is piped into an `AudioTrack` in the app, which has no delay feedback.
- Discovery: TinySVCmDNS inside Shairport, as root, with the app's Wi-Fi `MulticastLock`. Its host name is `shairport-<MAC>.local` because Android reports `localhost` (patch `0003`).
- Volume: Shairport runs with `ignore_volume_control` and sends metadata over loopback UDP; the sender's slider sets Android's music volume. It is one-way on purpose: the phone never sends its volume back to the sender (see HANDOFF).
- Wi-Fi: while running, the supervisor forces Wi-Fi hi-perf mode (`cmd wifi force-hi-perf-mode`): with the screen off the POCO otherwise stops answering TCP/ARP. A Wi-Fi reconnect or address change restarts the engine and re-acquires the multicast lock; a crash after 30 s of uptime restarts it too.

## Build

Everything compiles in Docker (`shairport-echo-deps:local` for Shairport and its static dependencies, `shairplay-android-builder:latest` for NQPTP and Gradle), offline:

```sh
make fetch patch
./scripts/build-android-docker.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first run builds the arm64 dependency prefix (`build/deps/arm64-v8a`, resumable); armv7 dependencies come prebuilt in the deps image. Shairport and NQPTP builds are incremental. `~/.android` is mounted so the debug signing key stays stable across builds.

`tests/aaudio/run.sh [serial]` checks the AAudio backend on a rooted API 26+ device, writing only zeros.

## Configure from ADB

Settings can be written with an explicit `adb shell` broadcast: one typed `key`/`value` pair per call, stored in the same preferences the settings screen uses. The receiver is protected by `android.permission.DUMP`, so regular apps cannot use it. Unknown keys, wrong types, invalid ports and values outside a list setting's choices are rejected. A running receiver applies changes at once (restarting the engine when the configuration changed); otherwise they apply the next time the app starts it.

```sh
# text (the advertised name defaults to Android's device name). adb shell re-splits the command
# on the device, so a value with spaces needs a second level of quotes.
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.CONFIGURE_SETTINGS --es key server_name --es value "'Kitchen speaker'"

# list settings take their value or its label: model ("HomePod mini" = AudioAccessory5,1),
# audio_output (aaudio|stdout), playback_mode. The port is text too.
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.CONFIGURE_SETTINGS --es key model --es value "'HomePod mini'"

# boolean
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.CONFIGURE_SETTINGS --es key receiver_enabled --ez value false
```

List keys, types, current values, defaults, and choices with their labels as JSON:

```sh
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.LIST_SETTINGS
```

Turning the receiver on over adb only takes effect when the receiver is next started: from the app, or as in [Headless setup](#headless-setup).

## Headless setup

The whole setup works over adb, with no screen interaction, on a Magisk-rooted device whose adb shell already has root (Magisk > Superuser > Shell allowed). Verified on the POCO F1 with Magisk 30.7.

1. Install: `adb install app-debug.apk`.
2. Grant the app root without Magisk's prompt, by writing Magisk's policy database (`policy` 2 = grant, 1 = deny; `until` 0 = forever; the last two columns are Magisk's log and toast):

   ```sh
   uid=$(adb shell cmd package list packages -U com.hkfuertes.shairport | sed 's/.*uid://' | tr -d '\r')
   adb shell su -c "\"magisk --sqlite 'REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($uid,2,0,1,1)'\""
   ```

   Stick to plain `SELECT`/`REPLACE`/`DELETE` statements: a `PRAGMA` query crashed `magiskd` on Magisk 30.7, and root was gone until the next reboot.
3. Configure it as in [Configure from ADB](#configure-from-adb).
4. Start the receiver without opening the app (root may start the non-exported service):

   ```sh
   adb shell su -c "'am start-foreground-service -n com.hkfuertes.shairport/.ReceiverService'"
   ```

5. Start it at every boot with a Magisk `service.d` script, which runs as root:

   ```sh
   cat > shairport.sh <<'SCRIPT'
   #!/system/bin/sh
   # Start the Shairport AirPlay receiver at boot (Magisk service.d, runs as root).
   until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
   am start-foreground-service -n com.hkfuertes.shairport/.ReceiverService
   SCRIPT
   adb push shairport.sh /data/local/tmp/
   adb shell su -c "'mkdir -p /data/adb/service.d && cp /data/local/tmp/shairport.sh /data/adb/service.d/ && chmod 755 /data/adb/service.d/shairport.sh'"
   ```

   Wi-Fi often gets its address after the engine starts at boot. The service restarts the engine as soon as the address it advertises is out of date.

## Layout

- `app/`: Kotlin app (`MainActivity`, `ReceiverService`, `VolumeSync`, `Prefs`, `SettingsReceiver`, `ModelPreference`).
- `patches/`: Android patch stack, in order; see [`patches/README.md`](patches/README.md).
- `scripts/`: fetch/patch, Docker builds (`build-android-docker.sh`, `build-shairport-android.sh`, `build-shairport-deps-android.sh`, `build-nqptp-android.sh`).
- `diario.md`: night log of decisions, measurements and dead ends.

The Echo raw-ALSA backend, Echo controls, LED controller, Rust artifacts, installer and TWRP files belong to the device-specific `shairport-echo` project and are intentionally excluded.
