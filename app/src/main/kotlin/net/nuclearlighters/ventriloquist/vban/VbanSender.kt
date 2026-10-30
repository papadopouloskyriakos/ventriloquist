package net.nuclearlighters.ventriloquist.vban

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/** Monotonic time and sleeping, injectable for tests. */
interface PacingClock {
    fun nanoTime(): Long
    fun sleepNanos(nanos: Long)
}

object SystemPacingClock : PacingClock {
    override fun nanoTime(): Long = System.nanoTime()

    override fun sleepNanos(nanos: Long) {
        if (nanos > 0) Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
    }
}

/** Where encoded datagrams go. */
fun interface DatagramSink {
    @Throws(IOException::class)
    fun send(packet: ByteBuffer)
}

/** One UDP socket to one destination, resolved once. Construct it off the main thread. */
class UdpSink(host: String, port: Int) : DatagramSink, Closeable {
    private val socket = DatagramSocket()
    private val datagram = DatagramPacket(ByteArray(0), 0, InetSocketAddress(InetAddress.getByName(host), port))

    override fun send(packet: ByteBuffer) {
        datagram.setData(packet.array(), packet.arrayOffset() + packet.position(), packet.remaining())
        socket.send(datagram)
    }

    override fun close() = socket.close()
}

/**
 * Turns captured PCM into VBAN datagrams paced by the source clock.
 *
 * Each capture read becomes a bounded run of packets (at most [VbanPacket.MAX_FRAMES] frames
 * each, split evenly). Packet k of the stream is due at anchor + frames before it / sample
 * rate, where the anchor is the arrival of the first read: the sender sleeps until a packet is
 * due instead of bursting it, and never queues anything. When it falls more than [maxLagNanos]
 * behind (a stall) or would wait more than [maxLeadNanos] (a clock jump), it re-anchors at the
 * present instead of catching up or waiting. Send errors are counted, never thrown, so a
 * network fault cannot stop capture.
 *
 * [send] is called from the capture thread only; the counters may be read from any thread.
 */
class VbanSender(
    stream: String,
    private val sink: DatagramSink,
    private val clock: PacingClock = SystemPacingClock,
    val sampleRate: Int = VbanPacket.DEFAULT_SAMPLE_RATE,
    channels: Int = VbanPacket.DEFAULT_CHANNELS,
    private val maxLagNanos: Long = 100_000_000L,
    private val maxLeadNanos: Long = 100_000_000L,
) {
    private val packet = VbanPacket(stream, sampleRate, channels)
    private val frameBytes = packet.frameBytes

    @Volatile var packetsSent = 0L
        private set

    @Volatile var sendErrors = 0L
        private set

    @Volatile var resyncs = 0L
        private set

    @Volatile var framesSent = 0L
        private set

    @Volatile var lastSendError: String? = null
        private set

    private var counter = 0
    private var anchored = false
    private var anchorNanos = 0L
    private var anchorFrames = 0L

    /** Sends the whole frames among the first [length] bytes of [pcm]. */
    fun send(pcm: ByteArray, length: Int) {
        val frames = length / frameBytes
        if (frames == 0) return
        val packets = (frames + VbanPacket.MAX_FRAMES - 1) / VbanPacket.MAX_FRAMES
        val base = frames / packets
        val extra = frames % packets
        var offset = 0
        for (k in 0 until packets) {
            val n = base + if (k < extra) 1 else 0
            pace()
            val datagram = packet.encode(pcm, offset, n, counter)
            try {
                sink.send(datagram)
                packetsSent++
            } catch (e: IOException) {
                sendErrors++
                lastSendError = e.message ?: e.javaClass.simpleName
            }
            counter++
            framesSent += n
            offset += n * frameBytes
        }
    }

    /** Forgets the timeline; the next packet re-anchors (after a pause in capture). */
    fun reset() {
        anchored = false
    }

    private fun pace() {
        val now = clock.nanoTime()
        if (!anchored) {
            anchor(now)
            return
        }
        val elapsedFrames = framesSent - anchorFrames
        val due = anchorNanos + elapsedFrames * 1_000_000_000L / sampleRate
        val late = now - due
        if (late > maxLagNanos || -late > maxLeadNanos) {
            resyncs++
            anchor(now)
            return
        }
        if (late < 0) clock.sleepNanos(-late)
        // Move the anchor forward along the same timeline now and then, so the arithmetic
        // above never overflows however long a session runs.
        if (elapsedFrames > REANCHOR_FRAMES) {
            anchorNanos = due
            anchorFrames = framesSent
        }
    }

    private fun anchor(now: Long) {
        anchorNanos = now
        anchorFrames = framesSent
        anchored = true
    }

    companion object {
        private const val REANCHOR_FRAMES = 48_000L * 3600
    }
}
