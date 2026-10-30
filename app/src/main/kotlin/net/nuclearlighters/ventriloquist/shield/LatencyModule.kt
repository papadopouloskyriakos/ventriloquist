package net.nuclearlighters.ventriloquist.shield

import net.nuclearlighters.ventriloquist.store.Ledger

/** The few platform audio controls the latency module needs; faked in tests. */
interface AudioControls {
    /** `AudioManager.getParameters`; may return an empty string when the HAL ignores the key. */
    fun getParameters(keys: String): String

    /** `AudioManager.setParameters`. */
    fun setParameters(keyValuePairs: String)

    /** Media (`STREAM_MUSIC`) volume index on the current output, which is HDMI on a TV box. */
    fun musicVolume(): Int

    fun musicVolumeMax(): Int

    /** `setStreamVolume(STREAM_MUSIC, index, 0)`. */
    fun setMusicVolume(index: Int)

    /** `isStreamMute(STREAM_MUSIC)`. */
    fun musicMuted(): Boolean

    /** `adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE or ADJUST_UNMUTE, 0)`. */
    fun setMusicMuted(muted: Boolean)

    /** `Settings.Global` encoded surround output, null when unset. */
    fun surroundSetting(): String?

    /** Writes the surround setting; null removes it. Needs WRITE_SECURE_SETTINGS. */
    fun setSurroundSetting(value: String?)
}

/**
 * Shield-side changes for house mode: the HDMI latency parameter that delays the picture, the
 * HDMI media volume that silences the TV's own speakers, and the surround output format.
 *
 * Every change records the original in the [Ledger] (persisted) before it is made, and
 * [restore] puts all originals back. Recording keeps the first original, so repeated changes
 * still restore to the state before Ventriloquist touched anything.
 */
class LatencyModule(private val audio: AudioControls, private val ledger: Ledger) {

    data class LatencyReading(val ms: Int?, val raw: String)

    /** Outcome of a TV mute or unmute: the state afterwards and every call made, in order. */
    data class MuteResult(
        val on: Boolean,
        val volume: Int,
        val muted: Boolean,
        val attempts: List<String>,
    ) {
        /** True when the TV receives silence (index 0 or stream muted). */
        val silenced: Boolean get() = volume == 0 || muted

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "on" to on,
            "hdmi_volume" to volume,
            "stream_muted" to muted,
            "silenced" to silenced,
            "attempts" to attempts,
        )
    }

    val dirty: Boolean get() = ledger.dirty

    val tvMuted: Boolean get() = ledger.has(K_VOLUME) || ledger.has(K_MUTED)

    /** The last mute or unmute, for the status page. */
    @Volatile
    var lastMute: MuteResult? = null
        private set

    fun originals(): Map<String, Any?> = ledger.snapshot()

    fun readLatency(): LatencyReading {
        val raw = audio.getParameters(PARAM)
        return LatencyReading(parseLatency(raw), raw)
    }

    /** Sets the HDMI latency to [ms] and returns what the HAL reports afterwards. */
    fun setLatency(ms: Int): LatencyReading {
        require(ms in 0..MAX_LATENCY_MS) { "latency must be in 0..$MAX_LATENCY_MS ms" }
        if (!ledger.has(K_LATENCY)) ledger.recordOriginal(K_LATENCY, readLatency().ms?.toLong())
        audio.setParameters("$PARAM=$ms")
        return readLatency()
    }

    /**
     * Silences the TV (HDMI media output) or puts its original volume and mute state back.
     *
     * Muting first sets the media index to 0 with `setStreamVolume`. Some boxes treat HDMI as a
     * full-volume output and keep the index at its maximum; then it mutes the stream with
     * `adjustStreamVolume(ADJUST_MUTE)`. Both originals are recorded before anything changes,
     * and every call with its before and after values is returned (and kept for the status page).
     */
    fun setTvMuted(muted: Boolean): MuteResult {
        val attempts = mutableListOf<String>()
        if (muted) {
            if (!ledger.has(K_VOLUME)) ledger.recordOriginal(K_VOLUME, audio.musicVolume().toLong())
            if (!ledger.has(K_MUTED)) ledger.recordOriginal(K_MUTED, audio.musicMuted())
            val before = audio.musicVolume()
            logCall(attempts, "setStreamVolume(STREAM_MUSIC, 0, 0): index $before") {
                audio.setMusicVolume(0)
                "-> ${audio.musicVolume()}, muted ${audio.musicMuted()}"
            }
            if (audio.musicVolume() > 0 && !audio.musicMuted()) {
                logCall(attempts, "adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE, 0): muted false") {
                    audio.setMusicMuted(true)
                    "-> ${audio.musicMuted()}, index ${audio.musicVolume()}"
                }
            }
        } else {
            restoreMute(attempts)
            restoreVolume(attempts)
        }
        val result = MuteResult(muted, audio.musicVolume(), audio.musicMuted(), attempts)
        lastMute = result
        return result
    }

    /** Forces PCM surround output, or restores the original; returns the setting afterwards. */
    fun setSurroundPcm(pcm: Boolean): String? {
        if (pcm) {
            if (!ledger.has(K_SURROUND)) ledger.recordOriginal(K_SURROUND, audio.surroundSetting())
            audio.setSurroundSetting(SURROUND_NEVER)
        } else {
            restoreSurround()
        }
        return audio.surroundSetting()
    }

    /**
     * Puts every recorded original back: latency, then surround, then volume (the TV speakers
     * come back last, when the picture is no longer delayed). An item that fails stays in the
     * ledger for the next attempt. Returns the failures, empty on success.
     */
    fun restore(): List<String> {
        val errors = mutableListOf<String>()
        attempt(errors, "latency") { restoreLatency() }
        attempt(errors, "surround") { restoreSurround() }
        attempt(errors, "mute") { restoreMute(null) }
        attempt(errors, "volume") { restoreVolume(null) }
        return errors
    }

    private fun restoreLatency() {
        if (!ledger.has(K_LATENCY)) return
        // An unreadable original means the HAL did not report one; its default is 0 ms.
        val original = (ledger.get(K_LATENCY) as? Number)?.toInt() ?: 0
        audio.setParameters("$PARAM=$original")
        ledger.clear(K_LATENCY)
    }

    private fun restoreSurround() {
        if (!ledger.has(K_SURROUND)) return
        audio.setSurroundSetting(ledger.get(K_SURROUND) as String?)
        ledger.clear(K_SURROUND)
    }

    private fun restoreMute(log: MutableList<String>?) {
        if (!ledger.has(K_MUTED)) return
        val original = ledger.get(K_MUTED) as? Boolean ?: false
        val before = audio.musicMuted()
        if (before != original) {
            audio.setMusicMuted(original)
            log?.add(
                "adjustStreamVolume(STREAM_MUSIC, ${if (original) "ADJUST_MUTE" else "ADJUST_UNMUTE"}, 0): " +
                    "muted $before -> ${audio.musicMuted()}",
            )
        }
        ledger.clear(K_MUTED)
    }

    private fun restoreVolume(log: MutableList<String>?) {
        if (!ledger.has(K_VOLUME)) return
        val original = (ledger.get(K_VOLUME) as? Number)?.toInt() ?: audio.musicVolumeMax()
        val before = audio.musicVolume()
        audio.setMusicVolume(original)
        log?.add("setStreamVolume(STREAM_MUSIC, $original, 0): index $before -> ${audio.musicVolume()}")
        ledger.clear(K_VOLUME)
    }

    /** Runs one platform call for the mute log; an exception is logged, not thrown. */
    private inline fun logCall(log: MutableList<String>, call: String, block: () -> String) {
        try {
            log.add("$call ${block()}")
        } catch (e: Exception) {
            log.add("$call threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private inline fun attempt(errors: MutableList<String>, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            errors.add("$what: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        const val PARAM = "nv_param_audio_hdmi_hw_latency"
        const val SURROUND_KEY = "encoded_surround_output"

        /** `Settings.Global.ENCODED_SURROUND_OUTPUT_NEVER`: always PCM through the mixer. */
        const val SURROUND_NEVER = "1"
        const val MAX_LATENCY_MS = 5000

        const val K_LATENCY = "hdmi_latency_ms"
        const val K_VOLUME = "hdmi_music_volume"
        const val K_MUTED = "hdmi_music_muted"
        const val K_SURROUND = "encoded_surround_output"

        /** Reads `PARAM=<ms>` out of a `getParameters` reply such as `a=1;PARAM=120`. */
        fun parseLatency(raw: String?): Int? {
            if (raw.isNullOrEmpty()) return null
            for (part in raw.split(';')) {
                val eq = part.indexOf('=')
                if (eq > 0 && part.substring(0, eq).trim() == PARAM) {
                    return part.substring(eq + 1).trim().toIntOrNull()
                }
            }
            return null
        }
    }
}
