package net.nuclearlighters.ventriloquist.shield

import net.nuclearlighters.ventriloquist.store.KeyValueStore
import net.nuclearlighters.ventriloquist.store.Ledger
import net.nuclearlighters.ventriloquist.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LatencyModuleTest {

    /** Fake platform; [beforeChange] runs before every write so a test can inspect the store. */
    private class FakeAudio(
        var latency: Int = 0,
        var volume: Int = 15,
        var surround: String? = null,
        var reportLatency: Boolean = true,
    ) : AudioControls {
        var beforeChange: (String) -> Unit = {}
        var failVolume = false

        override fun getParameters(keys: String): String =
            if (reportLatency) "${LatencyModule.PARAM}=$latency" else ""

        override fun setParameters(keyValuePairs: String) {
            beforeChange("latency")
            latency = keyValuePairs.substringAfter('=').toInt()
        }

        override fun musicVolume(): Int = volume
        override fun musicVolumeMax(): Int = 15
        override fun setMusicVolume(index: Int) {
            beforeChange("volume")
            if (failVolume) throw SecurityException("denied")
            volume = index
        }

        override fun surroundSetting(): String? = surround
        override fun setSurroundSetting(value: String?) {
            beforeChange("surround")
            surround = value
        }
    }

    private fun module(store: KeyValueStore, audio: AudioControls) =
        LatencyModule(audio, Ledger(store, "shield"))

    @Test
    fun originalIsPersistedBeforeEachChange() {
        val store = MemoryStore()
        val audio = FakeAudio(latency = 40, volume = 11, surround = "0")
        val seen = mutableListOf<String>()
        audio.beforeChange = { what -> seen.add("$what:${store.get("shield")}") }
        val m = module(store, audio)
        m.setLatency(600)
        m.setTvMuted(true)
        m.setSurroundPcm(true)
        assertTrue(seen[0], seen[0].contains("\"hdmi_latency_ms\":40"))
        assertTrue(seen[1], seen[1].contains("\"hdmi_music_volume\":11"))
        assertTrue(seen[2], seen[2].contains("\"encoded_surround_output\":\"0\""))
    }

    @Test
    fun setLatencyReadsBack() {
        val audio = FakeAudio()
        val r = module(MemoryStore(), audio).setLatency(250)
        assertEquals(250, r.ms)
        assertEquals("${LatencyModule.PARAM}=250", r.raw)
    }

    @Test
    fun restoreAfterACrashUsesThePersistedOriginals() {
        val store = MemoryStore()
        val audio = FakeAudio(latency = 0, volume = 12, surround = null)
        val first = module(store, audio)
        first.setLatency(700)
        first.setTvMuted(true)
        first.setSurroundPcm(true)
        assertEquals(0, audio.volume)
        assertEquals("1", audio.surround)

        // Process dies here; a new instance reads the same store.
        val second = module(store, audio)
        assertTrue(second.dirty)
        assertEquals(emptyList<String>(), second.restore())
        assertFalse(second.dirty)
        assertEquals(0, audio.latency)
        assertEquals(12, audio.volume)
        assertNull(audio.surround)
        assertNull(store.get("shield"))
    }

    @Test
    fun theFirstOriginalWins() {
        val audio = FakeAudio(latency = 30, volume = 9)
        val m = module(MemoryStore(), audio)
        m.setLatency(100)
        m.setLatency(200)
        m.setTvMuted(true)
        m.setTvMuted(true)
        m.restore()
        assertEquals(30, audio.latency)
        assertEquals(9, audio.volume)
    }

    @Test
    fun unmuteRestoresOnlyTheVolume() {
        val audio = FakeAudio(latency = 0, volume = 7)
        val m = module(MemoryStore(), audio)
        m.setLatency(500)
        m.setTvMuted(true)
        assertEquals(7, m.setTvMuted(false))
        assertFalse(m.tvMuted)
        assertTrue(m.dirty)
        assertEquals(500, audio.latency)
    }

    @Test
    fun aFailedRestoreStaysDirtyAndIsRetried() {
        val store = MemoryStore()
        val audio = FakeAudio(volume = 15)
        val m = module(store, audio)
        m.setLatency(300)
        m.setTvMuted(true)
        audio.failVolume = true
        val errors = m.restore()
        assertEquals(1, errors.size)
        assertTrue(errors[0].startsWith("volume"))
        assertTrue(m.dirty)
        assertEquals(0, audio.latency)
        audio.failVolume = false
        assertEquals(emptyList<String>(), module(store, audio).restore())
        assertEquals(15, audio.volume)
    }

    @Test
    fun unreadableOriginalLatencyRestoresToZero() {
        val audio = FakeAudio(latency = 90, reportLatency = false)
        val m = module(MemoryStore(), audio)
        m.setLatency(400)
        m.restore()
        assertEquals(0, audio.latency)
    }

    @Test
    fun nothingChangesWhenTheOriginalCannotBePersisted() {
        val broken = object : KeyValueStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String?) = throw IOException("disk full")
        }
        val audio = FakeAudio(latency = 0, volume = 15)
        val m = module(broken, audio)
        assertThrows(IOException::class.java) { m.setTvMuted(true) }
        assertEquals(15, audio.volume)
        assertFalse(m.dirty)
    }

    @Test
    fun rejectsOutOfRangeLatency() {
        val m = module(MemoryStore(), FakeAudio())
        assertThrows(IllegalArgumentException::class.java) { m.setLatency(-1) }
        assertThrows(IllegalArgumentException::class.java) { m.setLatency(5001) }
        assertFalse(m.dirty)
    }

    @Test
    fun parsesGetParametersReplies() {
        assertEquals(120, LatencyModule.parseLatency("${LatencyModule.PARAM}=120"))
        assertEquals(5, LatencyModule.parseLatency("a=1;${LatencyModule.PARAM}= 5 ;b=2"))
        assertNull(LatencyModule.parseLatency(""))
        assertNull(LatencyModule.parseLatency(null))
        assertNull(LatencyModule.parseLatency("${LatencyModule.PARAM}="))
        assertNull(LatencyModule.parseLatency("other=3"))
    }
}
