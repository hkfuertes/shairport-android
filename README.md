# Shairport

A rooted Android app that turns the phone into an AirPlay 2 receiver: Shairport Sync 5.5.1 (AirPlay 2) and NQPTP 1.2.8, cross-compiled with the NDK and run as root children of the app. Verified on a rooted POCO F1 (LineageOS, Android 15, Magisk): discovered and played from an iPhone.

This is deliberately **not** an upstream source mirror: the repository keeps only its own code and patches. The Dockerfile downloads the pinned upstream sources, checks their checksums and applies the Android patch stack. A [Kiosk Satellite plugin](#kiosk-satellite-plugin) manages the app from a kiosk and Home Assistant.

## How it works

- `MainActivity` (classic XML preferences) asks Magisk for `su`; without root every setting stays disabled.
- `ReceiverService` (foreground, media playback) writes `shairport-sync.conf` to external app storage and starts one root supervisor shell through `su`. The supervisor runs the APK-packaged `libnqptp.so` and `libshairport_sync.so` executables and lives exactly as long as Shairport. Closing its stdin (service stop, or the app dying) stops everything: Shairport exits cleanly on SIGTERM (Bionic cancellation fixes in patch `android/0005`), with SIGKILL after 3 s as a safety net.
- Audio: on Android 8+ Shairport plays through **AAudio** itself (patch `0004`), reporting the real output delay so AirPlay 2 timing and multi-room stay in sync. On Android 7, where AAudio does not exist, Shairport's `stdout` backend is piped into an `AudioTrack` in the app instead, which has no delay feedback.
- Discovery: TinySVCmDNS inside Shairport, as root, with the app's Wi-Fi `MulticastLock`. Its host name is `shairport-<MAC>.local` because Android reports `localhost` (patch `0003`).
- Control: the "AirPlay receiver" switch, a Quick Settings tile and the notification's "Stop" all start or stop the whole engine. A Status section shows each part (Shairport Sync, NQPTP, the mDNS advertisement, playback).
- Volume: Shairport runs with `ignore_volume_control` and sends metadata over loopback UDP; the sender's slider sets Android's music volume. It is one-way on purpose: the phone never sends its volume back to the sender (see HANDOFF).
- Wi-Fi: while running, the supervisor forces Wi-Fi hi-perf mode (`cmd wifi force-hi-perf-mode`): with the screen off the POCO otherwise stops answering TCP/ARP. A Wi-Fi reconnect or address change restarts the engine and re-acquires the multicast lock; a crash after 30 s of uptime restarts it too.

## Build

Everything builds in Docker, from a clean clone, with nothing else on the host:

```sh
make           # build/app-debug.apk and build/kiosk-plugin/
make install   # and adb install -r
```

The [Dockerfile](Dockerfile) downloads the NDK, the static dependencies (popt, libconfig, libsodium, libgpg-error, libgcrypt, libplist, OpenSSL, FFmpeg, libuuid), Shairport Sync and NQPTP at pinned versions, checks their checksums, applies [`native/patches`](native/patches/README.md) and builds arm64-v8a and armeabi-v7a, then the APK and the plugin. Docker's layer cache replaces incremental builds: a patch change rebuilds only Shairport Sync and NQPTP, a Kotlin change only reruns Gradle. The first build takes a while. `make` passes `~/.android/debug.keystore` as a build secret, so the debug signature stays stable across builds. `make jnilibs` exports only the engine to `build/jniLibs`, which `app/build.gradle` packages, for Gradle builds outside Docker (Android Studio).

`tests/aaudio/run.sh [serial]` checks the AAudio backend on a rooted API 26+ device, writing only zeros.

## Configure from ADB

Settings can be written with an explicit `adb shell` broadcast: one typed `key`/`value` pair per call, stored in the same preferences the settings screen uses. The receiver is protected by `android.permission.DUMP`, so regular apps cannot use it. Unknown keys, wrong types, invalid ports and values outside a list setting's choices are rejected. A running receiver applies changes at once (restarting the engine when the configuration changed); otherwise they apply the next time the app starts it.

```sh
# text (the advertised name defaults to Android's device name). adb shell re-splits the command
# on the device, so a value with spaces needs a second level of quotes.
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.CONFIGURE_SETTINGS --es key server_name --es value "'Kitchen speaker'"

# list settings take their value or its label: model ("HomePod mini" = AudioAccessory5,1),
# playback_mode. The port is text too.
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

The current state, for the [Kiosk Satellite plugin](#kiosk-satellite-plugin), as one URL-encoded line: `state` (`off`, `idle` or `playing`), `source`, `title`, `artist`, `album`, `address`, `volume` (music stream, %) and every setting's value:

```sh
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.GET_STATUS
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
4. Start the receiver without opening the app (the service is exported only to holders of `android.permission.DUMP`: adb, Shizuku, root):

   ```sh
   adb shell am start-foreground-service -n com.hkfuertes.shairport/.ReceiverService
   ```

5. Start it at every boot with a Magisk `service.d` script, which runs as root. The app's "Start at boot" switch (or `--es key start_at_boot --ez value true` over adb) writes and removes exactly this script. By hand:

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

## Kiosk Satellite plugin

[`kiosk-plugin/`](kiosk-plugin) is a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that manages this app from the kiosk and its Remote Admin page, and publishes its state to Home Assistant. It only manages: the Shairport app must be installed and granted root as above, and it keeps running the receiver. The plugin uses the adb interface above through Kiosk Satellite's Shizuku access (the `shell` backend is enough), one command at a time: `GET_STATUS` every 5 s, `CONFIGURE_SETTINGS` for changes, `am start-foreground-service` to turn the receiver on.

- Settings, on the kiosk and in Remote Admin: AirPlay receiver, Name, Model, Playback mode, Start at boot. They show the app's current values, including changes made in the app itself.
- Home Assistant (ESPHome with native entities enabled in Kiosk Satellite): switch *AirPlay receiver*; text sensors *State* (`off`, `idle`, `playing`), *Source*, *Title*, *Artist* and *Album*; sensor *Volume* (%).

Install it with this repository's URL in **Plugin Manager > Add plugin** (each GitHub release carries the plugin, attached by `.github/workflows/release.yml`), or build it with `make plugin` and use **Developer Tools > Install from ZIP** with `build/kiosk-plugin/shairport-*.zip`. Then grant Kiosk Satellite Shizuku access and enable the plugin.

The plugin SDK has no media player entity. A Home Assistant [universal media player](https://www.home-assistant.io/integrations/universal/) can wrap the entities (replace the entity IDs with yours):

```yaml
media_player:
  - platform: universal
    name: AirPlay
    state_template: "{{ states('sensor.kiosk_shairport_state') }}"
    attributes:
      media_title: sensor.kiosk_shairport_title
      media_artist: sensor.kiosk_shairport_artist
      media_album_name: sensor.kiosk_shairport_album
      source: sensor.kiosk_shairport_source
    commands:
      turn_on: {action: switch.turn_on, target: {entity_id: switch.kiosk_shairport_airplay_receiver}}
      turn_off: {action: switch.turn_off, target: {entity_id: switch.kiosk_shairport_airplay_receiver}}
```

Playback control (play, pause, next) is not exposed: like the volume, it would need DACP back to the sender (see HANDOFF).

## Layout

- `Dockerfile`, `Makefile`: the whole build (see [Build](#build)).
- `app/`: Kotlin app (`MainActivity`, `ReceiverService`, `VolumeSync`, `Prefs`, `SettingsReceiver`, `ModelPreference`).
- `native/`: engine build steps run by the Dockerfile (`build-deps.sh`, `build-engine.sh`) and the Android patch stack, in order; see [`native/patches/README.md`](native/patches/README.md).
- `kiosk-plugin/`: the Kiosk Satellite plugin (Java), with its test.
- `tests/aaudio/`: on-device check of the AAudio backend.
- `diario.md`: night log of decisions, measurements and dead ends.

The Echo raw-ALSA backend, Echo controls, LED controller, Rust artifacts, installer and TWRP files belong to the device-specific `shairport-echo` project and are intentionally excluded.
