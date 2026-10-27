# Shairport

An AirPlay receiver for Android 8.1 or later (arm64 and 32-bit ARM). [Shairport Sync](https://github.com/mikebrady/shairport-sync) 5.6.2 runs inside the app as a JNI library and advertises itself through Android's own mDNS. Classic AirPlay needs no root; AirPlay 2 (multi-room) needs root for [NQPTP](https://github.com/mikebrady/nqptp) 1.2.9, its timing service. With root, Wi-Fi power-save protection also starts automatically with the receiver, whatever the AirPlay version. Verified on a rooted POCO F1 (LineageOS, Android 15, Magisk).

A [Kiosk Satellite plugin](#kiosk-satellite-plugin) manages the app from a kiosk and Home Assistant. Other apps signed with the same key can take what the receiver plays, as it plays it: the [audio tap](#audio-tap).

## Use

Build and install `app-release.apk` (see [Build](#build)) and open Shairport:

- **AirPlay receiver** starts or stops it, as do the Quick Settings tile and the notification's *Stop*.
- **AirPlay 2** needs root for NQPTP; without root the switch stays off and the receiver stays classic AirPlay. Grant root through **Root access**: with it the receiver also keeps Wi-Fi awake while it runs; without it, classic AirPlay and linked volume still work.
- **Start at boot**, no root needed. With a secure lock screen it starts after the first unlock.
- **Name** (default: the device name), **Model** (the icon senders show, AirPlay 2 only) and **Playback mode** (stereo or mono).
- **Link Android music volume** (default on): AirPlay moves Android's music volume; with an AirPlay 2 sender it works both ways — Android's volume keys move the sender's slider. Off: that volume stays fixed and AirPlay volume applies only to this receiver's audio.
- **Now playing notification** (off by default): the track, artwork and progress in a media notification (lock screen and media controls included); with AirPlay 2 also previous, play/pause and next, reached by media buttons too.
- **Status** shows each part: engine and mode, NQPTP, the mDNS advertisement, playback, and which app taps the audio.

## Configure from ADB

One key/value pair per call, stored where the settings screen stores them. Protected by `android.permission.DUMP`; unknown keys, wrong types and out-of-list values are rejected. A running receiver applies changes at once (restarting the engine when the configuration changed), otherwise they apply the next time it starts.

```sh
# Keys: receiver_enabled, airplay_2, server_name, model, playback_mode, link_stream_volume,
# now_playing, start_at_boot. Booleans: --ez value false. List settings take their value or its
# label ("HomePod mini"). A value with spaces needs a second level of quotes.
adb shell am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver \
  -a com.hkfuertes.shairport.CONFIGURE_SETTINGS --es key server_name --es value "'Kitchen speaker'"
```

Same receiver, other actions: `LIST_SETTINGS` (every key, type, current value, default and choices, as JSON) and `GET_STATUS` (the state as one URL-encoded line: `state`, `mode`, `source`, `title`, `artist`, `album`, `address`, `volume` and every setting's value). Turning the receiver on over adb takes effect the next time it starts: from the app, or [headless](#headless-setup).

## Headless setup

The whole setup works over adb, no screen interaction. Root only for AirPlay 2 and Wi-Fi protection; step 2 assumes a Magisk device whose adb shell already has root (Magisk > Superuser > Shell allowed).

1. `adb install app-release.apk`.
2. Grant root without Magisk's prompt, writing its policy database (`policy` 2 = grant, 1 = deny; `until` 0 = forever):

   ```sh
   uid=$(adb shell cmd package list packages -U com.hkfuertes.shairport | sed 's/.*uid://' | tr -d '\r')
   adb shell su -c "\"magisk --sqlite 'REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($uid,2,0,1,1)'\""
   ```

   Plain `SELECT`/`REPLACE`/`DELETE` only: a `PRAGMA` query crashed `magiskd` on Magisk 30.7.
3. Configure as in [Configure from ADB](#configure-from-adb).
4. Start it without opening the app (exported only to holders of `android.permission.DUMP`):

   ```sh
   adb shell am start-foreground-service -n com.hkfuertes.shairport/.ReceiverService
   ```

5. Start at every boot: `--es key start_at_boot --ez value true`.

## Kiosk Satellite plugin

[`kiosk-plugin/`](kiosk-plugin) is a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that manages the app from the kiosk and its Remote Admin page (the app's own settings, with its current values, state refreshed every 5 s), and publishes to Home Assistant a switch *AirPlay receiver* and State, Source, Title, Artist, Album and Volume entities. The app must be installed (and granted root for AirPlay 2) and keeps running the receiver; the plugin drives the adb interface above through Kiosk Satellite's Shizuku access (the `shell` backend is enough). Install the `shairport-*.zip` from `build/kiosk-plugin/` with **Plugin Manager > Developer Tools > Install from ZIP**, then grant Shizuku access and enable the plugin.

## Audio tap

Apps signed with the same key can take what the receiver plays, as it plays it, and do what they like with it — the receiver doesn't know or care, and its status shows who taps it. The wire protocol and mechanics: [DESIGN.md](DESIGN.md#audio-tap).

## Limitations

- Control back to the sender (now playing controls, Android's volume) works with AirPlay 2 only: classic AirPlay's (DACP) needs the sender's `_dacp._tcp` service, which the app doesn't browse.
- Android's volume going back to the sender is untested in AirPlay 2 multi-room groups.
- The notification's progress can run up to the AirPlay latency (about 2 s) ahead of what is heard.
- Home app: only the Generic model can be added, and the home hub removes an accessory that switches models. HomeKit pairings live in memory only.
- Screen off: from Android 14 a Wi-Fi lock only works with the screen on and the app in the foreground. With root, low-latency mode is forced instead; without it the device may stop answering (the POCO does).
- Ports are fixed: 7000 (AirPlay 2) and 5000 (classic); upstream ignores `general.port`.
- No realtime priority for the player thread: watch for underruns under load.
- armeabi-v7a verified only on the POCO in 32-bit mode, not on a 32-bit-only device.

## Build

Everything builds in Docker from a clean clone; the host needs nothing else:

```sh
make           # build/app-release.apk and build/kiosk-plugin/
make install   # and adb install -r
```

The [Dockerfile](Dockerfile) downloads the NDK and the pinned, checksum-verified dependencies (static libraries, Shairport Sync, NQPTP), applies [`native/patches`](native/patches/README.md), builds both ABIs, then the APK and the plugin; Docker's layer cache stands in for incremental builds. Release builds are signed with the debug key: `~/.android/debug.keystore`, when it exists, is passed as a build secret, so installs keep the AirPlay device ID (it comes from `ANDROID_ID`, which depends on the signing key).

- `make plugin`: only the plugin, in `build/kiosk-plugin`.
- `make jnilibs`: only the engine, in `build/jniLibs` (for Gradle or Android Studio outside Docker).
- `make src`: the patched upstream sources, in `build/src`.

Tests, all in every build: `NowPlayingTest` and `RootScriptTest` (fake commands; it never touches the host's radio); on a rooted device also `tests/aaudio/run.sh [serial]` and `python3 tests/root-ui/check.py granted` (or `denied`). Without an Apple device, AirConnect's `cliraop -a` (ALAC) plays to classic AirPlay.

## Layout

- `app/`: the Kotlin app — `MainActivity` (settings and status), `ReceiverService`, `Engine.kt`, `AudioTapService`, `NowPlaying`, `VolumeSync`, `SettingsReceiver` (the adb interface), `BootReceiver`, `ReceiverTileService`.
- `native/`: the engine's build scripts, run by the Dockerfile, and its [patches](native/patches/README.md).
- `kiosk-plugin/`: the Kiosk Satellite plugin (Java) and its test.
- `tests/`: the on-device checks (AAudio, root UI).
- `satellites/`: companion apps, **not built** — out of the Gradle build; [their README](satellites/README.md).
- `Dockerfile`, `Makefile`: the whole build. `DESIGN.md`: how it works inside, and the audio tap protocol.

## Made with AI

Most of the code, patches and documentation were written by AI coding agents (Claude and GPT); the author set the goals, made the design decisions and tested on real devices. Review it as you would any unaudited code before relying on it.

## Credits

- [Shairport Sync](https://github.com/mikebrady/shairport-sync) and [NQPTP](https://github.com/mikebrady/nqptp), by Mike Brady and contributors: the AirPlay receiver and its timing service — this app is an Android shell around them. The engine also bundles OpenSSL, FFmpeg, libsodium, libgcrypt and libgpg-error, libplist, libconfig, popt and libuuid; each keeps its own licence, versions pinned in the [Dockerfile](Dockerfile).
- [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) and its [plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world), by jxlarrea: the plugin SDK and build tool.
