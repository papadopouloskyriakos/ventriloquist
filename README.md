# Ventriloquist

Throw your Android TV's voice into the speakers around the house, and keep the lips in sync.

Ventriloquist is an Android TV app that captures what the TV is playing and sends it as a
[VBAN](https://vb-audio.com/Voicemeeter/vban.htm) stream (PCM over UDP) to
[Music Assistant](https://www.music-assistant.io/). Music Assistant's
[VBAN receiver](https://www.music-assistant.io/plugins/vban-receiver/) turns the stream into a
source that any of its players or sync groups can play: Squeezelite, Sonos, Cast, AirPlay,
Sendspin, DLNA.

A networked audio path is slow compared with the TV's own speakers. On an NVIDIA Shield,
Ventriloquist compensates: it asks the Shield's audio stack to report that much extra output
latency, and every player that keeps video locked to the audio clock (YouTube, Plex, anything
built on ExoPlayer) presents its picture later by the same amount. The picture and the house
speakers then match.

## Status

Alpha, not yet proven on a device. The app holds the capture consent in a foreground service,
captures what the box plays, sends it as VBAN, drives Music Assistant over its HTTP API, holds
the picture back on an NVIDIA Shield, silences the TV's own speakers, and switches all of this
on and off by itself when playback starts and ends. Every change it makes, on the box and in
Music Assistant, is recorded first and put back afterwards, also after a crash or a reboot.
See [docs/DESIGN.md](docs/DESIGN.md) for the design and the roadmap.

## How it works

1. **Capture.** Android's playback-capture API (Android 10 and later) hands the app a copy of
   the audio that other apps play, after the user accepts the system's consent dialog once per
   app start. Apps can opt out, and protected content is never captured; YouTube and Plex
   allow it, Netflix does not.
2. **Send.** The PCM is packetised as VBAN (48 kHz, 16-bit, stereo, up to 256 frames per
   datagram) and sent over UDP to the Music Assistant host.
3. **Play.** Music Assistant exposes the stream as a source. Play it on one player or on a
   sync group and the TV's sound is everywhere.
4. **Lip sync (NVIDIA Shield).** The Shield's own "Audio video sync" setting works by setting
   an audio HAL parameter for the HDMI output latency. Ventriloquist sets the same parameter to
   the measured house delay while house mode is on, and clears it when house mode is off. Other
   Android TV devices get steps 1 to 3 only.
5. **TV speakers.** The picture is delayed but the HDMI sound is not, so the TV's own speakers
   would run ahead as an echo. In house mode Ventriloquist turns the box's HDMI volume to zero
   (capture keeps full level) and restores it afterwards, also after a crash or a reboot.
6. **Film and music.** House mode comes in two kinds. *Film* plays on a film player group,
   delays the picture by the measured house delay, and can turn chosen speakers into rear
   surround speakers (Music Assistant's per-player `surround` output channel: the difference
   of left and right, as a Pro Logic style matrix decoder derives it, band limited to 100 Hz
   to 7 kHz). *Music* plays on a music player group with no picture delay and leaves the
   speakers' channels alone.
7. **Automatic.** A watcher follows media sessions, active audio players and the screen. It
   starts house mode a few seconds after playback starts, stays through a pause, and stops a
   few minutes after playback ends or the screen goes off. It never starts inside quiet hours.
   A local HTTP endpoint overrides it (`auto`, `off`, `film`, `music`).

## Requirements

- An Android TV device on Android 11 (API 30) or later. Lip-sync compensation needs an NVIDIA
  Shield.
- Music Assistant 2.x with the VBAN receiver plugin enabled, configured with the TV's address as
  the sender, the stream name used by the app, 48000 Hz, S16LE, 2 channels, and an API token.
- For film mode's rear speakers: a Music Assistant build whose per-player output channels offer
  `surround`. Stock builds offer stereo, left, right and mono; leave `surround_player_ids` empty
  there and film mode changes no channels.
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
B64=$(base64 -w0 config.json)
adb shell am broadcast --include-stopped-packages -n $P/.ConfigReceiver \
    -a $P.action.CONFIGURE --es json_b64 $B64
adb shell am start -n $P/.ConsentActivity                         # accept the dialog once
```

The configuration broadcast answers `result=0, data="ok: updated ..."` or `result=1,
data="error: ..."`. Its receiver requires `android.permission.DUMP`, which the adb shell holds
and ordinary apps cannot obtain, so only adb (or root) can configure the app this way; later
changes go through `POST /config`. The capture consent lasts as long as the service runs: after
a reboot the app asks again by itself, after an update run the last line again.

`config.json` holds any of these keys (nothing has a site-specific default):

| Key | Default | Meaning |
|---|---|---|
| `control_token` | none | Bearer token of the control endpoint (16 characters or more) |
| `ma_url`, `ma_token` | none | Music Assistant base URL and API token |
| `source_uri` | none | URI of the VBAN receiver's audio source in Music Assistant |
| `film_player_id`, `music_player_id` | none | Player (or group) that plays the source in each mode |
| `surround_player_ids` | `[]` | Players switched to the `surround` output channel in film mode |
| `surround_sync_adjust_ms` | -15 | Their `sync_adjust` during film mode |
| `vban_host`, `vban_port`, `vban_stream` | none, 6980, `TV` | Where the VBAN stream goes |
| `film_latency_ms` | 0 | HDMI latency in film mode: the measured house delay |
| `quiet_start`, `quiet_end` | `23:00`, `08:00` | Local time window in which nothing starts |
| `film_packages`, `music_packages` | `[]` | Apps that always mean film or music |
| `start_debounce_s`, `stop_delay_s` | 3, 180 | Playback time before a start; idle or screen-off time before a stop |

## Control endpoint

Port 8770, HTTP/1.1, JSON in and out, `Authorization: Bearer <control_token>` on every request
(401 without it, 503 until a token is configured).

| Request | Does |
|---|---|
| `GET /status` | Consent, mode, override, target, latency read back, HDMI volume, surround setting, watcher view, capture levels, packets sent, errors, what is recorded for restore |
| `GET /latency` | The HDMI latency the audio HAL reports |
| `POST /latency {"ms":N}` | Sets it (0 to 5000) and returns the read-back |
| `POST /latency/step {"ms":±N}` | Moves `film_latency_ms` (stored), applied at once in film mode: for calibration |
| `POST /tv-mute {"on":true}` | Mutes or restores the HDMI volume |
| `POST /capture-test {"seconds":N}` | Captures for N seconds (1 to 30), returns RMS and peak per channel in dBFS |
| `POST /mode {"mode":"auto"}` | Override: `auto`, `off`, `film` or `music` (manual starts refused in quiet hours) |
| `POST /config {...}` | Same keys as the broadcast |
| `POST /restore` | Stops house mode, puts everything back, sets the override to `off` |

```sh
curl -s -H "Authorization: Bearer $TOKEN" http://<tv-address>:8770/status
curl -s -H "Authorization: Bearer $TOKEN" -d '{"mode":"film"}' http://<tv-address>:8770/mode
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
