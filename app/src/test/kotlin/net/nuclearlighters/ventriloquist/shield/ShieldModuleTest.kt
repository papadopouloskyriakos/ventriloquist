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

class ShieldModuleTest {

    /** Fake platform; [beforeChange] runs before every write so a test can inspect the store. */
    private class FakeAudio(
        var latency: Int = 0,
        var volume: Int = 15,
        var surround: String? = null,
        var muted: Boolean = false,
    ) : AudioControls {
        var beforeChange: (String) -> Unit = {}
        var failMute = false
        val calls = mutableListOf<String>()

        override fun getParameters(keys: String): String = "${ShieldModule.PARAM}=$latency"

        override fun setParameters(keyValuePairs: String) {
            beforeChange("latency")
            calls.add("setParameters $keyValuePairs")
            latency = keyValuePairs.substringAfter('=').toInt()
        }

        override fun musicVolume(): Int = volume
        override fun musicVolumeMax(): Int = 15
        override fun setMusicVolume(index: Int) {
            beforeChange("volume")
            calls.add("setStreamVolume $index")
            volume = index
        }

        override fun musicMuted(): Boolean = muted
        override fun setMusicMuted(muted: Boolean) {
            beforeChange("mute")
            if (failMute) throw SecurityException("denied")
            calls.add("mute $muted")
            this.muted = muted
        }

        override fun surroundSetting(): String? = surround
        override fun setSurroundSetting(value: String?) {
            beforeChange("surround")
            calls.add("surround $value")
            surround = value
        }
    }

    private fun module(store: KeyValueStore, audio: AudioControls) =
        ShieldModule(audio, Ledger(store, "shield"))

    @Test
    fun originalIsPersistedBeforeEachChange() {
        val store = MemoryStore()
        val audio = FakeAudio(surround = "0")
        val seen = mutableListOf<String>()
        audio.beforeChange = { what -> seen.add("$what:${store.get("shield")}") }
        val m = module(store, audio)
        m.setBoxMuted(true)
        m.setSurroundPcm(true)
        assertTrue(seen[0], seen[0].contains("\"hdmi_music_muted\":false"))
        assertTrue(seen[1], seen[1].contains("\"encoded_surround_output\":\"0\""))
    }

    @Test
    fun mutingNeverStepsTheVolume() {
        val audio = FakeAudio(volume = 15)
        val m = module(MemoryStore(), audio)
        assertTrue(m.setBoxMuted(true))
        assertTrue(m.boxMuted)
        assertFalse(m.setBoxMuted(false))
        assertFalse(m.boxMuted)
        assertEquals(listOf("mute true", "mute false"), audio.calls)
        assertEquals(15, audio.volume)
    }

    @Test
    fun aMuteUndoneByOthersIsReassertedOnlyWhileHeld() {
        val audio = FakeAudio()
        val m = module(MemoryStore(), audio)
        assertFalse(m.reassertMute()) // not held: nothing to do
        assertFalse(audio.muted)
        m.setBoxMuted(true)
        assertFalse(m.reassertMute()) // still muted
        audio.muted = false // someone else unmuted it
        assertTrue(m.reassertMute())
        assertTrue(audio.muted)
        m.restore()
        audio.muted = true
        audio.muted = false
        assertFalse(m.reassertMute()) // released: a later unmute is not ours to undo
        assertFalse(audio.muted)
    }

    @Test
    fun restoreAfterACrashUsesThePersistedOriginals() {
        val store = MemoryStore()
        val audio = FakeAudio(surround = "0")
        val first = module(store, audio)
        first.setBoxMuted(true)
        first.setSurroundPcm(true)
        val second = module(store, audio) // the process died, a new one starts
        assertTrue(second.dirty)
        assertEquals(emptyList<String>(), second.restore())
        assertFalse(audio.muted)
        assertEquals("0", audio.surround)
        assertFalse(second.dirty)
    }

    @Test
    fun theFirstOriginalWins() {
        val audio = FakeAudio(surround = "2")
        val m = module(MemoryStore(), audio)
        m.setSurroundPcm(true)
        m.setSurroundPcm(true)
        m.restore()
        assertEquals("2", audio.surround)
    }

    @Test
    fun anUnsetSurroundIsRemovedAgain() {
        val audio = FakeAudio(surround = null)
        val m = module(MemoryStore(), audio)
        m.setSurroundPcm(true)
        assertEquals(ShieldModule.SURROUND_NEVER, audio.surround)
        m.restore()
        assertNull(audio.surround)
    }

    @Test
    fun aFailedRestoreStaysDirtyAndIsRetried() {
        val audio = FakeAudio()
        val m = module(MemoryStore(), audio)
        m.setBoxMuted(true)
        audio.failMute = true
        val errors = m.restore()
        assertEquals(1, errors.size)
        assertTrue(errors[0], errors[0].startsWith("mute: SecurityException"))
        assertTrue(m.dirty)
        audio.failMute = false
        assertEquals(emptyList<String>(), m.restore())
        assertFalse(audio.muted)
        assertFalse(m.dirty)
    }

    @Test
    fun nothingChangesWhenTheOriginalCannotBePersisted() {
        val broken = object : KeyValueStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String?) = throw IOException("disk full")
        }
        val audio = FakeAudio()
        val m = module(broken, audio)
        assertThrows(IOException::class.java) { m.setBoxMuted(true) }
        assertFalse(audio.muted)
        assertFalse(m.dirty)
    }

    @Test
    fun leftoversOfOlderVersionsAreRestored() {
        val store = MemoryStore()
        Ledger(store, "shield").apply {
            recordOriginal(ShieldModule.K_LATENCY, 40L)
            recordOriginal(ShieldModule.K_VOLUME, 15L)
        }
        val audio = FakeAudio(latency = 4900, volume = 0)
        val m = module(store, audio)
        assertEquals(emptyList<String>(), m.restore())
        assertEquals(40, audio.latency)
        assertEquals(15, audio.volume)
        assertFalse(m.dirty)
    }

    @Test
    fun unreadableOriginalLatencyRestoresToZero() {
        val store = MemoryStore()
        Ledger(store, "shield").recordOriginal(ShieldModule.K_LATENCY, null)
        val audio = FakeAudio(latency = 2500)
        module(store, audio).restore()
        assertEquals(0, audio.latency)
    }

    @Test
    fun parsesGetParametersReplies() {
        assertEquals(120, ShieldModule.parseLatency("${ShieldModule.PARAM}=120"))
        assertEquals(5, ShieldModule.parseLatency("a=1;${ShieldModule.PARAM}= 5 ;b=2"))
        assertNull(ShieldModule.parseLatency(""))
        assertNull(ShieldModule.parseLatency(null))
        assertNull(ShieldModule.parseLatency("${ShieldModule.PARAM}="))
        assertNull(ShieldModule.parseLatency("other=3"))
    }
}
