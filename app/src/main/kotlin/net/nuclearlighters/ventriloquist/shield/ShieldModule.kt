package net.nuclearlighters.ventriloquist.shield

import net.nuclearlighters.ventriloquist.store.Ledger

/** The few platform audio controls the box-side module needs; faked in tests. */
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
 * Changes made to the box for house mode: its media output muted (the TV receives silence
 * while capture goes on at full level) and the surround output forced to PCM (an encoded
 * bitstream bypasses the mixer that capture records).
 *
 * Muting uses `ADJUST_MUTE` only. The volume index is never stepped: a box that controls the
 * TV's volume (by infrared or CEC) turns index changes into volume commands to the TV.
 *
 * Every change records the original in the [Ledger] (persisted) before it is made, and
 * [restore] puts all originals back. Recording keeps the first original, so repeated changes
 * still restore to the state before Ventriloquist touched anything. [restore] also puts back
 * the HDMI latency and the volume index that versions before 0.4 changed.
 */
class ShieldModule(private val audio: AudioControls, private val ledger: Ledger) {

    data class LatencyReading(val ms: Int?, val raw: String)

    val dirty: Boolean get() = ledger.dirty

    /** True while Ventriloquist holds the box's media output muted. */
    val boxMuted: Boolean get() = ledger.has(K_MUTED)

    fun originals(): Map<String, Any?> = ledger.snapshot()

    fun readLatency(): LatencyReading {
        val raw = audio.getParameters(PARAM)
        return LatencyReading(parseLatency(raw), raw)
    }

    /** Mutes the box's media output, or puts the original mute state back. Returns the state. */
    fun setBoxMuted(muted: Boolean): Boolean {
        if (muted) {
            if (!ledger.has(K_MUTED)) ledger.recordOriginal(K_MUTED, audio.musicMuted())
            if (!audio.musicMuted()) audio.setMusicMuted(true)
        } else {
            restoreMute()
        }
        return audio.musicMuted()
    }

    /**
     * While Ventriloquist holds the box muted, mutes it again if something else unmuted it (an
     * app or the system adjusting the media stream). Returns true when it had to.
     */
    fun reassertMute(): Boolean {
        if (!ledger.has(K_MUTED) || audio.musicMuted()) return false
        audio.setMusicMuted(true)
        return true
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
     * Puts every recorded original back: latency, surround, then the mute state and volume (the
     * TV gets its sound back last). An item that fails stays in the ledger for the next
     * attempt. Returns the failures, empty on success.
     */
    fun restore(): List<String> {
        val errors = mutableListOf<String>()
        attempt(errors, "latency") { restoreLatency() }
        attempt(errors, "surround") { restoreSurround() }
        attempt(errors, "mute") { restoreMute() }
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

    private fun restoreMute() {
        if (!ledger.has(K_MUTED)) return
        val original = ledger.get(K_MUTED) as? Boolean ?: false
        if (audio.musicMuted() != original) audio.setMusicMuted(original)
        ledger.clear(K_MUTED)
    }

    private fun restoreVolume() {
        if (!ledger.has(K_VOLUME)) return
        val original = (ledger.get(K_VOLUME) as? Number)?.toInt() ?: audio.musicVolumeMax()
        if (audio.musicVolume() != original) audio.setMusicVolume(original)
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
