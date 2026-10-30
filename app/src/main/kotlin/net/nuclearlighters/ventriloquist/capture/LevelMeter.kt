package net.nuclearlighters.ventriloquist.capture

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/** Per-channel RMS and peak of interleaved 16-bit little-endian PCM, in dBFS. */
class LevelMeter(val channels: Int = 2) {

    data class Levels(
        val frames: Long,
        val rmsDbfs: List<Double>,
        val peakDbfs: List<Double>,
    ) {
        /** True when every sample was zero (nothing captured, or a muted capture mix). */
        val silent: Boolean get() = peakDbfs.all { it <= FLOOR_DBFS }

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "frames" to frames,
            "seconds" to round(frames / 48.0) / 1000.0,
            "rms_dbfs" to rmsDbfs,
            "peak_dbfs" to peakDbfs,
            "silent" to silent,
        )
    }

    private val sumSquares = DoubleArray(channels)
    private val peak = IntArray(channels)
    private var frames = 0L
    private var channel = 0

    @Synchronized
    fun add(pcm: ByteArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length - (length and 1)
        while (i < end) {
            val sample = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            sumSquares[channel] += sample.toDouble() * sample
            val a = abs(sample)
            if (a > peak[channel]) peak[channel] = a
            channel++
            if (channel == channels) {
                channel = 0
                frames++
            }
            i += 2
        }
    }

    @Synchronized
    fun result(): Levels {
        val rms = List(channels) {
            if (frames == 0L) FLOOR_DBFS else dbfs(sqrt(sumSquares[it] / frames))
        }
        val peaks = List(channels) { dbfs(peak[it].toDouble()) }
        return Levels(frames, rms, peaks)
    }

    @Synchronized
    fun reset() {
        sumSquares.fill(0.0)
        peak.fill(0)
        frames = 0
        channel = 0
    }

    companion object {
        const val FLOOR_DBFS = -120.0

        /** 20 log10(x / 32768), floored at [FLOOR_DBFS], rounded to 0.1 dB. */
        fun dbfs(amplitude: Double): Double {
            if (amplitude <= 0.0) return FLOOR_DBFS
            val db = max(FLOOR_DBFS, 20.0 * log10(amplitude / 32768.0))
            return round(db * 10.0) / 10.0
        }
    }
}
