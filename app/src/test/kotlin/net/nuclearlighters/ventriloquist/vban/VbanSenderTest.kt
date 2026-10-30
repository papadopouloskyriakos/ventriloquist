package net.nuclearlighters.ventriloquist.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VbanSenderTest {

    private class FakeClock : PacingClock {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        override fun nanoTime() = now
        override fun sleepNanos(nanos: Long) {
            sleeps.add(nanos)
            now += nanos
        }
    }

    private class Sink(val clock: FakeClock) : DatagramSink {
        val times = mutableListOf<Long>()
        val packets = mutableListOf<ByteArray>()
        var fail = false
        override fun send(packet: ByteBuffer) {
            if (fail) throw IOException("network down")
            val bytes = ByteArray(packet.remaining())
            packet.duplicate().get(bytes)
            packets.add(bytes)
            times.add(clock.now)
        }
    }

    private val ms = 1_000_000L

    private fun pcm(frames: Int): ByteArray = ByteArray(frames * 4) { (it / 4).toByte() }

    private fun frames(packet: ByteArray) = (packet[VbanPacket.FRAMES_OFFSET].toInt() and 0xff) + 1

    private fun counter(packet: ByteArray) =
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(VbanPacket.COUNTER_OFFSET)

    @Test
    fun splitsAReadIntoEvenPacketsOfAtMost256Frames() {
        val clock = FakeClock()
        val sink = Sink(clock)
        val sender = VbanSender("TV", sink, clock)
        sender.send(pcm(960), 960 * 4)
        assertEquals(listOf(240, 240, 240, 240), sink.packets.map { frames(it) })
        sender.send(pcm(1000), 1000 * 4)
        assertEquals(listOf(250, 250, 250, 250), sink.packets.drop(4).map { frames(it) })
        sender.send(pcm(257), 257 * 4)
        assertEquals(listOf(129, 128), sink.packets.drop(8).map { frames(it) })
        assertEquals((0 until 10).toList(), sink.packets.map { counter(it) })
        assertEquals(10L, sender.packetsSent)
        assertEquals(2217L, sender.framesSent)
    }

    @Test
    fun carriesThePcmInOrder() {
        val clock = FakeClock()
        val sink = Sink(clock)
        val data = pcm(512)
        VbanSender("TV", sink, clock).send(data, data.size)
        val payload = sink.packets.flatMap { it.drop(VbanPacket.HEADER_BYTES) }.toByteArray()
        assertTrue(data.contentEquals(payload))
        assertEquals(VbanPacket.HEADER_BYTES + 256 * 4, sink.packets[0].size)
    }

    @Test
    fun pacesPacketsBySourceClockInsteadOfBursting() {
        val clock = FakeClock()
        val sink = Sink(clock)
        val sender = VbanSender("TV", sink, clock)
        sender.send(pcm(960), 960 * 4) // first read arrives at t = 0
        assertEquals(listOf(0L, 5 * ms, 10 * ms, 15 * ms), sink.times)
        clock.now = 20 * ms // the next read returns when its 20 ms are captured
        sender.send(pcm(960), 960 * 4)
        assertEquals(listOf(20 * ms, 25 * ms, 30 * ms, 35 * ms), sink.times.drop(4))
        assertTrue(clock.sleeps.all { it <= 5 * ms })
        assertEquals(0L, sender.resyncs)
    }

    @Test
    fun catchesUpAfterASmallDelayWithoutResync() {
        val clock = FakeClock()
        val sink = Sink(clock)
        val sender = VbanSender("TV", sink, clock)
        sender.send(pcm(960), 960 * 4)
        clock.now = 50 * ms // 30 ms late: the four packets were due at 20 to 35 ms
        sender.send(pcm(960), 960 * 4)
        assertEquals(listOf(50 * ms, 50 * ms, 50 * ms, 50 * ms), sink.times.drop(4))
        clock.now = 60 * ms // back on schedule: due at 40 ms, so sent at once and paced on
        sender.send(pcm(960), 960 * 4)
        assertEquals(0L, sender.resyncs)
    }

    @Test
    fun reAnchorsAfterAStallInsteadOfBursting() {
        val clock = FakeClock()
        val sink = Sink(clock)
        val sender = VbanSender("TV", sink, clock)
        sender.send(pcm(960), 960 * 4)
        clock.now = 500 * ms // capture stalled for half a second
        sender.send(pcm(960), 960 * 4)
        assertEquals(1L, sender.resyncs)
        assertEquals(listOf(500 * ms, 505 * ms, 510 * ms, 515 * ms), sink.times.drop(4))
    }

    @Test
    fun countsSendErrorsWithoutThrowing() {
        val clock = FakeClock()
        val sink = Sink(clock)
        val sender = VbanSender("TV", sink, clock)
        sink.fail = true
        sender.send(pcm(512), 512 * 4)
        assertEquals(2L, sender.sendErrors)
        assertEquals(0L, sender.packetsSent)
        assertEquals("network down", sender.lastSendError)
        sink.fail = false
        sender.send(pcm(256), 256 * 4)
        assertEquals(2, counter(sink.packets.single())) // the counter kept running
    }

    @Test
    fun ignoresAPartialTrailingFrame() {
        val clock = FakeClock()
        val sink = Sink(clock)
        VbanSender("TV", sink, clock).send(ByteArray(10), 10)
        assertEquals(1, sink.packets.size)
        assertEquals(2, frames(sink.packets[0]))
        VbanSender("TV", sink, clock).send(ByteArray(3), 3)
        assertEquals(1, sink.packets.size)
    }
}
