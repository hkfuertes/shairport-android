# Shairport

An Android app (8.1 or later, arm64 and 32-bit ARM) that turns the device into an AirPlay receiver: Shairport Sync 5.5.1 as a JNI library inside the app, with Android's own mDNS (NsdManager). It needs no root for classic AirPlay. AirPlay 2 (multi-room) is an option that needs root, only for NQPTP 1.2.8, its timing service. Verified on a rooted POCO F1 (LineageOS, Android 15, Magisk).

This is deliberately **not** an upstream source mirror: the repository keeps only its own code and patches. The Dockerfile downloads the pinned upstream sources, checks their checksums and applies the Android patch stack. A [Kiosk Satellite plugin](#kiosk-satellite-plugin) manages the app from a kiosk and Home Assistant.

## How it works

- `ReceiverService` (foreground, media playback) writes `shairport-sync.conf` and binds `EngineService`, which runs in a process of its own (`:engine`). There Shairport Sync runs as a JNI library (`libshairport_sync.so`, patch `0003`), once per process: unbinding asks it to exit, which ends the process, and the next start gets a fresh one. Its log goes to logcat (tag `Shairport`).
- Discovery: Shairport's `android` mDNS backend hands its services and TXT records to `NsdManager`, so Android's own responder advertises them and follows address changes. The app holds a Wi-Fi `MulticastLock`.
- AirPlay 2: off by default, the receiver is a classic AirPlay (AirPlay 1) one and needs no root. The "AirPlay 2 (multi-room)" switch asks Magisk for `su` and runs NQPTP (UDP ports 319/320 need root) in a root watcher until the app stops it or dies. Shairport, still as the app, reads NQPTP's shared memory from external app storage; without NQPTP it falls back to classic AirPlay. The advertised device ID comes from `ANDROID_ID` (apps can't read the MAC address).
- Audio: Shairport plays through **AAudio** (patch `0002`) and reports the real output delay, so AirPlay 2 timing and multi-room stay in sync. That is why Android 8.1 is the minimum.
- Control: the "AirPlay receiver" switch, a Quick Settings tile and the notification's "Stop" all start or stop the receiver. A Status section shows each part (Shairport Sync and its mode, NQPTP, the mDNS advertisement, playback).
- Volume: Shairport runs with `ignore_volume_control` and sends metadata over loopback UDP; the sender's slider sets Android's music volume. It is one-way on purpose: the phone never sends its volume back to the sender (see HANDOFF).
- Wi-Fi: the app holds a high-performance Wi-Fi lock, which keeps the receiver reachable with the screen off up to Android 13. From Android 14 app locks only work with the screen on; with AirPlay 2 on, the root watcher forces Wi-Fi hi-perf mode instead (`cmd wifi force-hi-perf-mode`), without which the POCO stops answering TCP/ARP with the screen off. A crash after 30 s of uptime restarts the receiver.

## Build

Everything builds in Docker, from a clean clone, with nothing else on the host:

```sh
make           # build/app-debug.apk and build/kiosk-plugin/
make install   # and adb install -r
```

The [Dockerfile](Dockerfile) downloads the NDK, the static dependencies (popt, libconfig, libsodium, libgpg-error, libgcrypt, libplist, OpenSSL, FFmpeg, libuuid), Shairport Sync and NQPTP at pinned versions, checks their checksums, applies [`native/patches`](native/patches/README.md) and builds arm64-v8a and armeabi-v7a, then the APK and the plugin. Docker's layer cache replaces incremental builds: a patch change rebuilds only Shairport Sync and NQPTP, a Kotlin change only reruns Gradle. The first build takes a while. `make` passes `~/.android/debug.keystore` as a build secret, so the debug signature stays stable across builds. `make jnilibs` exports only the engine to `build/jniLibs`, which `app/build.gradle` packages, for Gradle builds outside Docker (Android Studio).

`make src` exports the patched upstream sources to `build/src`. `tests/aaudio/run.sh [serial]` checks the AAudio backend on a rooted device, writing only zeros.

## Configure from ADB

Settings can be written with an explicit `adb shell` broadcast: one typed `key`/`value` pair per call, stored in the same preferences the settings screen uses. The receiver is protected by `android.permission.DUMP`, so regular apps cannot use it. Unknown keys, wrong types and values outside a list setting's choices are rejected. A running receiver applies changes at once (restarting the engine when the configuration changed); otherwise they apply the next time the app starts it.

```sh
# text (the advertised name defaults to Android's device name). adb shell re-splits the command
# on the device, so a value with spaces needs a second level of quotes.
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.CONFIGURE_SETTINGS --es key server_name --es value "'Kitchen speaker'"

# list settings take their value or its label: model ("HomePod mini" = AudioAccessory5,1),
# playback_mode.
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.CONFIGURE_SETTINGS --es key model --es value "'HomePod mini'"

# boolean
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.CONFIGURE_SETTINGS --es key receiver_enabled --ez value false

# AirPlay 2 needs su for NQPTP: Magisk prompts on the device unless the app is already granted
# (see Headless setup); without root the receiver stays classic AirPlay.
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.CONFIGURE_SETTINGS --es key airplay_2 --ez value true
```

List keys, types, current values, defaults, and choices with their labels as JSON:

```sh
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.LIST_SETTINGS
```

The current state, for the [Kiosk Satellite plugin](#kiosk-satellite-plugin), as one URL-encoded line: `state` (`off`, `idle` or `playing`), `mode` (`airplay2` or `classic`, while advertised), `source`, `title`, `artist`, `album`, `address`, `volume` (music stream, %) and every setting's value:

```sh
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.GET_STATUS
```

Turning the receiver on over adb only takes effect when the receiver is next started: from the app, or as in [Headless setup](#headless-setup).

## Headless setup

The whole setup works over adb, with no screen interaction. Root is only needed for AirPlay 2 and for starting at boot; steps 2 and 5 assume a Magisk-rooted device whose adb shell already has root (Magisk > Superuser > Shell allowed). Verified on the POCO F1 with Magisk 30.7.

1. Install: `adb install app-debug.apk`.
2. For AirPlay 2 or start at boot, grant the app root without Magisk's prompt, by writing Magisk's policy database (`policy` 2 = grant, 1 = deny; `until` 0 = forever; the last two columns are Magisk's log and toast):

   ```sh
   uid=$(adb shell cmd package list packages -U com.hkfuertes.shairport | sed 's/.*uid://' | tr -d '\r')
   adb shell su -c "\"magisk --sqlite 'REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($uid,2,0,1,1)'\""
   ```

   Stick to plain `SELECT`/`REPLACE`/`DELETE` statements: a `PRAGMA` query crashed `magiskd` on Magisk 30.7, and root was gone until the next reboot.
3. Configure it as in [Configure from ADB](#configure-from-adb).
4. Start the receiver without opening the app (the service is exported only to holders of `android.permission.DUMP`: adb, Shizuku, root; no root needed):

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

## Kiosk Satellite plugin

[`kiosk-plugin/`](kiosk-plugin) is a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that manages this app from the kiosk and its Remote Admin page, and publishes its state to Home Assistant. It only manages: the Shairport app must be installed (and granted root for AirPlay 2 or Start at boot, as above), and it keeps running the receiver. The plugin uses the adb interface above through Kiosk Satellite's Shizuku access (the `shell` backend is enough), one command at a time: `GET_STATUS` every 5 s, `CONFIGURE_SETTINGS` for changes, `am start-foreground-service` to turn the receiver on.

- Settings, on the kiosk and in Remote Admin: AirPlay receiver, AirPlay 2 (multi-room), Name, Model, Playback mode, Start at boot. The status line says whether the receiver runs as AirPlay 2 or classic AirPlay. They show the app's current values, including changes made in the app itself.
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
- `app/`: Kotlin app (`MainActivity`, `ReceiverService`, `Engine` and `EngineService` (the `:engine` process), `VolumeSync`, `Prefs`, `SettingsReceiver`, `ModelPreference`).
- `native/`: engine build steps run by the Dockerfile (`build-deps.sh`, `build-engine.sh`) and the Android patch stack, in order; see [`native/patches/README.md`](native/patches/README.md).
- `kiosk-plugin/`: the Kiosk Satellite plugin (Java), with its test.
- `tests/aaudio/`: on-device check of the AAudio backend.
- `diario.md`: night log of decisions, measurements and dead ends.

The Echo raw-ALSA backend, Echo controls, LED controller, Rust artifacts, installer and TWRP files belong to the device-specific `shairport-echo` project and are intentionally excluded.
