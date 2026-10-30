# Ventriloquist design

## Problem

A household has a set of synchronised network speakers driven by Music Assistant, and a TV
driven by an Android TV box. They want whatever the box plays (music apps, radio, a video's
sound) on those speakers, switched on and off by itself, with the remote's volume buttons
working on the speakers, without a receiver, without extra wiring, and without rooting the box.

Android does not let an app read another app's audio unless the user grants playback capture,
and some apps opt out. Everything else is plumbing, and putting things back.

Lip sync is not a goal any more: see "Lip sync: tried and dropped".

## Architecture

```
 Android TV box                           Music Assistant host             speakers
 +----------------------------------+     +---------------------------+    +--------+
 | media app (any)                  |     |                           |    |        |
 |   |  AudioTrack                  |     |  vban_receiver plugin     |    |        |
 |   v                              | UDP |  (source "TV")            |--->| player |
 | mixer --> HDMI out (muted)       | --> |        |                  |    | group  |
 |   |                              |6980 |        v                  |--->|        |
 |   +--> playback capture          |     |  player queue / streaming |    |        |
 |          |                       |     +---------------------------+    +--------+
 |          v                       |            ^  HTTP API: play, stop, group volume
 | Ventriloquist service  ----------+------------+
 |   PCM -> VBAN packets -> UDP     |
 |   box module (mute, PCM)         |     Home Assistant (optional)
 |   volume buttons (accessibility) |     webhook: started / stopped; polls GET /status
 |   control endpoint, settings UI  | --> mutes the TV, unmutes it afterwards
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
  goes through the mixer. Ventriloquist switches that setting to PCM for the duration of
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
is outside this project. Music Assistant is driven over its HTTP API: `POST {ma_url}/api` with
`Authorization: Bearer {ma_token}` and `{"message_id", "command", "args"}`; the reply body is
the command's result. A status other than 200, or an object with `error_code`, is a failure.
Commands used: `player_queues/play_media` with `option: replace`, `players/cmd/stop`,
`players/get` (volume), `players/cmd/group_volume`, `players/all` (the settings screen).

### The TV's own speakers

The TV would play the same sound seconds before the house speakers, which is an echo. It is
silenced from the box, so nothing on the TV has to change:

- When house mode starts, the box's media stream is muted with
  `adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE)`; when it stops, the previous mute state comes
  back. The TV then receives silence over HDMI.
- The volume index is never stepped. On a box that controls the TV's volume by infrared or
  HDMI-CEC (an NVIDIA Shield does), an index change becomes a volume command to the TV: version
  0.3 set the index to 0 and the Shield sent the TV sixteen infrared volume-down commands.
- Playback capture is not affected: Android renders a capture mix at unit gain whatever the
  stream volume or mute is (the audio policy treats a remote-submix output that serves a policy
  mix as fixed volume).
- `mute_box` turns this off, for a TV that should keep playing.
- A home-automation system can mute the TV itself as a second layer; the app tells it when
  house mode starts and stops (see Home Assistant).

### Volume buttons

The remote's volume buttons should move the speakers while they play. Android gives an app the
key before the system acts on it in one place only: an accessibility service with key-event
filtering (`flagRequestFilterKeyEvents`). Ventriloquist's service returns false (the key goes on
to the system) unless house mode runs and `volume_keys` is on; then it takes both the press and
the release, and each press or key repeat is one step. Presses are gathered for about a tenth
of a second and sent as one `players/cmd/group_volume` call, so holding a button does not queue
a call per repeat; the level just set is reused for five seconds instead of being read back.
Up never passes `max_volume` (and never lowers a level set higher elsewhere); down stops at 0.
A short message on screen shows the new level. The service reads no window content and asks
for no accessibility events; it is enabled once over adb.

A remote that sends infrared to the TV by itself, without the box, cannot be stopped by any app.
On a Shield the 2019 remote can work that way: the Shield programs the TV's infrared codes into
the remote ("IR mode"). Its log shows the IR mode switched off and on again at the same moments
as the media stream is muted and unmuted, which on the observed device happens when the box goes
to sleep and wakes; whether the mute or the sleep is the cause is not known. The key also
reaches Android as `KEY_VOLUMEUP` or `KEY_VOLUMEDOWN` (seen with `getevent`), and with IR mode on
the system then changes no volume of its own. Whether muting the box during house mode keeps the
remote's infrared away from the TV is the open question of the first test of 0.4; no published
test was found.

### House mode

House mode is a state: capture running, the stream playing on `player_id`, the box's output
muted, surround output forced to stereo PCM, the volume buttons taken.

**Start:** surround output to PCM; mute the box; start capture and VBAN; record the player;
play `source_uri` on it; notify Home Assistant. **Stop:** stop the player; stop capture; put the
box back (the TV gets its sound back last); notify Home Assistant. If any step of a start
fails, everything done so far is put back and the error is reported.

### Automatic mode

A watcher feeds a small state machine once a second and whenever the platform reports a change:

- **Inputs:** the active media sessions and their playback state (through a notification
  listener grant, which the app needs only for `MediaSessionManager.getActiveSessions`; it reads
  no notifications), the active audio players' usage and state
  (`AudioManager.registerAudioPlaybackCallback`), and screen on or off.
- **Playing, paused, idle:** a session that plays (or buffers) means playing; without one, a
  media or game player that is known to be started means playing; a paused session whose player
  still exists means paused; anything else is idle. Any app counts.
- **Rules (when `enabled`):** start `start_debounce_s` after playback started, with the screen
  on; stay through a pause; stop `stop_delay_s` after playback ended, or after the screen went
  off. Nothing starts inside the quiet hours `quiet_start` to `quiet_end` (local time, across
  midnight allowed), and a running session stops when they begin. After a failed start the
  machine waits five minutes, or until playback ends and starts again.
- **Disabled** (`enabled` false, "TV speakers"): stops at once and starts nothing. The choice is
  stored, so it survives a reboot.
- **Start now:** `POST /mode {"mode":"on"}` runs house mode whatever plays, until
  `POST /mode` again, until the screen has been off for the stop delay, or until quiet hours
  begin; it is refused inside them.

The machine is plain Kotlin with an injected clock, so every rule is covered by unit tests.

### Settings screen

The launcher opens a screen made for a remote: a status line and one row per setting, each a
button whose choices open in a dialog. Rows: Sound (home speakers or TV speakers), Speakers
(the Music Assistant players and groups, fetched live), Volume buttons, Volume step, Maximum
volume, TV sound while the speakers play, Quiet hours (fixed choices, never off), Back to the
TV after playback stops, Capture permission. Addresses and tokens are not shown; they are set
at provisioning time.

### Home Assistant

With `ha_webhook_url` set, every start and stop is posted as JSON
(`{"event":"house_started"|"house_stopped","house":true|false,"player":...,"reason":...}`) on a
thread of its own, with one retry; a webhook URL carries its own credential, so no token is
sent. The webhook is best effort: Home Assistant should also poll `GET /status` and treat an
unreachable app as stopped. [home-assistant.md](home-assistant.md) has a package that mutes the
TV through its own integration while the speakers play and unmutes it afterwards, unmuting only
a mute it made itself.

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

Every change Ventriloquist makes, on the box (mute, surround output) and in Music Assistant
(which player plays the source), is first recorded in a ledger: one JSON object per side in
SharedPreferences, written synchronously, before the change is made. If the record cannot be
written, the change is not made. The first recorded original wins, so repeated changes still
restore to the state before Ventriloquist touched anything.

The ledgers are replayed on stop, on `POST /restore`, at boot, and when the service starts with
a ledger still dirty (after a crash or a kill). The box side is restored at once; the Music
Assistant side follows over the network and is retried every minute while it fails. Entries
that version 0.3 recorded (HDMI latency, volume index, rear players' settings) are restored or
dropped the same way.

## Lip sync: tried and dropped

Version 0.3 delayed the picture by the house delay. The Shield's "Audio video sync" setting is a
small system app that calls `AudioManager.setParameters("nv_param_audio_hdmi_hw_latency=<ms>")`;
the audio policy then reports that much extra HDMI latency, and players that lock video to the
audio clock release frames later by the same amount. Measured on a Shield TV (2017, Android 11):

| Finding | Value |
|---|---|
| Parameter range accepted and read back | 0 to 5000 ms |
| Plex (ExoPlayer) | follows it up to 4900 ms (pause, set, play) |
| YouTube (Cobalt) | plays at 400 and 800 ms, hangs at 1200 ms and above; a change while playing wedges it |
| Speaker path (VBAN, Music Assistant live-source buffering, mp3 to Squeezelite over Wi-Fi) | about 8 s behind the picture, by eye |

No player waits 8 s, and the parameter cannot hold more than 5 s anyway. Shortening the path
would mean smaller live-source buffers in Music Assistant, with dropouts on 2,4 GHz Wi-Fi
players. 0.4 dropped film mode, the latency module and the rear-speaker matrix: the parameter is
never set, and a film's sound on the home speakers arrives late.

## Latency model

| Stage | Order of magnitude | Notes |
|---|---|---|
| Capture buffer | tens of ms | `AudioRecord` buffer, twice the platform minimum |
| UDP on the LAN | about 1 ms | wired box recommended |
| Receiver queue | configurable | Music Assistant queue size and back-pressure strategy |
| Music Assistant streaming | seconds | live-source buffering, encoding |
| Player buffers and start scheduling | seconds | depends on the player type and its sync algorithm |

Measured end to end with Squeezelite players: about 8 s. Fine for music, late for dialogue.

## What is deliberately not done

- No capture as the shell user through the device's own ADB port. It avoids the consent dialog
  but needs Android 12 and leaves network ADB permanently enabled. The Shield 2017 stays on
  Android 11.
- No root, no system-partition changes. They would break protected playback on the box.
- No AirPlay, Cast or Bluetooth output. Music Assistant already drives those players.
- No lip sync (see above).
- No writes to the box vendor's own settings (such as the Shield's volume-control mode): a
  normal app cannot write them, and changing how a remote controls the TV behind the
  household's back is not the app's call.

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

1. Skeleton, capture, VBAN sending, control endpoint, crash-safe restore, release signing. Done
   (0.2, 0.3).
2. First device run: capture in the background, capture at unit gain, VBAN into Music
   Assistant, lip sync measured and dropped. Done (0.3.2).
3. 0.4: music only, any app, the box muted instead of the volume index, volume buttons on the
   speakers, settings screen, Home Assistant webhook, quiet hours stop a running session. Built;
   device test of the volume buttons and the mute next.
