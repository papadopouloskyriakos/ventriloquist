/*
 * Golden values ported from tests/ProtocolCheck.java in androidtv-vban-sender
 * (https://github.com/zewelor/androidtv-vban-sender), Apache License 2.0;
 * see third-party/androidtv-vban-sender-LICENSE. Rewritten as JUnit tests.
 */
package net.nuclearlighters.ventriloquist.vban

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VbanPacketTest {

    @Test
    fun headerMatchesGoldenBytesFor48kStereo() {
        val pcm = ByteArray(1024)
        pcm[0] = 0x34
        pcm[1] = 0x12
        pcm[2] = 0xcc.toByte()
        pcm[3] = 0xed.toByte()

        val wire = VbanPacket("VBANTX").encode(pcm, 0, 256, -1)

        val golden = byteArrayOf(
            0x56, 0x42, 0x41, 0x4e, 0x03, 0xff.toByte(), 0x01, 0x01,
            0x56, 0x42, 0x41, 0x4e, 0x54, 0x58, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
        )
        val header = ByteArray(VbanPacket.HEADER_BYTES)
        wire.get(header)
        assertArrayEquals(golden, header)
        assertEquals(VbanPacket.HEADER_BYTES + 1024, wire.limit())
        assertEquals(0x34.toByte(), wire.get())
        assertEquals(0x12.toByte(), wire.get())
        assertEquals(0xcc.toByte(), wire.get())
        assertEquals(0xed.toByte(), wire.get())
    }

    @Test
    fun counterIsLittleEndianUnsigned() {
        val wire = VbanPacket("Shield TV").encode(ByteArray(4), 0, 1, Int.MIN_VALUE)
        assertEquals(0, wire.get(24).toInt())
        assertEquals(0x80.toByte(), wire.get(27))
        assertEquals(0x00.toByte(), wire.get(VbanPacket.FRAMES_OFFSET))
    }

    @Test
    fun streamNameIsZeroPaddedTo16Bytes() {
        val wire = VbanPacket("Shield TV").encode(ByteArray(4), 0, 1, 0)
        val name = ByteArray(16)
        wire.position(8)
        wire.get(name)
        assertArrayEquals("Shield TV".toByteArray() + ByteArray(7), name)
    }

    @Test
    fun sampleRateIndexFollowsTheVbanTable() {
        assertEquals(3, VbanPacket.sampleRateIndex(48_000))
        assertEquals(16, VbanPacket.sampleRateIndex(44_100))
        val wire = VbanPacket("x", sampleRate = 44_100, channels = 1).encode(ByteArray(2), 0, 1, 0)
        assertEquals(16.toByte(), wire.get(4))
        assertEquals(0.toByte(), wire.get(6))
    }

    @Test
    fun rejectsBadInput() {
        assertThrows(IllegalArgumentException::class.java) { VbanPacket("") }
        assertThrows(IllegalArgumentException::class.java) { VbanPacket("seventeen chars!!") }
        assertThrows(IllegalArgumentException::class.java) { VbanPacket("bad\u0000name") }
        assertThrows(IllegalArgumentException::class.java) { VbanPacket("x", sampleRate = 47_000) }
        val packet = VbanPacket("x")
        assertThrows(IllegalArgumentException::class.java) { packet.encode(ByteArray(4), 0, 2, 0) }
        assertThrows(IllegalArgumentException::class.java) { packet.encode(ByteArray(4), 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { packet.encode(ByteArray(2048), 0, 257, 0) }
    }
}
