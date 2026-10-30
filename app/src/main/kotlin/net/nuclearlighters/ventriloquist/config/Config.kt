package net.nuclearlighters.ventriloquist.config

/**
 * Everything the app needs to know about its surroundings. Nothing here has a site-specific
 * default: addresses, tokens and player ids are set at provisioning time, through the ADB
 * configuration broadcast, the token-protected `POST /config`, or the settings screen.
 *
 * JSON keys are the snake_case names in [KEYS]. A merge is all or nothing: one bad value
 * rejects the whole update.
 */
data class Config(
    val controlToken: String = "",
    val maUrl: String = "",
    val maToken: String = "",
    val sourceUri: String = "",
    val playerId: String = "",
    val vbanHost: String = "",
    val vbanPort: Int = 6980,
    val vbanStream: String = "TV",
    /** Automatic house mode: the home speakers play whatever the box plays. False: TV speakers. */
    val enabled: Boolean = true,
    val quietStart: String = "23:00",
    val quietEnd: String = "08:00",
    val startDebounceS: Int = 3,
    val stopDelayS: Int = 180,
    /** Mute the box's own media output while the home speakers play: the TV gets silence. */
    val muteBox: Boolean = true,
    /** The remote's volume buttons drive the home speakers while they play. */
    val volumeKeys: Boolean = true,
    val volumeStep: Int = 2,
    val maxVolume: Int = 40,
    /** Optional Home Assistant webhook told about every start and stop; empty for none. */
    val haWebhookUrl: String = "",
) {

    class ConfigException(val errors: List<String>) :
        IllegalArgumentException(errors.joinToString("; "))

    /** Returns a copy with the keys of [update] applied; a JSON null resets a key to its default. */
    fun merge(update: Map<String, Any?>): Config {
        val errors = mutableListOf<String>()
        var c = this
        val defaults = Config()
        for ((key, raw) in update) {
            try {
                c = when (key) {
                    "control_token" -> c.copy(controlToken = token(raw ?: defaults.controlToken))
                    "ma_url" -> c.copy(maUrl = url(raw ?: defaults.maUrl))
                    "ma_token" -> c.copy(maToken = text(raw ?: defaults.maToken, 4096))
                    "source_uri" -> c.copy(sourceUri = text(raw ?: defaults.sourceUri, 512))
                    "player_id" -> c.copy(playerId = text(raw ?: defaults.playerId, 256))
                    "vban_host" -> c.copy(vbanHost = host(raw ?: defaults.vbanHost))
                    "vban_port" -> c.copy(vbanPort = int(raw ?: defaults.vbanPort, 1..65535))
                    "vban_stream" -> c.copy(vbanStream = stream(raw ?: defaults.vbanStream))
                    "enabled" -> c.copy(enabled = bool(raw ?: defaults.enabled))
                    "quiet_start" -> c.copy(quietStart = clock(raw ?: defaults.quietStart))
                    "quiet_end" -> c.copy(quietEnd = clock(raw ?: defaults.quietEnd))
                    "start_debounce_s" -> c.copy(startDebounceS = int(raw ?: defaults.startDebounceS, 0..600))
                    "stop_delay_s" -> c.copy(stopDelayS = int(raw ?: defaults.stopDelayS, 0..3600))
                    "mute_box" -> c.copy(muteBox = bool(raw ?: defaults.muteBox))
                    "volume_keys" -> c.copy(volumeKeys = bool(raw ?: defaults.volumeKeys))
                    "volume_step" -> c.copy(volumeStep = int(raw ?: defaults.volumeStep, 1..10))
                    "max_volume" -> c.copy(maxVolume = int(raw ?: defaults.maxVolume, 1..100))
                    "ha_webhook_url" -> c.copy(haWebhookUrl = url(raw ?: defaults.haWebhookUrl))
                    else -> throw IllegalArgumentException("unknown key")
                }
            } catch (e: IllegalArgumentException) {
                errors.add("$key: ${e.message}")
            }
        }
        if (errors.isNotEmpty()) throw ConfigException(errors)
        return c
    }

    /** The configuration as JSON-ready values; secrets are replaced by `"set"` or `""`. */
    fun toMap(redactSecrets: Boolean): Map<String, Any?> = linkedMapOf(
        "control_token" to secret(controlToken, redactSecrets),
        "ma_url" to maUrl,
        "ma_token" to secret(maToken, redactSecrets),
        "source_uri" to sourceUri,
        "player_id" to playerId,
        "vban_host" to vbanHost,
        "vban_port" to vbanPort,
        "vban_stream" to vbanStream,
        "enabled" to enabled,
        "quiet_start" to quietStart,
        "quiet_end" to quietEnd,
        "start_debounce_s" to startDebounceS,
        "stop_delay_s" to stopDelayS,
        "mute_box" to muteBox,
        "volume_keys" to volumeKeys,
        "volume_step" to volumeStep,
        "max_volume" to maxVolume,
        // the webhook id in the URL is the webhook's only credential
        "ha_webhook_url" to secret(haWebhookUrl, redactSecrets),
    )

    /** Keys that must be set before house mode can run; empty when ready. */
    fun missingForHouse(): List<String> = buildList {
        if (maUrl.isEmpty()) add("ma_url")
        if (maToken.isEmpty()) add("ma_token")
        if (sourceUri.isEmpty()) add("source_uri")
        if (vbanHost.isEmpty()) add("vban_host")
        if (playerId.isEmpty()) add("player_id")
    }

    /** Quiet start as minutes after midnight. */
    val quietStartMinute: Int get() = minuteOfDay(quietStart)

    /** Quiet end as minutes after midnight. */
    val quietEndMinute: Int get() = minuteOfDay(quietEnd)

    companion object {
        val KEYS: List<String> = Config().toMap(false).keys.toList()

        /** Keys of 0.3 (film mode) that a stored configuration may still hold. */
        val RETIRED_KEYS = setOf(
            "film_player_id", "surround_player_ids", "surround_sync_adjust_ms", "film_latency_ms",
            "film_packages", "music_packages", "tv_mute",
        )

        /**
         * Turns a stored configuration of an older version into one [merge] accepts: the music
         * player becomes the player, film keys are dropped. Unknown keys stay, so a damaged
         * store is still reported.
         */
        fun migrate(stored: Map<String, Any?>): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for ((key, value) in stored) {
                when {
                    key == "music_player_id" -> if (!stored.containsKey("player_id")) out["player_id"] = value
                    key in RETIRED_KEYS -> Unit
                    else -> out[key] = value
                }
            }
            return out
        }

        fun minuteOfDay(hhmm: String): Int {
            val parts = hhmm.split(':')
            return parts[0].toInt() * 60 + parts[1].toInt()
        }

        private fun secret(value: String, redact: Boolean): String =
            if (!redact) value else if (value.isEmpty()) "" else "set"

        private fun text(raw: Any, max: Int): String {
            require(raw is String) { "expected a string" }
            require(raw.length <= max) { "longer than $max characters" }
            return raw.trim()
        }

        private fun token(raw: Any): String {
            val t = text(raw, 256)
            require(t.isEmpty() || t.length >= 16) { "at least 16 characters" }
            require(t.all { it.code in 33..126 }) { "printable ASCII without spaces only" }
            return t
        }

        private fun url(raw: Any): String {
            val t = text(raw, 512).trimEnd('/')
            require(t.isEmpty() || t.startsWith("http://") || t.startsWith("https://")) {
                "must start with http:// or https://"
            }
            return t
        }

        private fun host(raw: Any): String {
            val t = text(raw, 253)
            require(t.none { it.isWhitespace() || it == '/' || it == ':' }) {
                "a host name or IPv4 address, without port"
            }
            return t
        }

        private fun stream(raw: Any): String {
            val t = text(raw, 16)
            require(t.isNotEmpty() && t.all { it.code in 32..126 }) {
                "1 to 16 printable ASCII characters"
            }
            return t
        }

        private fun clock(raw: Any): String {
            val t = text(raw, 5)
            val ok = Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(t)
            require(ok) { "expected HH:MM, 24-hour" }
            return t
        }

        private fun int(raw: Any, range: IntRange): Int {
            val v: Long = when (raw) {
                is Int -> raw.toLong()
                is Long -> raw
                is Double -> if (raw == Math.floor(raw) && raw.isFinite()) raw.toLong() else
                    throw IllegalArgumentException("expected an integer")
                else -> throw IllegalArgumentException("expected an integer")
            }
            require(v in range.first.toLong()..range.last.toLong()) {
                "must be in ${range.first}..${range.last}"
            }
            return v.toInt()
        }

        private fun bool(raw: Any): Boolean {
            require(raw is Boolean) { "expected true or false" }
            return raw
        }
    }
}
