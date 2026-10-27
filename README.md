# Shairport

An AirPlay receiver for Android 8.1 or later (arm64 and 32-bit ARM). [Shairport Sync](https://github.com/mikebrady/shairport-sync) 5.6 runs inside the app as a JNI library and advertises itself through Android's own mDNS. Classic AirPlay needs no root; AirPlay 2 (multi-room) needs root for [NQPTP](https://github.com/mikebrady/nqptp) 1.2.9, its timing service. When root is available, Wi-Fi power-save protection starts automatically with the receiver, independently of the AirPlay version. Verified on a rooted POCO F1 (LineageOS, Android 15, Magisk).

A [Kiosk Satellite plugin](#kiosk-satellite-plugin) manages the app from a kiosk and Home Assistant. Another app can take what the receiver plays through its [audio tap](#audio-tap): the [satellites](satellites/README.md) apps here (work in progress) play it in sync on other speakers.

## Use

Build and install `app-release.apk` (see [Build](#build)) and open Shairport:

- **AirPlay receiver** starts or stops it, as do the Quick Settings tile and the notification's *Stop*.
- **AirPlay 2** needs root for NQPTP; without root its switch stays off and the receiver stays classic AirPlay. Grant root through **Root access**: with it, the receiver also keeps Wi-Fi awake while it runs, for any AirPlay version, without it classic AirPlay and linked volume still work.
- **Start at boot**, no root needed. With a secure lock screen it starts after the first unlock.
- **Name** (default: the device name), **Model** (the icon senders show, AirPlay 2 only) and **Playback mode** (stereo or mono).
- **Link Android music volume** defaults on: AirPlay changes Android's music volume, and the [audio tap](#audio-tap)'s app follows it. With an AirPlay 2 sender it works both ways: changing Android's music volume (keys, Home Assistant) moves the sender's slider. Turn it off to keep that volume fixed and apply AirPlay volume only to this receiver's audio.
- **Now playing notification** (off by default): the track, its artwork and progress in a media notification of its own, also on the lock screen and in the quick settings' media controls. With an AirPlay 2 sender it has previous, play/pause and next, which media buttons (headsets) also reach; classic AirPlay shows the track without controls.
- **Status** shows each part: Shairport Sync and its mode, NQPTP, the mDNS advertisement, playback, and which app taps the audio.

## Configure from ADB

Settings can be written with an explicit `adb shell` broadcast: one typed `key`/`value` pair per call, stored in the same preferences the settings screen uses. The receiver is protected by `android.permission.DUMP`, so regular apps cannot use it. Unknown keys, wrong types and values outside a list setting's choices are rejected. A running receiver applies changes at once (restarting the engine when the configuration changed); otherwise they apply the next time it starts.

```sh
# text. adb shell re-splits the command on the device, so a value with spaces needs
# a second level of quotes. Booleans: --ez value false. List settings take their value
# or its label: model ("HomePod mini"), playback_mode. Keys: receiver_enabled, airplay_2,
# server_name, model, playback_mode, link_stream_volume, now_playing, start_at_boot.
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.CONFIGURE_SETTINGS --es key server_name --es value "'Kitchen speaker'"
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

The whole setup works over adb, with no screen interaction. Root is only needed for AirPlay 2 and automatic Wi-Fi protection; step 2 assumes a Magisk-rooted device whose adb shell already has root (Magisk > Superuser > Shell allowed). Verified with Magisk 30.7.

1. Install: `adb install app-release.apk`.
2. For AirPlay 2 or Wi-Fi protection, grant the app root without Magisk's prompt, by writing Magisk's policy database (`policy` 2 = grant, 1 = deny; `until` 0 = forever):

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

- Settings, on the kiosk and in Remote Admin: exactly the app's, showing its current values, including changes made in the app itself. The status line reports the receiver's state, refreshed every 5 s.
- Home Assistant (Kiosk Satellite's **Settings > ESPHome**: Enable ESPHome and Expose kiosk entities): switch *AirPlay receiver*; text sensors *State* (`off`, `idle`, `playing`), *Source*, *Title*, *Artist* and *Album*; sensor *Volume* (%).

Install it with **Plugin Manager > Developer Tools > Install from ZIP**, using the `shairport-*.zip` from `build/kiosk-plugin/`. Then grant Kiosk Satellite Shizuku access and enable the plugin.

## Audio tap

Another app can take what the receiver plays, as it plays it, and do what it likes with it: only apps signed with the same key can (the tap's permission is `signature`); the receiver doesn't know or care what the app does, and its status shows which app taps it.

The app binds the service `com.hkfuertes.shairport.AUDIO_TAP` (in `com.hkfuertes.shairport`, with `<queries>` for it) and sends its `Messenger` a message (`what` 1) with:

- `socket`: one end of a `SOCK_SEQPACKET` socket pair (`Os.socketpair(AF_UNIX, SOCK_SEQPACKET, ...)`; `ParcelFileDescriptor`'s pairs are streams, which the engine refuses); the app reads the other end, a message per read.
- `lead` (float, 0.5 to 1 s): how early the audio leaves before it's heard, the AAudio buffer's length; 0.5 s is the receiver's own.
- `silent` (boolean): this device plays zeros meanwhile. Its AAudio output keeps running, so its clock still paces Shairport, and the app's audio with it.
- `only_44100` (boolean): AirPlay 2 at 44.1 kHz only: no lossless (48 kHz ALAC), 48 kHz AAC or surround. Buffered audio stays (44.1 kHz AAC and ALAC).

A change of `lead` or `only_44100` restarts the engine (a first tap usually does); the socket and `silent` apply at once. A new message replaces the tap. Unbinding, or the app dying, ends it, and this device plays again.

The engine then sends a message per buffer AAudio plays, in native byte order (little-endian): `int32 type`, `int32 value`, `int64 time`, then, for audio, its frames.

| `type` | `value` | `time` | then |
|---|---|---|---|
| 0: audio | the rate (Hz) | when its first frame is heard: `System.nanoTime()` (`CLOCK_MONOTONIC`, the same clock in every process), on the sender's timeline (AirPlay 2's PTP included) | 16-bit stereo frames |
| 1: flush (pause, skip, stop) | 0 | 0 | |
| 2: volume | percent | 0 | |

The volume is 100 while AirPlay volume goes into the audio. With **Link Android music volume** the audio is at full scale and the volume is Android's music volume, however it is set. Sending never blocks Shairport: a socket the app doesn't read in time drops messages.

## Limitations

- Control back to the sender (the now playing controls, Android's volume) works with AirPlay 2 only: classic AirPlay's (DACP) needs the sender's `_dacp._tcp` service, which the app doesn't browse. Whether a sender app honours each command is up to it.
- Android's volume going back to the sender is untested in AirPlay 2 multi-room groups, where one speaker pushing its volume has been known to misbehave.
- The notification's progress starts from where the sender last said the track was, so it can run up to the AirPlay latency (about 2 s) ahead of what is heard.
- Home app: only the Generic model can be added. Home never offers a HomePod model, and the home hub removes an accessory that switches to one. Shairport keeps HomeKit pairings in memory only; whether Home survives an engine restart is still unchecked.
- Screen off: from Android 14 an app's Wi-Fi lock only works with the screen on and the app in the foreground. With root granted, the receiver automatically forces low-latency mode instead, independently of AirPlay 2; without root the device may stop answering (the POCO does).
- Ports are fixed: 7000 for AirPlay 2, 5000 for classic AirPlay (upstream ignores `general.port`).
- An app can't give the player thread realtime priority: watch for underruns under load.
- armeabi-v7a was verified on the POCO in 32-bit mode (`adb install --abi armeabi-v7a`), not on a 32-bit-only device.

## Build

Everything builds in Docker from a clean clone; the host needs nothing else:

```sh
make           # build/app-release.apk, the satellites APKs and build/kiosk-plugin/
make install   # and adb install -r the apps
```

The repository keeps only its own code and patches. The [Dockerfile](Dockerfile) downloads the NDK, the static dependencies (popt, libconfig, libsodium, libgpg-error, libgcrypt, libplist, OpenSSL, FFmpeg, libuuid), Shairport Sync and NQPTP at pinned versions, checks their checksums, applies [`native/patches`](native/patches/README.md), builds both ABIs, then the APKs and the plugin. Docker's layer cache stands in for incremental builds: a patch change rebuilds only Shairport Sync and NQPTP, a Kotlin change only reruns Gradle. The first build takes a while.

The APKs are release builds signed with the debug key: `make` passes `~/.android/debug.keystore`, when it exists, as a build secret, so every build installs over the last one and keeps the AirPlay device ID (it comes from `ANDROID_ID`, which depends on the signing key). Without it, each build gets a new key. Either way the apps share it, which the [audio tap](#audio-tap) requires.

- `make plugin`: only the plugin, in `build/kiosk-plugin`.
- `make jnilibs`: only the engine, in `build/jniLibs`, where `app/build.gradle` picks it up: for Gradle or Android Studio outside Docker.
- `make src`: the patched upstream sources, in `build/src`.

Testing, all in every build:

- `NowPlayingTest`: the notification's progress; linked volume sent to the sender comes back unchanged (no echo).
- `RootScriptTest`: automatic Wi-Fi protection with either AirPlay mode, with fake commands; it never changes the host's radio.
- `tests/aaudio/run.sh [serial]`: the AAudio backend on a rooted device, writing only zeros.
- `python3 tests/root-ui/check.py granted` (or `denied`): the AirPlay 2 switch and subtitle on an unlocked device with its Magisk policy set accordingly.
- Without an Apple device, AirConnect's `cliraop -a` (ALAC) plays to classic AirPlay. AirPlay 2 needs an Apple sender.

## How it works

The rule: whatever Android can do, Android does; root only where nothing else works.

- Engine: `ReceiverService` writes `shairport-sync.conf` and binds `EngineService`, which runs Shairport Sync as a JNI library (`libshairport_sync.so`, patch [`0008`](native/patches/README.md)) in a process of its own (`:engine`). Shairport assumes a fresh process, so it runs once per process: stopping asks it to exit, its own cleanup runs (mDNS goodbyes, AAudio closed) and the process ends; the next start waits until it is gone. An engine that dies after 30 s of uptime is restarted (not sooner, so a broken setup can't loop). Only the JNI entry points are exported, so the static OpenSSL and FFmpeg inside can't clash with the app's own libraries. The log goes to logcat, tag `Shairport`.
- `ReceiverService` is a `connectedDevice` foreground service: Android 15 doesn't let `mediaPlayback` ones start from `BOOT_COMPLETED`. The type doesn't affect audio.
- Discovery: Shairport's `android` mDNS backend hands its services and TXT records to `NsdManager`, so Android's responder advertises them and follows address changes. NsdManager can't update a TXT record, so an AirPlay 2 group change re-registers the service. The app holds a Wi-Fi `MulticastLock`.
- Audio: AAudio (patch `0007`) reports the real output delay, which AirPlay 2 timing and multi-room need. It's why Android 8.1 is the minimum.
- AirPlay 2: NQPTP binds UDP ports 319 and 320, which takes root. With the switch on, `ReceiverService` runs a root watcher through `su` until the app stops it or dies. NQPTP's shared memory lives in external app storage, which both root and the app can reach. On root refusal the AirPlay 2 switch turns off and Shairport starts in classic mode; non-root features remain enabled.
- Device ID: apps can't read the MAC address, so the AirPlay device ID comes from `ANDROID_ID`.
- Audio tap: `AudioTapService` keeps the tapping app's socket and wishes; `ReceiverService` writes the lead and 44.1 kHz into the configuration and hands the socket and `silent` to the engine over its binder. There, patch `0009` sends every buffer the AAudio backend plays, with the time Shairport says it is heard, straight from Shairport's player thread to the socket, never blocking it (no Java on the way), and a flush on pause or skip. Silent, AAudio gets zeros instead, until the other app goes away.
- Volume: with Link Android music volume on, Shairport ignores volume control and sends metadata to the app over loopback UDP; the app applies the sender's volume to Android's music stream. The engine passes every change of that volume on to the audio tap, and with an AirPlay 2 sender to the sender too, unless the change came from it. With it off, Shairport applies AirPlay volume itself.
- Now playing: patch `0010` builds Shairport's metadata hub and remote control without D-Bus or MQTT. A hub watcher hands the track, cover art file, state, duration and position to `ReceiverService` over the engine's status `Messenger`: the status (and Home Assistant) and `NowPlaying.kt`, a `MediaSession` with a `MediaStyle` notification, read it. Its controls go back over the engine's binder to Shairport's remote control. AirPlay 2 senders now send the track as a plist rather than the classic metadata items, so the track no longer comes over UDP.
- Wi-Fi: a high-performance Wi-Fi lock keeps the receiver reachable with the screen off up to Android 13 (see [Limitations](#limitations)). When root is available, protection automatically forces low-latency mode, falling back to high-performance on older Androids; stopping the receiver restores normal Wi-Fi management. A file lock serializes root helpers so an old instance's cleanup cannot undo a replacement's Wi-Fi mode.

## Layout

- `app/`: the Kotlin app: `MainActivity` (settings and status), `ReceiverService`, `Engine.kt` (`EngineService`, the `:engine` process), `AudioTapService`, `NowPlaying`, `VolumeSync`, `SettingsReceiver` (the adb interface), `BootReceiver`, `ReceiverTileService`.
- `satellites/`: the satellites apps — [their README](satellites/README.md).
- `native/`: the engine's build scripts, run by the Dockerfile, and its [patches](native/patches/README.md).
- `kiosk-plugin/`: the Kiosk Satellite plugin (Java) and its test.
- `tests/`: the on-device checks (AAudio, root UI).
- `Dockerfile`, `Makefile`: the whole build.

## Made with AI

Most of the code, patches and documentation were written by AI coding agents (Anthropic's Claude and OpenAI's GPT models). The author set the goals, made the design decisions and tested the app on real devices. Review it as you would any unaudited code before relying on it.

## Credits

- [Shairport Sync](https://github.com/mikebrady/shairport-sync) and [NQPTP](https://github.com/mikebrady/nqptp), by Mike Brady and contributors: the AirPlay receiver and its timing service. This app is an Android shell around them.
- The libraries built into the engine: [OpenSSL](https://www.openssl.org), [FFmpeg](https://ffmpeg.org), [libsodium](https://github.com/jedisct1/libsodium), [libgcrypt and libgpg-error](https://gnupg.org), [libplist](https://github.com/libimobiledevice/libplist), [libconfig](https://github.com/hyperrealm/libconfig), [popt](https://github.com/rpm-software-management/popt) and libuuid from [util-linux](https://github.com/util-linux/util-linux).
- [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite), by jxlarrea, and its [plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world), which provides the plugin SDK and build tool.

Each keeps its own licence; the [Dockerfile](Dockerfile) pins the exact versions.
