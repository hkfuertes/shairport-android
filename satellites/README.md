# Satellites

> **Archived, not built.** Out of the Gradle build since the `housekeeping/satellites` branch; kept here for reference only, and likely to be deleted.

**Work in progress.** Companion apps for the [Shairport](../README.md) receiver, built with it and signed with the same key, that play what it plays on other speakers, fed by its [audio tap](../DESIGN.md#audio-tap): in sync with it and with the rest of an AirPlay 2 group — for the sender they are part of this speaker, one volume, grouped together. They are experimental, change with the receiver, and are not in any release: to build them again, re-add `include(":satellites")` to `settings.gradle` and the satellites steps to the Dockerfile (see git history).

- `sendspin-release.apk`, **Sendspin Satellites** (`com.hkfuertes.shairport.sendspin`): a minimal [Sendspin](https://github.com/Sendspin/spec) server, for Sendspin players.
- `snapcast-release.apk`, **Snapcast Satellites** (`com.hkfuertes.shairport.snapcast`): a minimal [Snapcast](https://github.com/badaix/snapcast) server, for Snapcast clients.
- `chromecast-release.apk`, **Chromecast Satellites** (`com.hkfuertes.shairport.chromecast`): casts to Google Cast devices, each pulling the stream (AirConnect's aircast design).

## Use

They are the same app around a different server. Turn on **Satellites** in one; it runs as a foreground service of its own (its notification counts the players), also after a reboot, while the receiver keeps running in Shairport. One app taps the receiver at a time: the last one turned on; the other one says so. Without Shairport installed (or signed with another key) there is nothing to tap: its screen says so.

Its screen shows whether it reaches Shairport, its address and each player, with a switch each: an off player is not used (not dialed, not cast to) or, if it connected by itself, hears nothing; remembered between runs, by its address (a Cast device, by its Cast identity). Its settings:

- **Only satellites**: the satellites play and the Shairport device stays silent, playing zeros: its clock keeps pacing Shairport and the satellites. If the satellites app stops or dies, the device plays again.
- **Only 44.1 kHz** (restarts Shairport's engine), for players that can't play 48 kHz: AirPlay 2 senders are offered 44.1 kHz only.

Audio goes out as 20 ms chunks of 16-bit stereo PCM at the rate Shairport plays, each with the time it is heard, on the sender's timeline (AirPlay 2's PTP included): about 1.4 Mbit/s per player. Satellites get it about 1 s ahead (the tap's lead), room for Wi-Fi hiccups; a player cut off for longer goes silent and comes back in sync. With **Link Android music volume** on in Shairport, satellites play at this device's music volume; with it off, AirPlay volume goes into the audio itself and each satellite's own level is set on the satellite. Players reconnect by themselves when the device is back on the network; without root, see the receiver's [Limitations](../README.md#limitations) for screen-off reachability.

### Sendspin

- Players: the [sendspin CLI](https://github.com/Sendspin/sendspin-python-cli), [sendspin-cpp](https://github.com/Sendspin/sendspin-cpp), ESPHome players, any Sendspin `player@v1`. Both ways in: players that wait for a server (`_sendspin._tcp`, the default) are found and connected to; players that look for one find it as `_sendspin-server._tcp` or at the device's address, port 8927, path `/sendspin` (`sendspin daemon --url ws://ADDRESS:8927/sendspin`).
- Unpaired access only: players must allow it (the CLI does by default; sendspin-cpp needs `-a`, ESPHome `unpaired_access: true`). Pairing isn't implemented, and turning Satellites on approves every player that allows it: the traffic is still encrypted (Noise), but not authenticated.
- A player that doesn't list the current format (or plays FLAC only) gets no audio until it changes. On pause or skip their stream ends; the next audio starts a new one.

### Snapcast

- Clients: `snapclient` on Linux, an [ESP32](https://github.com/CarlosDerSeher/snapclient), [Snapdroid](https://github.com/badaix/snapdroid) on Android. They find the server over mDNS (`_snapcast._tcp`, named `Snapcast`) or at the device's address, port 1704 (`snapclient -h ADDRESS`; give the device a fixed one, a DHCP reservation).
- Volume goes as their Snapcast volume, which each client applies at once; a client that ignores it plays at full volume. Clients buffer 1.5 s (above the 1 s lead), so an ESP32 needs PSRAM.
- On pause or skip they get a new codec header, drop what they hold and stop with this device; so does a change of rate. Port 1705 accepts connections and answers nothing (Snapdroid's Play stays empty); there is no other Snapcast control.

### Chromecast

- Every Google Cast device on the network (`_googlecast._tcp`) is cast to — speakers, screens, groups, each with its switch. Google Cast has no pairing to allow.
- Each device pulls the audio as an endless WAV over HTTP, the way [AirConnect](https://github.com/philippe44/AirConnect)'s aircast feeds its devices. Cast devices are not sample-synchronized with it nor exactly with each other (each buffers what it wants, seconds behind it): use **Only satellites**, or accept the delay. For Cast devices in step with each other, cast to a speaker group made in the Google Home app — and switch its members off, or they play twice, once alone and once in the group.
- A pause or skip keeps playing what was buffered; a change of rate restarts the stream (a gap while each device reloads). Volume goes as the device's own Google Cast volume. Nothing shows on the device's screen but the stream.

## Configure from ADB

Over adb (`android.permission.DUMP`, as Shairport's; `APP` is any of the three packages):

```sh
adb shell am start-foreground-service -n $APP/com.hkfuertes.shairport.satellites.SatellitesService  # on
adb shell am broadcast --include-stopped-packages -p $APP \
  -a com.hkfuertes.shairport.satellites.CONFIGURE_SETTINGS --es key enabled --ez value false  # off
adb shell am broadcast --include-stopped-packages -p $APP -a com.hkfuertes.shairport.satellites.GET_STATUS
```

## Layout

- `src/main`: the service, tap and UI.
- `src/<flavor>`: the protocol (`Protocol`, `Satellites`, and Sendspin's `Noise`).

## Testing

`SatellitesTest` (each app) runs in every build: its server against a fake player fed with audio tap messages — Sendspin's with the Noise handshake, both ways in; Snapcast's as a snapclient; Chromecast's WAV server.
