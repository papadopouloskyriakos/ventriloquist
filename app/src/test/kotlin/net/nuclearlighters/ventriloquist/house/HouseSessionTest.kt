package net.nuclearlighters.ventriloquist.house

import net.nuclearlighters.ventriloquist.config.Config
import net.nuclearlighters.ventriloquist.ma.MaApi
import net.nuclearlighters.ventriloquist.ma.MaException
import net.nuclearlighters.ventriloquist.ma.MaPlayer
import net.nuclearlighters.ventriloquist.shield.AudioControls
import net.nuclearlighters.ventriloquist.shield.ShieldModule
import net.nuclearlighters.ventriloquist.store.Ledger
import net.nuclearlighters.ventriloquist.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseSessionTest {

    private class FakeAudio : AudioControls {
        var latency = 0
        var volume = 15
        var muted = false
        var surround: String? = "0"
        val calls = mutableListOf<String>()
        override fun getParameters(keys: String) = "${ShieldModule.PARAM}=$latency"
        override fun setParameters(keyValuePairs: String) {
            calls.add("latency")
            latency = keyValuePairs.substringAfter('=').toInt()
        }
        override fun musicVolume() = volume
        override fun musicVolumeMax() = 15
        override fun setMusicVolume(index: Int) {
            calls.add("volume $index")
            volume = index
        }
        override fun musicMuted() = muted
        override fun setMusicMuted(muted: Boolean) {
            calls.add("mute $muted")
            this.muted = muted
        }
        override fun surroundSetting() = surround
        override fun setSurroundSetting(value: String?) {
            surround = value
        }
    }

    /** A fake Music Assistant; records every call. */
    private class FakeMa : MaApi {
        val calls = mutableListOf<String>()
        var failOn: String? = null
        var level: Int? = 11

        private fun check(call: String) {
            calls.add(call)
            if (failOn != null && call.startsWith(failOn!!)) throw MaException("$call refused")
        }

        override fun playMedia(queueId: String, media: String) = check("play $queueId $media")
        override fun stop(playerId: String) = check("stop $playerId")
        override fun volume(playerId: String): Int? {
            check("volume $playerId")
            return level
        }
        override fun setGroupVolume(playerId: String, level: Int) {
            check("set $playerId $level")
            this.level = level
        }
        override fun players(): List<MaPlayer> = emptyList()
    }

    private class FakeStreamer : Streamer {
        override var running = false
        var failStart = false
        override fun start(config: Config) {
            if (failStart) throw IllegalStateException("no media projection: consent needed")
            running = true
        }
        override fun stop() {
            running = false
        }
    }

    private val config = Config(
        maUrl = "http://ma.example:8095",
        maToken = "token",
        sourceUri = "vban://source",
        playerId = "house",
        vbanHost = "192.0.2.10",
        volumeStep = 2,
        maxVolume = 40,
    )

    private class Rig(
        val store: MemoryStore = MemoryStore(),
        val audio: FakeAudio = FakeAudio(),
        val ma: FakeMa = FakeMa(),
        val streamer: FakeStreamer = FakeStreamer(),
    ) {
        val shield = ShieldModule(audio, Ledger(store, "shield"))
        val session = HouseSession(shield, Ledger(store, "music_assistant"), { ma }, streamer)

        /** The same stores and devices after a process restart. */
        fun restarted() = Rig(store, audio, ma, FakeStreamer())
    }

    @Test
    fun startMutesTheBoxForcesPcmAndPlaysTheSource() {
        val r = Rig()
        r.session.start(config)
        assertTrue(r.audio.muted)
        assertEquals(ShieldModule.SURROUND_NEVER, r.audio.surround)
        assertEquals(15, r.audio.volume) // the index is never stepped
        assertEquals(0, r.audio.latency)
        assertTrue(r.streamer.running)
        assertEquals(listOf("play house vban://source"), r.ma.calls)
        assertTrue(r.session.running)
        assertEquals("house", r.session.target)
        assertEquals(listOf("mute true"), r.audio.calls)
    }

    @Test
    fun stopPutsEverythingBack() {
        val r = Rig()
        r.session.start(config)
        assertEquals(emptyList<String>(), r.session.stop(config))
        assertFalse(r.audio.muted)
        assertEquals("0", r.audio.surround)
        assertFalse(r.streamer.running)
        assertEquals(listOf("play house vban://source", "stop house"), r.ma.calls)
        assertFalse(r.session.running)
        assertFalse(r.shield.dirty)
        assertFalse(r.session.maDirty)
    }

    @Test
    fun muteBoxOffLeavesTheTvSoundAlone() {
        val r = Rig()
        r.session.start(config.copy(muteBox = false))
        assertFalse(r.audio.muted)
        assertTrue(r.audio.calls.isEmpty())
    }

    @Test
    fun aBoxMutedBeforeStaysMuted() {
        val r = Rig()
        r.audio.muted = true
        r.session.start(config)
        r.session.stop(config)
        assertTrue(r.audio.muted)
        assertTrue(r.audio.calls.isEmpty())
    }

    @Test
    fun aFailedPlayPutsEverythingBack() {
        val r = Rig()
        r.ma.failOn = "play"
        assertThrows(MaException::class.java) { r.session.start(config) }
        assertFalse(r.audio.muted)
        assertEquals("0", r.audio.surround)
        assertFalse(r.streamer.running)
        assertFalse(r.session.running)
        // the player was recorded before play, so stop was attempted and the ledger is clean
        assertEquals(listOf("play house vban://source", "stop house"), r.ma.calls)
        assertFalse(r.session.maDirty)
        assertTrue(r.session.lastMaError!!.contains("refused"))
    }

    @Test
    fun aFailedStreamerStartPutsTheBoxBack() {
        val r = Rig()
        r.streamer.failStart = true
        assertThrows(IllegalStateException::class.java) { r.session.start(config) }
        assertFalse(r.audio.muted)
        assertTrue(r.ma.calls.isEmpty())
        assertFalse(r.shield.dirty)
    }

    @Test
    fun refusesToStartUnconfigured() {
        val r = Rig()
        val e = assertThrows(IllegalStateException::class.java) { r.session.start(config.copy(playerId = "")) }
        assertTrue(e.message!!, e.message!!.contains("player_id"))
        assertFalse(r.audio.muted)
    }

    @Test
    fun aCrashedRunIsCleanedUpAfterARestart() {
        val r = Rig()
        r.session.start(config)
        val after = r.restarted()
        assertTrue(after.shield.dirty)
        assertTrue(after.session.maDirty)
        assertEquals(emptyList<String>(), after.session.stop(config))
        assertFalse(r.audio.muted)
        assertEquals("stop house", r.ma.calls.last())
        assertFalse(after.shield.dirty)
        assertFalse(after.session.maDirty)
    }

    @Test
    fun aFailedStopStaysRecordedForTheNextAttempt() {
        val r = Rig()
        r.session.start(config)
        r.ma.failOn = "stop"
        val errors = r.session.stop(config)
        assertEquals(1, errors.size)
        assertTrue(r.session.maDirty)
        assertFalse(r.audio.muted) // the TV gets its sound back whatever Music Assistant says
        r.ma.failOn = null
        assertEquals(emptyList<String>(), r.session.stop(config))
        assertFalse(r.session.maDirty)
    }

    @Test
    fun filmLeftoversOfAnOlderVersionAreRestoredAndDropped() {
        val store = MemoryStore()
        Ledger(store, "shield").apply {
            recordOriginal(ShieldModule.K_LATENCY, 0L)
            recordOriginal(ShieldModule.K_VOLUME, 15L)
        }
        Ledger(store, "music_assistant").recordOriginal("player:sp1", mapOf("output_channels" to "left"))
        val audio = FakeAudio().apply {
            latency = 4900
            volume = 0
        }
        val r = Rig(store, audio)
        assertEquals(emptyList<String>(), r.session.stop(config))
        assertEquals(0, audio.latency)
        assertEquals(15, audio.volume)
        assertFalse(r.shield.dirty)
        assertFalse(r.session.maDirty)
    }

    @Test
    fun volumeStepsWithinTheMaximum() {
        val r = Rig()
        r.session.start(config)
        assertEquals(13, r.session.nudgeVolume(1, config, null))
        assertEquals(19, r.session.nudgeVolume(3, config, 13))
        assertEquals(listOf("volume house", "set house 13", "set house 19"), r.ma.calls.drop(1))
        assertEquals(40, r.session.nudgeVolume(50, config, 39))
        assertEquals(40, r.session.nudgeVolume(1, config, 40))
        assertEquals("set house 40", r.ma.calls.last()) // no call when nothing changes
        assertEquals(0, r.session.nudgeVolume(-100, config, 40))
    }

    @Test
    fun volumeDoesNothingWhenNotRunning() {
        val r = Rig()
        assertNull(r.session.nudgeVolume(1, config, null))
        assertTrue(r.ma.calls.isEmpty())
    }

    @Test
    fun volumeMath() {
        assertEquals(13, VolumeMath.next(11, 1, 2, 40))
        assertEquals(40, VolumeMath.next(39, 1, 2, 40))
        assertEquals(60, VolumeMath.next(60, 1, 2, 40)) // set above the maximum elsewhere: up keeps it
        assertEquals(58, VolumeMath.next(60, -1, 2, 40)) // down still works
        assertEquals(0, VolumeMath.next(1, -1, 2, 40))
        assertEquals(100, VolumeMath.next(99, 5, 5, 100))
    }
}
