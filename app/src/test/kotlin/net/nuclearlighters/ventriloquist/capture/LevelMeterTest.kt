package net.nuclearlighters.ventriloquist.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelMeterTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[2 * i] = (s and 0xff).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    @Test
    fun silenceIsAtTheFloor() {
        val m = LevelMeter()
        m.add(ByteArray(400), 0, 400)
        val r = m.result()
        assertEquals(100L, r.frames)
        assertEquals(listOf(LevelMeter.FLOOR_DBFS, LevelMeter.FLOOR_DBFS), r.rmsDbfs)
        assertTrue(r.silent)
    }

    @Test
    fun fullScaleSquareWaveIsZeroDbfs() {
        val m = LevelMeter()
        val samples = IntArray(96) { if ((it / 2) % 2 == 0) 32767 else -32768 }
        m.add(pcm(*samples), 0, samples.size * 2)
        val r = m.result()
        assertEquals(0.0, r.peakDbfs[0], 0.01)
        assertEquals(0.0, r.rmsDbfs[1], 0.01)
        assertFalse(r.silent)
    }

    @Test
    fun channelsAreMeasuredSeparately() {
        val m = LevelMeter()
        // left at half scale, right silent
        val samples = IntArray(200) { if (it % 2 == 0) 16384 else 0 }
        m.add(pcm(*samples), 0, samples.size * 2)
        val r = m.result()
        assertEquals(-6.0, r.peakDbfs[0], 0.05)
        assertEquals(-6.0, r.rmsDbfs[0], 0.05)
        assertEquals(LevelMeter.FLOOR_DBFS, r.peakDbfs[1], 0.0)
    }

    @Test
    fun framesSpanningCallsKeepTheirChannel() {
        val m = LevelMeter()
        val bytes = pcm(1000, 0, 1000, 0)
        m.add(bytes, 0, 2) // left only
        m.add(bytes, 2, 6) // right, left, right
        val r = m.result()
        assertEquals(2L, r.frames)
        assertEquals(LevelMeter.FLOOR_DBFS, r.peakDbfs[1], 0.0)
    }
}
