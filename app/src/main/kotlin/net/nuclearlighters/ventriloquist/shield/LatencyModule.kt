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

    fun setMusicVolume(index: Int)

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

    val dirty: Boolean get() = ledger.dirty

    val tvMuted: Boolean get() = ledger.has(K_VOLUME)

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

    /** Mutes (volume 0) or restores the HDMI media volume; returns the volume afterwards. */
    fun setTvMuted(muted: Boolean): Int {
        if (muted) {
            if (!ledger.has(K_VOLUME)) ledger.recordOriginal(K_VOLUME, audio.musicVolume().toLong())
            audio.setMusicVolume(0)
        } else {
            restoreVolume()
        }
        return audio.musicVolume()
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
        attempt(errors, "volume") { restoreVolume() }
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

    private fun restoreVolume() {
        if (!ledger.has(K_VOLUME)) return
        val original = (ledger.get(K_VOLUME) as? Number)?.toInt() ?: audio.musicVolumeMax()
        audio.setMusicVolume(original)
        ledger.clear(K_VOLUME)
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
