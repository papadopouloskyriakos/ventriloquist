# Ventriloquist design

## Problem

A household has a set of synchronised network speakers driven by Music Assistant, and a TV
driven by an Android TV box. They want the TV's sound on those speakers, with the picture
still in sync, without a receiver, without extra wiring, and without rooting the box.

Two things make this hard:

1. Android does not let an app read another app's audio unless the user grants playback
   capture, and some apps opt out.
2. A capture-and-network path adds delay. Through Music Assistant and a synchronised player
   group it is seconds, not milliseconds, while lip-sync tolerance is well under a tenth of a
   second. The box cannot delay its own video on request. Or so it seems.

## Architecture

```
 Android TV box                           Music Assistant host             speakers
 +----------------------------------+     +---------------------------+    +--------+
 | media app (YouTube, Plex, ...)   |     |                           |    |        |
 |   |  AudioTrack                  |     |  vban_receiver plugin     |    |        |
 |   v                              | UDP |  (source "TV")            |--->| player |
 | mixer --> HDMI out (TV speakers) | --> |        |                  |    | group  |
 |   |                              |6980 |        v                  |--->|        |
 |   +--> playback capture          |     |  player queue / streaming |    |        |
 |          |                       |     +---------------------------+    +--------+
 |          v                       |
 | Ventriloquist service            |
 |   PCM -> VBAN packets -> UDP     |
 |   latency module (Shield)        |
 |   control endpoint (HTTP/intent) |
 +----------------------------------+
```

### Capture

- API: `MediaProjection` + `AudioPlaybackCaptureConfiguration` + `AudioRecord`, matching the
  usages `MEDIA`, `GAME` and `UNKNOWN`. Format 48 kHz, 16-bit, stereo.
- Consent: the system shows its screen-capture consent dialog when the projection is created.
  Android 11 shows it every time; the projection stays valid for as long as the foreground
  service holds it, so in practice the consent is needed once per boot of the box. The dialog
  can be accepted with the remote, or by automation that drives the TV's UI over ADB.
- Opt-outs: apps whose manifest sets `allowAudioPlaybackCapture="false"`, apps targeting
  Android 9 or lower, and protected (DRM) content are not captured. Checked on one Shield:
  Plex, YouTube, YouTube Music, VLC, Jellyfin and TuneIn allow capture; Netflix opts out.
- Passthrough: audio that an app sends as a compressed Dolby bitstream in direct mode bypasses
  the mixer and is not captured. With the box's surround setting on PCM, every app's audio
  goes through the mixer. Ventriloquist will switch that setting to PCM for the duration of
  house mode (needs `WRITE_SECURE_SETTINGS`, grantable once with `adb shell pm grant`).

### Send

- VBAN audio sub-protocol: a 28-byte header (`VBAN`, sample-rate index, frames minus one,
  channels minus one, data type, 16-byte stream name, 32-bit frame counter) followed by
  interleaved little-endian PCM, at most 256 frames per datagram. One UDP socket, one
  destination, no discovery.
- Pacing follows the source clock: each capture read is sent as a bounded run of packets at the
  sample rate, never as a burst, so a slow network cannot grow an unbounded queue in the app.
- The Music Assistant receiver has to match: port (default 6980), stream name, sender address,
  PCM format, sample rate, channel count. It ignores packets while nothing is playing the
  source. Ventriloquist sends only while house mode is on (see Control).

### Play

Music Assistant treats the receiver as an audio source. Selecting it on a player or on a sync
group streams it like any track. Synchronisation between speakers is Music Assistant's job and
is outside this project.

### Lip sync on NVIDIA Shield

The Shield's settings menu has an "Audio video sync" item (Display and Sound, Advanced). It
opens a small system app that lets the user shift audio timing against a test clip. That app
holds the normal `MODIFY_AUDIO_SETTINGS` permission and does one thing: it calls
`AudioManager.setParameters("nv_param_audio_hdmi_hw_latency=<ms>")`, reading the value back with
`getParameters`. The audio policy then reports that much extra latency for the HDMI output.

Why that helps: players that keep video locked to the audio clock (ExoPlayer, which YouTube and
Plex use) compute the audio position as frames written minus output latency. A larger reported
latency means the audio position reads earlier, so video frames are released later. The audio
data itself is not delayed, and the captured copy leaves the mixer at the same moment as
before. Net effect: the picture waits by the configured amount while the captured audio
travels to the speakers. If the house path takes 600 ms, a reported latency of 600 ms puts the
speakers in sync with the picture. The TV's own HDMI sound would run early, which does not
matter because it is muted in house mode.

Ventriloquist's latency module sets the parameter when house mode starts and sets it back to
the previous value when house mode stops. The house delay is measured once (the Shield ships a
sync test clip at `/vendor/oem/media/sync_test.mp4`) and stored.

Unknowns to measure on a device: the maximum value the Shield's audio HAL accepts (the
settings slider has 100 steps; its scale in milliseconds is not documented), and how constant
the house path's delay is between sessions. ExoPlayer ignores reported latencies above five
seconds.

Other Android TV devices do not expose an equivalent parameter. On them Ventriloquist is a
music-grade sender: fine for music apps and for listeners in other rooms, not for dialogue in
the room with the picture.

### The TV's own speakers

The compensation delays the picture, not the HDMI sound. With house mode on, the TV's speakers
would therefore play ahead of both the picture and the house speakers by the whole house delay,
which is an echo. They must be silent while house mode is on.

Ventriloquist silences them from the box, so nothing on the TV has to change:

- It sets the box's media volume for the HDMI output to zero when house mode starts, and restores
  the previous value when house mode stops. On the reference Shield the HDMI output is not a
  fixed-volume device (its fixed list covers only the analogue dock, line and S/PDIF outputs), so
  this volume really scales what the TV receives.
- Playback capture is not affected: Android renders a capture mix at unit gain whatever the
  stream volume is (the audio policy treats a remote-submix output that serves a policy mix as
  fixed volume). To be confirmed on the device in the first test.
- The previous volume is persisted before it is changed, and restored at service stop, at the
  next boot and when the app starts after a crash, so a failure cannot leave the TV mute.
- Remote controls that drive the TV's volume directly (by infrared or HDMI-CEC) keep changing
  the TV, which is silent in house mode. House volume belongs to Music Assistant (its group
  volume, or a home-automation slider).

### Control

House mode is a state: capture running, stream playing on the chosen player group, TV speakers
muted, latency parameter set, surround output forced to stereo PCM. A home-automation system
flips it. The app offers a small local HTTP endpoint (status, start, stop) and equivalent intents
for `adb shell am`, and it restarts its idle service at boot so the endpoint is reachable.

Capture runs only while house mode is on. The consent, however, is obtained once per boot: the
idle service asks for it at boot and keeps the resulting projection, and house mode then opens
and closes the actual capture on that projection as often as needed. This keeps the box idle
(no capture, no packets) outside house mode, which matters on a device with little memory, and
it still starts instantly because no dialog appears when house mode is switched on.

## Latency model

| Stage | Order of magnitude | Notes |
|---|---|---|
| Capture buffer | tens of ms | `AudioRecord` buffer, twice the platform minimum |
| UDP on the LAN | about 1 ms | wired box recommended |
| Receiver queue | configurable | Music Assistant queue size and back-pressure strategy |
| Music Assistant streaming | hundreds of ms to seconds | transcoding and chunking |
| Player start buffer | hundreds of ms | depends on the player type and its sync algorithm |

The sum must be constant for a fixed compensation to work. Variable parts belong on the
Music Assistant side (queue strategy, player start scheduling), not in the app.

## What is deliberately not done

- No capture as the shell user through the device's own ADB port. It avoids the consent dialog
  but needs Android 12 and leaves network ADB permanently enabled. The Shield 2017 stays on
  Android 11.
- No root, no system-partition changes. They would break protected playback on the box.
- No AirPlay, Cast or Bluetooth output. Music Assistant already drives those players.

## Prior art

- [androidtv-vban-sender](https://github.com/zewelor/androidtv-vban-sender): capture-to-VBAN
  on Android TV as the shell user, Android 12 and later, experimental. Source of the VBAN
  packet code ported here. States that A/V synchronisation and fixed end-to-end latency are
  unvalidated.
- [centuryplay](https://github.com/g8row/centuryplay): Android system audio to AirPlay
  speakers (AGPL-3.0). Design reference only.
- [scrcpy](https://github.com/Genymobile/scrcpy) and [sndcpy](https://github.com/rom1v/sndcpy):
  playback capture on Android without root.
- Music Assistant discussions on using a phone or a line input as a source show the demand;
  the project's own planned answer (a Sendspin "source" role) had no implementation as of
  mid 2026.

## Roadmap

1. Skeleton: builds, installs, idle foreground service, status screen. (this repository state)
2. Capture: consent flow, `AudioRecord` on playback capture, level meter on the status screen.
3. Send: VBAN sender with source-clock pacing, settings for destination and stream name.
4. Latency module: read and set the Shield HDMI latency parameter; surround-to-PCM toggle;
   HDMI volume to zero with persisted restore; measurement procedure with the Shield's test
   clip.
5. Control: HTTP endpoint and intents; boot start; home-automation recipe.
6. Release: signing key outside the repository, versioned builds, install notes.
