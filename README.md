# Ventriloquist

Throw your Android TV's voice into the speakers around the house.

Ventriloquist is an Android TV app that captures what the TV is playing and sends it as a
[VBAN](https://vb-audio.com/Voicemeeter/vban.htm) stream (PCM over UDP) to
[Music Assistant](https://www.music-assistant.io/). Music Assistant's
[VBAN receiver](https://www.music-assistant.io/plugins/vban-receiver/) turns the stream into a
source that any of its players or sync groups can play: Squeezelite, Sonos, Cast, AirPlay,
Sendspin, DLNA. Whatever the box plays (a music app, a radio app, a video) then plays on the
home speakers, and the remote's volume buttons control them.

It is made for music. A networked multi-room path is seconds behind the picture, so a film's
sound arrives late; see "Why there is no lip sync" below.

## Status

Beta, running on an NVIDIA Shield TV. The app holds the capture consent in a foreground
service, captures what the box plays, sends it as VBAN, drives Music Assistant over its HTTP
API, mutes the box's own output so the TV is silent, takes the remote's volume buttons for the
speakers, and switches all of this on and off by itself when playback starts and ends. Every
change it makes, on the box and in Music Assistant, is recorded first and put back afterwards,
also after a crash or a reboot. See [docs/DESIGN.md](docs/DESIGN.md) for the design.

## How it works

1. **Capture.** Android's playback-capture API (Android 10 and later) hands the app a copy of
   the audio that other apps play, after the user accepts the system's consent dialog once per
   app start. Apps can opt out, and protected content is never captured; YouTube, YouTube
   Music, Plex, VLC, Jellyfin and TuneIn allow it, Netflix does not.
2. **Send.** The PCM is packetised as VBAN (48 kHz, 16-bit, stereo, up to 256 frames per
   datagram) and sent over UDP to the Music Assistant host.
3. **Play.** Music Assistant exposes the stream as a source and plays it on the configured
   player or sync group.
4. **TV speakers.** While the home speakers play, the app mutes the box's media output
   (`ADJUST_MUTE`), so the TV receives silence; capture is not affected, it records the mix at
   full level. The volume index is never stepped: on a box that controls the TV's volume (the
   Shield does, by infrared or HDMI-CEC) an index change becomes a volume command to the TV.
   Optionally a Home Assistant automation mutes the TV as well (see below).
5. **Volume buttons.** An accessibility service sees the remote's volume buttons before the
   system does. While the home speakers play it takes them and moves the speakers' volume
   (Music Assistant's group volume, which scales every member), within a configurable maximum,
   with a short message on screen. At any other time the buttons are left alone.
6. **Automatic.** A watcher follows media sessions, active audio players and the screen. Any
   app counts. House mode starts a few seconds after playback starts, stays through a pause,
   and stops a few minutes after playback ends or the screen goes off. Nothing starts inside
   quiet hours, and a running session stops when they begin: the TV keeps its own speakers at
   night.
7. **Settings screen.** Open the app from the launcher: choose the home speakers or the TV
   speakers, the speaker group, what the volume buttons do, the volume step and maximum, whether
   the TV is muted, the quiet hours and how long after playback the TV gets its sound back.
   Everything works with the remote's arrows and OK. Addresses and tokens are set at
   provisioning time and are not shown.
8. **Home Assistant (optional).** The app posts `house_started` and `house_stopped` to a
   Home Assistant webhook, and `GET /status` can be polled; an example package is in
   [docs/home-assistant.md](docs/home-assistant.md).

### Why there is no lip sync

Version 0.3 tried to hold the picture back by the house delay through the Shield's HDMI
latency parameter (`nv_param_audio_hdmi_hw_latency`, the value behind the Shield's own
"Audio video sync" setting). On the device the parameter accepts 0 to 5000 ms, ExoPlayer apps
(Plex) follow it, but YouTube's player hangs above about 1,2 s, and the speaker path (VBAN,
Music Assistant's live-source buffering, mp3 to Squeezelite players over Wi-Fi) measured about
8 s. With no player able to wait that long, 0.4 dropped the attempt: the parameter is never
touched, and a film's sound on the home speakers arrives late.

## Requirements

- An Android TV device on Android 11 (API 30) or later.
- Music Assistant 2.x with the VBAN receiver plugin enabled, configured with the TV's address as
  the sender, the stream name used by the app, 48000 Hz, S16LE, 2 channels, and an API token.
- A way to run `adb` against the TV, for installation, for granting what the TV's user interface
  cannot grant, and for the first configuration.

## Build

The project is a standard Gradle Android build (Kotlin, AGP 8.13, Gradle 8.14.4, JDK 17, no
libraries beyond the platform).

```sh
./gradlew assembleRelease assembleDebug testDebugUnitTest
```

To build on another machine that has the Android SDK, `tools/build-remote.sh` syncs the tree
over SSH, runs Gradle there and copies the APKs back:

```sh
BUILD_HOST=buildbox BUILD_USER=me ANDROID_SDK=/opt/android-sdk tools/build-remote.sh assembleRelease
```

Release signing reads `ventriloquist.release.storeFile`, `.storePassword`, `.keyAlias` and
`.keyPassword` from Gradle properties outside the repository (for example
`~/.gradle/gradle.properties` on the build machine); without them the release APK is unsigned.
Debug builds install as `net.nuclearlighters.ventriloquist.debug`, so they never clash with a
release build's signature.

## Install and provision

```sh
P=net.nuclearlighters.ventriloquist
adb connect <tv-address>:5555
adb install -r app-release.apk
adb shell pm grant $P android.permission.WRITE_SECURE_SETTINGS   # surround output to PCM
adb shell pm grant $P android.permission.RECORD_AUDIO
adb shell appops set $P SYSTEM_ALERT_WINDOW allow                # consent screen at boot
adb shell cmd notification allow_listener $P/$P.NotificationListener   # media sessions
adb shell settings put secure enabled_accessibility_services $P/$P.VolumeKeyService
adb shell settings put secure accessibility_enabled 1            # volume buttons
B64=$(base64 -w0 config.json)
adb shell am broadcast --include-stopped-packages -n $P/.ConfigReceiver \
    -a $P.action.CONFIGURE --es json_b64 $B64
adb shell am start -n $P/.ConsentActivity                         # accept the dialog once
```

Setting `enabled_accessibility_services` replaces the list: if other services are enabled,
append to the existing value (colon separated) instead.

The configuration broadcast answers `result=0, data="ok: updated ..."` or `result=1,
data="error: ..."`. Its receiver requires `android.permission.DUMP`, which the adb shell holds
and ordinary apps cannot obtain, so only adb (or root) can configure the app this way; later
changes go through `POST /config`. The capture consent lasts as long as the service runs: after
a reboot the app asks again by itself, after an update run the last line again. If status shows
`capture_access: needs_visible_start` (the service was started while the app was not on screen,
so Android withholds background recording), run the last line once more: with a consent held it
only re-arms the access, without a dialog.

`config.json` holds any of these keys (nothing has a site-specific default):

| Key | Default | Meaning |
|---|---|---|
| `control_token` | none | Bearer token of the control endpoint (16 characters or more) |
| `ma_url`, `ma_token` | none | Music Assistant base URL and API token |
| `source_uri` | none | URI of the VBAN receiver's audio source in Music Assistant |
| `player_id` | none | Player or group that plays the source (settings screen: Speakers) |
| `vban_host`, `vban_port`, `vban_stream` | none, 6980, `TV` | Where the VBAN stream goes |
| `enabled` | true | Automatic house mode; false keeps the TV speakers (settings screen: Sound) |
| `mute_box` | true | Mute the box's output while the home speakers play |
| `volume_keys` | true | The remote's volume buttons drive the speakers while they play |
| `volume_step`, `max_volume` | 2, 40 | Points per button press; the highest level the buttons reach |
| `quiet_start`, `quiet_end` | `23:00`, `08:00` | Local time window in which the home speakers are not used |
| `start_debounce_s`, `stop_delay_s` | 3, 180 | Playback time before a start; idle or screen-off time before a stop |
| `ha_webhook_url` | none | Home Assistant webhook told about every start and stop |

Android blocks plain `http://` from apps, so `ma_url` and `ha_webhook_url` must be `https://`
(a reverse proxy in front of the service will do). A configuration stored by 0.3 is migrated:
`music_player_id` becomes `player_id`, the film keys are dropped.

## Control endpoint

Port 8770, HTTP/1.1, JSON in and out, `Authorization: Bearer <control_token>` on every request
(401 without it, 503 until a token is configured).

| Request | Does |
|---|---|
| `GET /status` | Consent, mode, target, volume, box mute, surround setting, watcher view, capture levels, packets sent, errors, what is recorded for restore |
| `GET /players` | The players and groups Music Assistant offers, and the selected one |
| `POST /mode {"mode":"auto"}` | `auto` (home speakers, stored), `off` (TV speakers, stored), `on` (start now, refused in quiet hours) |
| `POST /volume {"steps":±N}` | Moves the speakers' volume by N steps while they play, like the buttons |
| `POST /capture-test {"seconds":N}` | Captures for N seconds (1 to 30), returns RMS and peak per channel in dBFS |
| `POST /config {...}` | Same keys as the broadcast |
| `POST /restore` | Stops house mode and puts everything back |

```sh
curl -s -H "Authorization: Bearer $TOKEN" http://<tv-address>:8770/status
curl -s -H "Authorization: Bearer $TOKEN" -d '{"mode":"off"}' http://<tv-address>:8770/mode
```

## Prior art and credits

- [androidtv-vban-sender](https://github.com/zewelor/androidtv-vban-sender) (Apache-2.0):
  an experimental Android TV VBAN sender that captures as the shell user through the device's
  own ADB port, which needs Android 12. Ventriloquist ports its VBAN packet code and golden
  tests (see [third-party/README.md](third-party/README.md)) and takes a different route for
  capture and for synchronisation.
- [scrcpy](https://github.com/Genymobile/scrcpy) and
  [sndcpy](https://github.com/rom1v/sndcpy) proved playback capture on Android without root.
- [VB-Audio](https://vb-audio.com/) publishes the VBAN protocol for anyone to implement.

## Licence

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
