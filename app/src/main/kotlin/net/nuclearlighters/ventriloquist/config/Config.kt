package net.nuclearlighters.ventriloquist.config

/**
 * Everything the app needs to know about its surroundings. Nothing here has a site-specific
 * default: addresses, tokens and player ids are set at provisioning time, through the ADB
 * configuration broadcast or the token-protected `POST /config`.
 *
 * JSON keys are the snake_case names in [KEYS]. A merge is all or nothing: one bad value
 * rejects the whole update.
 */
data class Config(
    val controlToken: String = "",
    val maUrl: String = "",
    val maToken: String = "",
    val sourceUri: String = "",
    val filmPlayerId: String = "",
    val musicPlayerId: String = "",
    val surroundPlayerIds: List<String> = emptyList(),
    val surroundSyncAdjustMs: Int = -15,
    val vbanHost: String = "",
    val vbanPort: Int = 6980,
    val vbanStream: String = "TV",
    val filmLatencyMs: Int = 0,
    val quietStart: String = "23:00",
    val quietEnd: String = "08:00",
    val filmPackages: List<String> = emptyList(),
    val musicPackages: List<String> = emptyList(),
    val startDebounceS: Int = 3,
    val stopDelayS: Int = 180,
    val tvMute: Boolean = true,
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
                    "film_player_id" -> c.copy(filmPlayerId = text(raw ?: defaults.filmPlayerId, 256))
                    "music_player_id" -> c.copy(musicPlayerId = text(raw ?: defaults.musicPlayerId, 256))
                    "surround_player_ids" ->
                        c.copy(surroundPlayerIds = textList(raw ?: defaults.surroundPlayerIds, 256))
                    "surround_sync_adjust_ms" ->
                        c.copy(surroundSyncAdjustMs = int(raw ?: defaults.surroundSyncAdjustMs, -500..500))
                    "vban_host" -> c.copy(vbanHost = host(raw ?: defaults.vbanHost))
                    "vban_port" -> c.copy(vbanPort = int(raw ?: defaults.vbanPort, 1..65535))
                    "vban_stream" -> c.copy(vbanStream = stream(raw ?: defaults.vbanStream))
                    "film_latency_ms" ->
                        c.copy(filmLatencyMs = int(raw ?: defaults.filmLatencyMs, 0..MAX_LATENCY_MS))
                    "quiet_start" -> c.copy(quietStart = clock(raw ?: defaults.quietStart))
                    "quiet_end" -> c.copy(quietEnd = clock(raw ?: defaults.quietEnd))
                    "film_packages" -> c.copy(filmPackages = textList(raw ?: defaults.filmPackages, 256))
                    "music_packages" -> c.copy(musicPackages = textList(raw ?: defaults.musicPackages, 256))
                    "start_debounce_s" -> c.copy(startDebounceS = int(raw ?: defaults.startDebounceS, 0..600))
                    "stop_delay_s" -> c.copy(stopDelayS = int(raw ?: defaults.stopDelayS, 0..3600))
                    "tv_mute" -> c.copy(tvMute = bool(raw ?: defaults.tvMute))
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
        "film_player_id" to filmPlayerId,
        "music_player_id" to musicPlayerId,
        "surround_player_ids" to surroundPlayerIds,
        "surround_sync_adjust_ms" to surroundSyncAdjustMs,
        "vban_host" to vbanHost,
        "vban_port" to vbanPort,
        "vban_stream" to vbanStream,
        "film_latency_ms" to filmLatencyMs,
        "quiet_start" to quietStart,
        "quiet_end" to quietEnd,
        "film_packages" to filmPackages,
        "music_packages" to musicPackages,
        "start_debounce_s" to startDebounceS,
        "stop_delay_s" to stopDelayS,
        "tv_mute" to tvMute,
    )

    /** Keys that must be set before house mode can run; empty when ready. */
    fun missingForHouse(film: Boolean): List<String> = buildList {
        if (maUrl.isEmpty()) add("ma_url")
        if (maToken.isEmpty()) add("ma_token")
        if (sourceUri.isEmpty()) add("source_uri")
        if (vbanHost.isEmpty()) add("vban_host")
        if (film && filmPlayerId.isEmpty()) add("film_player_id")
        if (!film && musicPlayerId.isEmpty()) add("music_player_id")
    }

    /** Quiet start as minutes after midnight. */
    val quietStartMinute: Int get() = minuteOfDay(quietStart)

    /** Quiet end as minutes after midnight. */
    val quietEndMinute: Int get() = minuteOfDay(quietEnd)

    companion object {
        const val MAX_LATENCY_MS = 5000

        val KEYS: List<String> = Config().toMap(false).keys.toList()

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

        private fun textList(raw: Any, maxItem: Int): List<String> {
            require(raw is List<*>) { "expected a list of strings" }
            require(raw.size <= 64) { "at most 64 entries" }
            return raw.map {
                require(it is String && it.isNotBlank() && it.length <= maxItem) {
                    "expected non-empty strings"
                }
                it.trim()
            }
        }
    }
}
