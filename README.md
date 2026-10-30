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

Pre-alpha. This repository holds the project skeleton: it builds, installs, shows a status
screen and runs an idle foreground service. **Nothing captures or sends audio yet.**
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

## Requirements

- An Android TV device on Android 10 (API 29) or later. Lip-sync compensation needs an NVIDIA
  Shield.
- Music Assistant 2.x with the VBAN receiver plugin enabled, configured with the TV's address as
  the sender, the stream name used by the app, 48000 Hz, S16LE, 2 channels.
- A way to run `adb` against the TV, for installation and for granting one permission that the
  TV's user interface cannot grant.

## Build

The project is a standard Gradle Android build (Kotlin, AGP 8.13, Gradle 8.14.4, JDK 17).

```sh
./gradlew assembleDebug testDebugUnitTest
```

To build on another machine that has the Android SDK, `tools/build-remote.sh` syncs the tree
over SSH, runs Gradle there and copies the APKs back:

```sh
BUILD_HOST=buildbox BUILD_USER=me ANDROID_SDK=/opt/android-sdk tools/build-remote.sh
```

Install on the TV:

```sh
adb connect <tv-address>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
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
