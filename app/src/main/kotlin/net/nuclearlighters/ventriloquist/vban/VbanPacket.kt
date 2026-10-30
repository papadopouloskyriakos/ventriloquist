/*
 * Ported from VbanPacket.java in androidtv-vban-sender
 * (https://github.com/zewelor/androidtv-vban-sender), licensed under the Apache License 2.0;
 * see third-party/androidtv-vban-sender-LICENSE.
 * Changes: Kotlin; sample rate and channel count are parameters; explicit sample-rate index
 * table; header validation helpers for tests.
 */
package net.nuclearlighters.ventriloquist.vban

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Builds VBAN audio datagrams (sub-protocol 0, PCM 16-bit little-endian, interleaved).
 *
 * The 28-byte header: "VBAN", sample-rate index, frames minus one, channels minus one,
 * data type (1 = INT16) with codec PCM, 16-byte stream name (zero padded), 32-bit frame counter.
 * The returned buffer is reused on the next call to [encode].
 */
class VbanPacket(
    val stream: String,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val channels: Int = DEFAULT_CHANNELS,
) {
    /** Bytes per frame: one 16-bit sample per channel. */
    val frameBytes: Int = channels * BYTES_PER_SAMPLE

    private val packet: ByteBuffer = ByteBuffer
        .allocate(HEADER_BYTES + MAX_FRAMES * frameBytes)
        .order(ByteOrder.LITTLE_ENDIAN)

    init {
        require(stream.length in 1..STREAM_NAME_BYTES && stream.all { it.code in 32..126 }) {
            "Stream must contain 1 to $STREAM_NAME_BYTES printable ASCII characters"
        }
        require(channels in 1..MAX_CHANNELS) { "Channels must be in 1..$MAX_CHANNELS" }
        val rateIndex = sampleRateIndex(sampleRate)
        packet.put(
            byteArrayOf(
                'V'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'N'.code.toByte(),
                rateIndex.toByte(), 0, (channels - 1).toByte(), DATA_TYPE_INT16,
            ),
        )
        packet.put(stream.toByteArray(StandardCharsets.US_ASCII))
    }

    /**
     * Encodes [frames] frames of interleaved PCM starting at [offset] in [pcm], stamped with
     * [counter]. The returned buffer is valid until the next call.
     */
    fun encode(pcm: ByteArray, offset: Int, frames: Int, counter: Int): ByteBuffer {
        val bytes = frames * frameBytes
        require(frames in 1..MAX_FRAMES && offset >= 0 && offset <= pcm.size - bytes) {
            "Invalid PCM frame range"
        }
        packet.clear()
        packet.put(FRAMES_OFFSET, (frames - 1).toByte())
        packet.putInt(COUNTER_OFFSET, counter)
        packet.position(HEADER_BYTES)
        packet.put(pcm, offset, bytes)
        packet.flip()
        return packet
    }

    companion object {
        const val HEADER_BYTES = 28
        const val STREAM_NAME_BYTES = 16
        const val MAX_FRAMES = 256
        const val MAX_CHANNELS = 256
        const val BYTES_PER_SAMPLE = 2
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_CHANNELS = 2
        const val FRAMES_OFFSET = 5
        const val COUNTER_OFFSET = 24
        const val DATA_TYPE_INT16: Byte = 1

        /** VBAN sample-rate table; the index is what goes on the wire. */
        val SAMPLE_RATES: IntArray = intArrayOf(
            6_000, 12_000, 24_000, 48_000, 96_000, 192_000, 384_000,
            8_000, 16_000, 32_000, 64_000, 128_000, 256_000, 512_000,
            11_025, 22_050, 44_100, 88_200, 176_400, 352_800, 705_600,
        )

        fun sampleRateIndex(rate: Int): Int {
            val index = SAMPLE_RATES.indexOf(rate)
            require(index >= 0) { "Sample rate $rate is not a VBAN rate" }
            return index
        }
    }
}
