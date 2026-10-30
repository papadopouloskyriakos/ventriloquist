# Home Assistant

Ventriloquist works without Home Assistant. With it, Home Assistant gets a switch for the app's
"Home speakers / TV speakers" choice, a status sensor, the TV's volume saved when the home
speakers take over and put back afterwards (a remote that sends infrared to the TV moves the
TV's volume with every press, see [DESIGN.md](DESIGN.md)), and an optional second way to silence
the TV: its own integration mutes the TV while the home speakers play and unmutes it afterwards.

The package below needs no restart to add: it uses `rest_command` and template entities (both
reloadable) instead of the REST integration. Put it in `packages/ventriloquist.yaml`, include the
directory once in `configuration.yaml`

```yaml
homeassistant:
  packages: !include_dir_named packages
```

and add two secrets to `secrets.yaml`:

```yaml
ventriloquist_bearer: "Bearer <the app's control_token>"
ventriloquist_webhook_id: "<a long random string>"
```

Replace `TV_ADDRESS` with the box's address and `media_player.YOUR_TV` with the TV's media player
(one whose `volume_mute` sets the state rather than toggling it). Check the configuration, then
reload input booleans, input numbers, REST commands, template entities and automations. Finally give the app
the webhook, over the network:

```sh
curl -s -H "Authorization: Bearer $TOKEN" \
    -d '{"ha_webhook_url":"https://<home-assistant>/api/webhook/<ventriloquist_webhook_id>"}' \
    http://TV_ADDRESS:8770/config
```

Android blocks plain `http://` from apps, so the webhook URL must be `https://`, for example
Home Assistant's own external URL. The webhook is `local_only`; a reverse proxy on the LAN passes.

Turn on `input_boolean.ventriloquist_mute_tv` to let Home Assistant also mute the TV (the muted
box already gives the TV silence, and a volume press unmutes many TVs). It only ever
unmutes a mute it made itself, and a mute or unmute by hand wins. Safety unmutes: the app reports
stopped (webhook or poll), the app is unreachable for 2 minutes, the TV is turned on while
nothing plays, Home Assistant starts.

```yaml
input_boolean:
  ventriloquist_mute_tv:
    name: "Ventriloquist: mute the TV while the home speakers play"
    icon: mdi:television-speaker-off
  ventriloquist_tv_muted:
    name: "Ventriloquist: the TV is muted by Home Assistant"
    icon: mdi:volume-off
  ventriloquist_tv_volume_saved:
    name: "Ventriloquist: the TV's volume is saved for restoring"
    icon: mdi:content-save

input_number:
  ventriloquist_tv_volume:
    name: "Ventriloquist: the TV's volume before the home speakers played"
    icon: mdi:volume-medium
    min: 0
    max: 1
    step: 0.01
    mode: box

rest_command:
  ventriloquist_status:
    url: "http://TV_ADDRESS:8770/status"
    method: GET
    headers:
      Authorization: !secret ventriloquist_bearer
    timeout: 10
  ventriloquist_mode:
    url: "http://TV_ADDRESS:8770/mode"
    method: POST
    headers:
      Authorization: !secret ventriloquist_bearer
    content_type: "application/json"
    payload: '{"mode":"{{ mode }}"}'
    timeout: 20

template:
  - triggers:
      - trigger: time_pattern
        seconds: "/30"
      - trigger: homeassistant
        event: start
      - trigger: event
        event_type: ventriloquist_refresh
    actions:
      - action: rest_command.ventriloquist_status
        response_variable: reply
        continue_on_error: true
    sensor:
      - name: "Ventriloquist"
        unique_id: ventriloquist_status
        icon: mdi:speaker-multiple
        availability: "{{ reply is defined and reply.status == 200 and reply.content is mapping }}"
        state: "{{ reply.content.mode }}"
        attributes:
          house: "{{ reply.content.house }}"
          enabled: "{{ reply.content.enabled }}"
          manual: "{{ reply.content.manual }}"
          target: "{{ reply.content.target }}"
          volume: "{{ reply.content.volume }}"
          box_muted: "{{ reply.content.box_muted }}"
          takes_volume_keys: "{{ reply.content.takes_volume_keys }}"
          blocked: "{{ reply.content.watcher.blocked }}"
          last_error: "{{ reply.content.last_error }}"
          version: "{{ reply.content.version }}"
  - switch:
      - name: "TV sound to the home speakers"
        unique_id: ventriloquist_enabled
        icon: mdi:speaker-wireless
        state: "{{ state_attr('sensor.ventriloquist', 'enabled') == true }}"
        availability: "{{ has_value('sensor.ventriloquist') }}"
        turn_on:
          - action: rest_command.ventriloquist_mode
            data:
              mode: auto
          - event: ventriloquist_refresh
        turn_off:
          - action: rest_command.ventriloquist_mode
            data:
              mode: "off"
          - event: ventriloquist_refresh

automation:
  - id: ventriloquist_tv_mute
    alias: "Ventriloquist - the TV while the home speakers play: save and restore its volume, optional mute"
    description: >-
      When the Ventriloquist app starts playing on the home speakers: saves the
      TV's volume (the Shield remote's own infrared moves it with every press while the
      speakers play) and, if ventriloquist_mute_tv is on, mutes the TV. When it stops: unmutes
      only a mute this automation made, and puts the saved volume back. Also on: app
      unreachable for 2 minutes, the TV turned on while nothing plays, Home Assistant start.
    mode: queued
    max: 10
    triggers:
      - trigger: webhook
        webhook_id: !secret ventriloquist_webhook_id
        allowed_methods:
          - POST
        local_only: true
        id: hook
      - trigger: state
        entity_id: sensor.ventriloquist
        to: house
        id: poll
      - trigger: state
        entity_id: sensor.ventriloquist
        from: house
        id: poll
      - trigger: state
        entity_id: sensor.ventriloquist
        to: unavailable
        for: "00:02:00"
        id: lost
      - trigger: state
        entity_id: media_player.YOUR_TV
        from: "off"
        to: "on"
        id: tv_on
      - trigger: homeassistant
        event: start
        id: ha_start
    actions:
      - variables:
          house: >-
            {{ (trigger.json.house | default(false)) == true if trigger.id == 'hook'
               else (false if trigger.id == 'lost'
               else is_state('sensor.ventriloquist', 'house')) }}
      - if:
          - condition: template
            value_template: "{{ house }}"
        then:
          # save the TV's own volume once, while it is known (some TVs report 0 while muted)
          - if:
              - condition: state
                entity_id: input_boolean.ventriloquist_tv_volume_saved
                state: "off"
              - condition: state
                entity_id: media_player.YOUR_TV
                state: "on"
              - condition: template
                value_template: >-
                  {{ state_attr('media_player.YOUR_TV', 'is_volume_muted') != true
                     and state_attr('media_player.YOUR_TV', 'volume_level') is number }}
            then:
              - action: input_number.set_value
                target:
                  entity_id: input_number.ventriloquist_tv_volume
                data:
                  value: "{{ state_attr('media_player.YOUR_TV', 'volume_level') | round(2) }}"
              - action: input_boolean.turn_on
                target:
                  entity_id: input_boolean.ventriloquist_tv_volume_saved
          - if:
              - condition: state
                entity_id: input_boolean.ventriloquist_mute_tv
                state: "on"
              - condition: state
                entity_id: media_player.YOUR_TV
                state: "on"
              - condition: template
                value_template: "{{ state_attr('media_player.YOUR_TV', 'is_volume_muted') != true }}"
            then:
              - action: input_boolean.turn_on
                target:
                  entity_id: input_boolean.ventriloquist_tv_muted
              - action: media_player.volume_mute
                target:
                  entity_id: media_player.YOUR_TV
                data:
                  is_volume_muted: true
        else:
          # a TV that is off can be neither unmuted nor set: the flags stay, the TV turning on
          # (trigger tv_on) finishes the job
          - if:
              - condition: state
                entity_id: input_boolean.ventriloquist_tv_muted
                state: "on"
              - condition: state
                entity_id: media_player.YOUR_TV
                state: "on"
            then:
              - action: media_player.volume_mute
                target:
                  entity_id: media_player.YOUR_TV
                data:
                  is_volume_muted: false
              - action: input_boolean.turn_off
                target:
                  entity_id: input_boolean.ventriloquist_tv_muted
              - delay: "00:00:02"
          - if:
              - condition: state
                entity_id: input_boolean.ventriloquist_tv_volume_saved
                state: "on"
              - condition: state
                entity_id: media_player.YOUR_TV
                state: "on"
            then:
              - if:
                  - condition: template
                    value_template: >-
                      {{ (state_attr('media_player.YOUR_TV', 'volume_level') | float(-1) | round(2))
                         != (states('input_number.ventriloquist_tv_volume') | float(-1) | round(2)) }}
                then:
                  - action: media_player.volume_set
                    target:
                      entity_id: media_player.YOUR_TV
                    data:
                      volume_level: "{{ states('input_number.ventriloquist_tv_volume') | float }}"
              - action: input_boolean.turn_off
                target:
                  entity_id: input_boolean.ventriloquist_tv_volume_saved
      - event: ventriloquist_refresh

  - id: ventriloquist_tv_unmuted_by_hand
    alias: "Ventriloquist - the TV was unmuted by hand"
    description: >-
      Someone unmuted the TV (remote, app) while Home Assistant held it
      muted for the home speakers: the hand wins, Home Assistant forgets its mute.
    mode: single
    triggers:
      - trigger: state
        entity_id: media_player.YOUR_TV
        attribute: is_volume_muted
        to: false
    conditions:
      - condition: state
        entity_id: input_boolean.ventriloquist_tv_muted
        state: "on"
    actions:
      - action: input_boolean.turn_off
        target:
          entity_id: input_boolean.ventriloquist_tv_muted
```
