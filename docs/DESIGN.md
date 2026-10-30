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
- Background recording: playback capture counts as recording, and Android 11 and later let a
  foreground service record while another app is in front only if the service has the
  `microphone` type and was started while an activity of the app was visible (while-in-use
  access). Without it the audio server refuses the record (app-op 27, `AudioRecord` error -20)
  although the consent is held. So every service start that matters comes from the translucent
  consent activity while it is on screen, the service never leaves the foreground until it
  stops, and opening the consent activity when a consent is already held re-arms the access
  without a dialog. Status reports `capture_access` (`granted` or `needs_visible_start`), read
  from the evaluated RECORD_AUDIO app-op; house mode does not start without it.
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
- Pacing follows the source clock: each capture read (20 ms) becomes a bounded run of packets,
  split evenly at no more than 256 frames each, and packet k leaves when the frames before it
  are due at the sample rate, counted from the first read, never as a burst. Nothing is queued:
  a slow network cannot grow a queue in the app. After a stall of more than 100 ms the sender
  re-anchors at the present instead of catching up in a burst. The 32-bit frame counter counts
  packets. Send errors are counted and reported, never thrown, so a network fault cannot stop
  capture.
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
  the previous value when house mode stops. Where the platform keeps the HDMI index at its
  maximum (a full-volume output), it mutes the media stream instead
  (`adjustStreamVolume(ADJUST_MUTE)`) and unmutes it afterwards; each call, with the index and
  mute state before and after, is returned by `POST /tv-mute` and shown in the status. On the reference Shield the HDMI output is not a
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

### House mode: film and music

House mode is a state: capture running, the stream playing on a Music Assistant player or
group, TV speakers muted, surround output forced to stereo PCM, and (in film mode) the latency
parameter set. It comes in two kinds, because a film and an album want different things from
the same speakers:

| | Film | Music |
|---|---|---|
| Plays on | `film_player_id` (the speakers around the TV) | `music_player_id` (any group, other rooms too) |
| HDMI latency | `film_latency_ms`, the measured house delay | 0: there is no picture to wait for |
| Rear speakers | `surround_player_ids` switched to the `surround` output channel, `sync_adjust` set to `surround_sync_adjust_ms` | unchanged |
| TV speakers | muted | muted |

**Matrix surround.** A stereo film soundtrack carries its surround information the way Dolby
Surround and Pro Logic encode it: what should come from behind is in antiphase between left and
right. A Pro Logic style decoder recovers it as the difference of the two channels, in which
dialogue (centred, so identical in both) cancels out. Music Assistant's per-player `surround`
output channel does that on the Music Assistant side: the player receives 0.5 L minus 0.5 R,
band limited to 100 Hz to 7 kHz like the surround channel of the matrix, while the front
speakers keep full stereo. Ventriloquist only switches the chosen rear players to that mode for
the film and back afterwards; the decoding is Music Assistant's. The `sync_adjust` step holds
the rear speakers back slightly, like the delay a matrix decoder puts on its surround channel:
the rear sound then arrives after the front, so whatever dialogue leaks into the difference
signal is still heard from the front. For Squeezelite players a positive `sync_adjust` plays
earlier, so the default `-15` ms plays the rear 15 ms later; check the sign for other player
types. Stock Music Assistant
builds offer only stereo, left, right and mono; with them `surround_player_ids` stays empty.

**Sequences.** Film start: record and set each rear player's channels and sync adjustment; set
the latency; mute the TV; surround output to PCM; start capture and VBAN; play `source_uri` on
the film player. Music start: the same without the rear players and with latency 0. Stop: stop
the playing player; stop capture; put the rear players back; then latency, surround output and
TV volume (the TV speakers come back last, once the picture is no longer delayed). A switch from
film to music (or back) moves only the Music Assistant side: the old player stops, the rear
players go back, the new player starts, while capture keeps running and the TV stays muted, so
the TV speakers do not blip in between. If any step of a start fails, everything done so far is
put back and the error is reported.

Music Assistant is driven over its HTTP API: `POST {ma_url}/api` with `Authorization: Bearer
{ma_token}` and `{"message_id", "command", "args"}`; the reply body is the command's result. A
status other than 200, or an object with `error_code`, is a failure. Commands used:
`config/players/get` and `config/players/save` (rear players), `player_queues/play_media` with
`option: replace`, `players/cmd/stop`.

### Automatic mode

A watcher feeds a small state machine once a second and whenever the platform reports a change:

- **Inputs:** the active media sessions and their playback state (through a notification
  listener grant, which the app needs only for `MediaSessionManager.getActiveSessions`; it reads
  no notifications), the active audio players' usage and content type
  (`AudioManager.registerAudioPlaybackCallback`), and screen on or off.
- **Playing, paused, idle:** a session that plays (or buffers) means playing; without one, a
  media player that is known to be started means playing; a paused session whose player still
  exists means paused; anything else is idle.
- **Film or music:** a package in `film_packages` means film, a package in `music_packages`
  means music; otherwise music content means music and everything else (movie, speech, unknown)
  means film.
- **Rules:** start `start_debounce_s` after playback started, with the screen on; stay through a
  pause; stop `stop_delay_s` after playback ended, or after the screen went off; switch film and
  music when the kind of playback changes (debounced like a start). Nothing starts or switches
  inside the quiet hours `quiet_start` to `quiet_end` (local time, across midnight allowed); a
  mode already running finishes by the normal stop rule. After a failed start the machine waits
  five minutes, or until playback ends and starts again.
- **Override:** `auto` (default) follows the rules; `off` stops at once and starts nothing;
  `film` and `music` run that mode whatever plays, until the override changes. A manual start is
  refused inside quiet hours, and a manual mode falls back to `auto` when the screen has been
  off for the stop delay, so a forgotten override cannot keep the TV muted.

The machine is plain Kotlin with an injected clock, so every rule is covered by unit tests.

### Control

The app offers a small local HTTP endpoint (port 8770, a bearer token, JSON; the request list is
in the README) and restarts its idle service at boot so the endpoint is reachable. The server is
a plain `ServerSocket` with a strict parser: one request per connection, Content-Length bodies
only, header block and body sizes capped, a small worker pool with a bounded queue.

The first configuration, which includes the token, cannot go through the token-protected
endpoint. It arrives as an explicit broadcast sent with `adb shell am broadcast`, carrying the
JSON (or its base64). The receiver is exported but requires `android.permission.DUMP`: the adb
shell holds it, ordinary apps cannot get it, so only adb or root can reach the receiver. An
update is all or nothing: one unknown key or bad value rejects it, and the reply says which.

Capture runs only while house mode is on. The consent, however, is obtained once per boot: the
idle service asks for it at boot and keeps the resulting projection, and house mode then opens
and closes the actual capture on that projection as often as needed. This keeps the box idle
(no capture, no packets) outside house mode, which matters on a device with little memory, and
it still starts instantly because no dialog appears when house mode is switched on. Opening the
consent screen from the background at boot needs the "display over other apps" app-op, granted
once over adb.

### Crash-safe restore

Every change Ventriloquist makes, on the box (HDMI latency, HDMI volume, surround output) and in
Music Assistant (rear players' channels and sync adjustment, which player plays the source),
is first recorded in a ledger: one JSON object per side in SharedPreferences, written
synchronously, before the change is made. If the record cannot be written, the change is not
made. The first recorded original wins, so repeated changes still restore to the state before
Ventriloquist touched anything.

The ledgers are replayed on stop, on `POST /restore`, at boot, and when the service starts with
a ledger still dirty (after a crash or a kill). The box side is restored at once; the Music
Assistant side follows over the network and is retried every minute while it fails, each item
staying recorded until it has been put back.

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

1. Skeleton: builds, installs, idle foreground service, status screen. Done.
2. Capture: consent flow, `AudioRecord` on playback capture, level measurement. Done.
3. Send: VBAN sender with source-clock pacing, destination and stream name from configuration.
   Done.
4. Latency module: read and set the Shield HDMI latency parameter; surround-to-PCM toggle;
   HDMI volume to zero with persisted restore. Done; the measurement procedure with the
   Shield's test clip is next.
5. Control: HTTP endpoint, ADB configuration broadcast, boot start, automatic film and music
   modes with quiet hours. Done; a home-automation recipe is next.
6. Release: signing key outside the repository, versioned builds, install notes. Done.
7. First device run: confirm capture at unit gain with the HDMI volume at zero, the HAL's
   latency ceiling, and how constant the house delay is between sessions.
