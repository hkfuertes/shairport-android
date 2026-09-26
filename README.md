# Shairport

An AirPlay receiver for Android 8.1 or later (arm64 and 32-bit ARM). [Shairport Sync](https://github.com/mikebrady/shairport-sync) 5.5.1 runs inside the app as a JNI library and advertises itself through Android's own mDNS. Classic AirPlay needs no root; AirPlay 2 (multi-room) needs root only for [NQPTP](https://github.com/mikebrady/nqptp) 1.2.8, its timing service. Verified on a rooted POCO F1 (LineageOS, Android 15, Magisk).

A [Kiosk Satellite plugin](#kiosk-satellite-plugin) manages the app from a kiosk and Home Assistant.

## Use

Install `app-release.apk` (see [Build](#build)) and open Shairport:

- **AirPlay receiver** starts or stops it, as do the Quick Settings tile and the notification's *Stop*.
- **AirPlay 2 (multi-room)** asks Magisk for root to run NQPTP. Without root the receiver stays classic AirPlay.
- **Start at boot**, no root needed. With a secure lock screen it starts after the first unlock.
- **Name** (default: the device name), **Model** (the icon senders show, AirPlay 2 only) and **Playback mode** (stereo or mono).
- **Status** shows each part: Shairport Sync and its mode, NQPTP, the mDNS advertisement, playback.

The sender's volume slider sets Android's music volume.

## Configure from ADB

Settings can be written with an explicit `adb shell` broadcast: one typed `key`/`value` pair per call, stored in the same preferences the settings screen uses. The receiver is protected by `android.permission.DUMP`, so regular apps cannot use it. Unknown keys, wrong types and values outside a list setting's choices are rejected. A running receiver applies changes at once (restarting the engine when the configuration changed); otherwise they apply the next time it starts.

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

The current state as one URL-encoded line: `state` (`off`, `idle` or `playing`), `mode` (`airplay2` or `classic`, while advertised), `source`, `title`, `artist`, `album`, `address`, `volume` (music stream, %) and every setting's value:

```sh
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.GET_STATUS
```

Turning the receiver on over adb takes effect the next time it starts: from the app, or as in [Headless setup](#headless-setup).

## Headless setup

The whole setup works over adb, with no screen interaction. Root is only needed for AirPlay 2; step 2 assumes a Magisk-rooted device whose adb shell already has root (Magisk > Superuser > Shell allowed). Verified with Magisk 30.7.

1. Install: `adb install app-release.apk`.
2. For AirPlay 2, grant the app root without Magisk's prompt, by writing Magisk's policy database (`policy` 2 = grant, 1 = deny; `until` 0 = forever; the last two columns are Magisk's log and toast):

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

5. Start it at every boot: `--es key start_at_boot --ez value true`.

## Kiosk Satellite plugin

[`kiosk-plugin/`](kiosk-plugin) is a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that manages this app from the kiosk and its Remote Admin page, and publishes its state to Home Assistant. It only manages: the Shairport app must be installed (and granted root for AirPlay 2, as above), and the app keeps running the receiver. The plugin drives the adb interface above through Kiosk Satellite's Shizuku access (the `shell` backend is enough), one command at a time: `GET_STATUS` every 5 s, `CONFIGURE_SETTINGS` for changes, `am start-foreground-service` to turn the receiver on.

- Settings, on the kiosk and in Remote Admin: AirPlay receiver, AirPlay 2 (multi-room), Name, Model, Playback mode, Start at boot. They show the app's current values, including changes made in the app itself, and the status line says whether the receiver runs as AirPlay 2 or classic AirPlay.
- Home Assistant (ESPHome with native entities enabled in Kiosk Satellite): switch *AirPlay receiver*; text sensors *State* (`off`, `idle`, `playing`), *Source*, *Title*, *Artist* and *Album*; sensor *Volume* (%).

Install it with **Developer Tools > Install from ZIP**, using `shairport-*.zip` from a release or from `build/kiosk-plugin/`. Then grant Kiosk Satellite Shizuku access and enable the plugin.

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

## Limitations

- Volume goes one way, sender to Android, and Android can't pause or skip. Both would need DACP back to the sender: receiver-to-sender volume isn't in a stable Shairport Sync release yet, and one speaker pushing its volume misbehaves in multi-room groups.
- Home app: only the Generic model can be added. Home never offers a HomePod model, and the home hub removes an accessory that switches to one. Shairport keeps HomeKit pairings in memory only; whether Home survives an engine restart is still unchecked.
- Screen off: from Android 14 an app's Wi-Fi lock only works with the screen on. With AirPlay 2, the root watcher forces Wi-Fi high-performance mode instead; without root the device may stop answering (the POCO does).
- Ports are fixed: 7000 for AirPlay 2, 5000 for classic AirPlay (upstream ignores `general.port`).
- An app can't give the player thread realtime priority: watch for underruns under load.
- armeabi-v7a was verified on the POCO in 32-bit mode (`adb install --abi armeabi-v7a`), not on a 32-bit-only device.

## Build

Everything builds in Docker from a clean clone; the host needs nothing else:

```sh
make           # build/app-release.apk and build/kiosk-plugin/
make install   # and adb install -r
```

The repository keeps only its own code and patches. The [Dockerfile](Dockerfile) downloads the NDK, the static dependencies (popt, libconfig, libsodium, libgpg-error, libgcrypt, libplist, OpenSSL, FFmpeg, libuuid), Shairport Sync and NQPTP at pinned versions, checks their checksums, applies [`native/patches`](native/patches/README.md), builds both ABIs, then the APK and the plugin. Docker's layer cache stands in for incremental builds: a patch change rebuilds only Shairport Sync and NQPTP, a Kotlin change only reruns Gradle. The first build takes a while.

The APK is a release build signed with the debug key: `make` passes `~/.android/debug.keystore` as a build secret, so every build installs over the last one and keeps the AirPlay device ID (it comes from `ANDROID_ID`, which depends on the signing key).

- `make plugin`: only the plugin, in `build/kiosk-plugin`.
- `make jnilibs`: only the engine, in `build/jniLibs`, where `app/build.gradle` picks it up: for Gradle or Android Studio outside Docker.
- `make src`: the patched upstream sources, in `build/src`.

Testing:

- `tests/aaudio/run.sh [serial]` checks the AAudio backend on a rooted device, writing only zeros.
- The plugin's test runs against a fake Kiosk Satellite host in every build.
- Without an Apple device, AirConnect's `cliraop -a` (ALAC) plays to classic AirPlay (`-v 0` sets Android's volume to 0 too). pyatv can't drive this build. AirPlay 2 needs an Apple sender.

## How it works

The rule: whatever Android can do, Android does; root only where nothing else works.

- Engine: `ReceiverService` writes `shairport-sync.conf` and binds `EngineService`, which runs Shairport Sync as a JNI library (`libshairport_sync.so`, patch [`0008`](native/patches/README.md)) in a process of its own (`:engine`). Shairport assumes a fresh process, so it runs once per process: stopping asks it to exit, its own cleanup runs (mDNS goodbyes, AAudio closed) and the process ends; the next start waits until it is gone. An engine that dies after 30 s of uptime is restarted (not sooner, so a broken setup can't loop). Only the JNI entry points are exported, so the static OpenSSL and FFmpeg inside can't clash with the app's own libraries. The log goes to logcat, tag `Shairport`.
- `ReceiverService` is a `connectedDevice` foreground service: Android 15 doesn't let `mediaPlayback` ones start from `BOOT_COMPLETED`. The type doesn't affect audio.
- Discovery: Shairport's `android` mDNS backend hands its services and TXT records to `NsdManager`, so Android's responder advertises them and follows address changes. NsdManager can't update a TXT record, so an AirPlay 2 group change re-registers the service. The app holds a Wi-Fi `MulticastLock`.
- Audio: AAudio (patch `0007`) reports the real output delay, which AirPlay 2 timing and multi-room need. It's why Android 8.1 is the minimum.
- AirPlay 2: NQPTP binds UDP ports 319 and 320, which takes root. With the switch on, `ReceiverService` runs a root watcher through `su`: it forces Wi-Fi high-performance mode and runs NQPTP until the app stops it or dies. NQPTP's shared memory lives in external app storage, which both root and the app can reach. Without NQPTP (su denied), Shairport falls back to classic AirPlay.
- Device ID: apps can't read the MAC address, so the AirPlay device ID comes from `ANDROID_ID`.
- Volume: Shairport ignores volume control and sends metadata to the app over loopback UDP; the app applies the sender's volume to Android's music stream.
- Wi-Fi: a high-performance Wi-Fi lock keeps the receiver reachable with the screen off up to Android 13 (see [Limitations](#limitations)).

## Layout

- `app/`: the Kotlin app: `MainActivity` (settings and status), `ReceiverService`, `Engine.kt` (`EngineService`, the `:engine` process), `VolumeSync`, `SettingsReceiver` (the adb interface), `BootReceiver`, `ReceiverTileService`.
- `native/`: the engine's build scripts, run by the Dockerfile, and its [patches](native/patches/README.md).
- `kiosk-plugin/`: the Kiosk Satellite plugin (Java) and its test.
- `tests/aaudio/`: the on-device AAudio check.
- `Dockerfile`, `Makefile`: the whole build.

## Made with AI

Most of the code, patches and documentation were written by AI coding agents (Anthropic's Claude and OpenAI's GPT models). The author set the goals, made the design decisions and tested the app on real devices. Review it as you would any unaudited code before relying on it.

## Credits

- [Shairport Sync](https://github.com/mikebrady/shairport-sync) and [NQPTP](https://github.com/mikebrady/nqptp), by Mike Brady and contributors: the AirPlay receiver and its timing service. This app is an Android shell around them.
- The libraries built into the engine: [OpenSSL](https://www.openssl.org), [FFmpeg](https://ffmpeg.org), [libsodium](https://github.com/jedisct1/libsodium), [libgcrypt and libgpg-error](https://gnupg.org), [libplist](https://github.com/libimobiledevice/libplist), [libconfig](https://github.com/hyperrealm/libconfig), [popt](https://github.com/rpm-software-management/popt) and libuuid from [util-linux](https://github.com/util-linux/util-linux).
- [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite), by jxlarrea, and its [plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world), which provides the plugin SDK and build tool.

Each keeps its own licence; the [Dockerfile](Dockerfile) pins the exact versions.
