package net.nuclearlighters.ventriloquist.house

import net.nuclearlighters.ventriloquist.config.Config
import net.nuclearlighters.ventriloquist.ma.MaApi
import net.nuclearlighters.ventriloquist.ma.MaException
import net.nuclearlighters.ventriloquist.shield.AudioControls
import net.nuclearlighters.ventriloquist.shield.LatencyModule
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
        var volume = 13
        var surround: String? = "0"
        override fun getParameters(keys: String) = "${LatencyModule.PARAM}=$latency"
        override fun setParameters(keyValuePairs: String) {
            latency = keyValuePairs.substringAfter('=').toInt()
        }
        override fun musicVolume() = volume
        override fun musicVolumeMax() = 15
        override fun setMusicVolume(index: Int) {
            volume = index
        }
        override fun surroundSetting() = surround
        override fun setSurroundSetting(value: String?) {
            surround = value
        }
    }

    /** A fake Music Assistant holding player settings; records every call. */
    private class FakeMa : MaApi {
        val players = mutableMapOf<String, MutableMap<String, Any?>>(
            "sp1" to mutableMapOf("output_channels" to "stereo", "sync_adjust" to 0L),
            "sp2" to mutableMapOf("output_channels" to "left", "sync_adjust" to 20L),
            "bare" to mutableMapOf(),
        )
        val calls = mutableListOf<String>()
        var failOn: String? = null

        private fun check(call: String) {
            calls.add(call)
            if (failOn != null && call.startsWith(failOn!!)) throw MaException("$call refused")
        }

        override fun getPlayerValues(playerId: String, keys: List<String>): Map<String, Any?> {
            check("get $playerId")
            return players.getValue(playerId).filterKeys { it in keys }
        }

        override fun savePlayerValues(playerId: String, values: Map<String, Any?>) {
            check("save $playerId $values")
            players.getValue(playerId).putAll(values)
        }

        override fun playMedia(queueId: String, media: String) = check("play $queueId $media")

        override fun stop(playerId: String) = check("stop $playerId")
    }

    private class FakeStreamer : Streamer {
        override var running = false
        var starts = 0
        var failStart = false
        override fun start(config: Config) {
            if (failStart) throw IllegalStateException("no media projection: consent needed")
            if (!running) starts++
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
        filmPlayerId = "group",
        musicPlayerId = "kitchen",
        surroundPlayerIds = listOf("sp1", "sp2"),
        surroundSyncAdjustMs = -15,
        vbanHost = "192.0.2.10",
        filmLatencyMs = 600,
    )

    private class Rig(
        val store: MemoryStore = MemoryStore(),
        val audio: FakeAudio = FakeAudio(),
        val ma: FakeMa = FakeMa(),
        val streamer: FakeStreamer = FakeStreamer(),
    ) {
        val shield = LatencyModule(audio, Ledger(store, "shield"))
        val session = HouseSession(shield, Ledger(store, "music_assistant"), { ma }, streamer)

        /** The same stores and devices after a process restart. */
        fun restarted() = Rig(store, audio, ma, FakeStreamer())
    }

    @Test
    fun filmSavesAndSetsTheSurroundPlayersThenStopRestoresEverything() {
        val r = Rig()
        r.session.start(HouseMode.FILM, config)

        assertEquals(HouseMode.FILM, r.session.mode)
        assertEquals("group", r.session.target)
        assertEquals(mapOf("output_channels" to "surround", "sync_adjust" to -15), r.ma.players["sp1"])
        assertEquals(mapOf("output_channels" to "surround", "sync_adjust" to -15), r.ma.players["sp2"])
        assertEquals(600, r.audio.latency)
        assertEquals(0, r.audio.volume)
        assertEquals(LatencyModule.SURROUND_NEVER, r.audio.surround)
        assertTrue(r.streamer.running)
        assertEquals(
            listOf(
                "get sp1", "save sp1 {output_channels=surround, sync_adjust=-15}",
                "get sp2", "save sp2 {output_channels=surround, sync_adjust=-15}",
                "play group vban://source",
            ),
            r.ma.calls,
        )

        r.ma.calls.clear()
        assertEquals(emptyList<String>(), r.session.stop(config))
        assertEquals("stop group", r.ma.calls.first())
        assertEquals(mapOf("output_channels" to "stereo", "sync_adjust" to 0L), r.ma.players["sp1"])
        assertEquals(mapOf("output_channels" to "left", "sync_adjust" to 20L), r.ma.players["sp2"])
        assertEquals(0, r.audio.latency)
        assertEquals(13, r.audio.volume)
        assertEquals("0", r.audio.surround)
        assertFalse(r.streamer.running)
        assertFalse(r.session.maDirty)
        assertFalse(r.shield.dirty)
        assertNull(r.session.mode)
        assertTrue(r.store.values.isEmpty())
    }

    @Test
    fun musicLeavesTheChannelsAloneAndClearsTheLatency() {
        val r = Rig()
        r.audio.latency = 40
        r.session.start(HouseMode.MUSIC, config)
        assertEquals(listOf("play kitchen vban://source"), r.ma.calls)
        assertEquals(0, r.audio.latency)
        assertEquals(0, r.audio.volume)
        r.session.stop(config)
        assertEquals(40, r.audio.latency)
        assertEquals(13, r.audio.volume)
    }

    @Test
    fun aFailedStartPutsEverythingBack() {
        val r = Rig()
        r.ma.failOn = "play"
        assertThrows(MaException::class.java) { r.session.start(HouseMode.FILM, config) }
        assertNull(r.session.mode)
        assertEquals("stereo", r.ma.players["sp1"]!!["output_channels"])
        assertEquals("left", r.ma.players["sp2"]!!["output_channels"])
        assertEquals(0, r.audio.latency)
        assertEquals(13, r.audio.volume)
        assertFalse(r.streamer.running)
        assertFalse(r.session.maDirty)
        assertTrue(r.session.lastMaError!!.contains("refused"))
    }

    @Test
    fun noConsentMeansNoChangeLeftBehind() {
        val r = Rig()
        r.streamer.failStart = true
        assertThrows(IllegalStateException::class.java) { r.session.start(HouseMode.MUSIC, config) }
        assertEquals(13, r.audio.volume)
        assertFalse(r.shield.dirty)
    }

    @Test
    fun aRestartedProcessCleansUpAfterACrash() {
        val r = Rig()
        r.session.start(HouseMode.FILM, config)
        // The process dies in film mode; the next one finds the ledgers.
        val next = r.restarted()
        assertTrue(next.session.maDirty)
        assertTrue(next.shield.dirty)
        r.ma.calls.clear()
        assertEquals(emptyList<String>(), next.session.stop(config))
        assertEquals("stop group", r.ma.calls.first())
        assertEquals("stereo", r.ma.players["sp1"]!!["output_channels"])
        assertEquals(20L, r.ma.players["sp2"]!!["sync_adjust"])
        assertEquals(13, r.audio.volume)
        assertEquals(0, r.audio.latency)
        assertTrue(r.store.values.isEmpty())
    }

    @Test
    fun aFailedCleanupStaysRecordedForTheNextAttempt() {
        val r = Rig()
        r.session.start(HouseMode.FILM, config)
        r.ma.failOn = "save sp2"
        val errors = r.session.stop(config)
        assertEquals(1, errors.size)
        assertTrue(errors[0].startsWith("restore sp2"))
        assertTrue(r.session.maDirty)
        assertFalse(r.shield.dirty) // the Shield side does not wait for Music Assistant
        r.ma.failOn = null
        val next = r.restarted()
        assertEquals(emptyList<String>(), next.session.stop(config))
        assertEquals("left", r.ma.players["sp2"]!!["output_channels"])
        assertFalse(next.session.maDirty)
        assertTrue(r.store.values.isEmpty())
    }

    @Test
    fun switchingFromFilmToMusicMovesOnlyTheMusicAssistantSide() {
        val r = Rig()
        r.session.start(HouseMode.FILM, config)
        r.ma.calls.clear()
        r.session.start(HouseMode.MUSIC, config)
        assertEquals(HouseMode.MUSIC, r.session.mode)
        assertEquals("kitchen", r.session.target)
        assertEquals("stop group", r.ma.calls[0])
        assertEquals("play kitchen vban://source", r.ma.calls.last())
        assertEquals("stereo", r.ma.players["sp1"]!!["output_channels"])
        assertEquals(0, r.audio.latency)
        assertEquals(0, r.audio.volume) // still muted, no unmute blip
        assertEquals(1, r.streamer.starts) // capture kept running
        r.session.stop(config)
        assertEquals(13, r.audio.volume)
        assertEquals(0, r.audio.latency)
    }

    @Test
    fun refusesToStartWhenNotConfigured() {
        val r = Rig()
        val e = assertThrows(IllegalStateException::class.java) {
            r.session.start(HouseMode.FILM, config.copy(filmPlayerId = "", vbanHost = ""))
        }
        assertTrue(e.message!!, e.message!!.contains("vban_host, film_player_id"))
        assertTrue(r.ma.calls.isEmpty())
        assertFalse(r.shield.dirty)
    }

    @Test
    fun aSurroundPlayerWithoutTheSettingsFailsCleanly() {
        val r = Rig()
        val e = assertThrows(IllegalStateException::class.java) {
            r.session.start(HouseMode.FILM, config.copy(surroundPlayerIds = listOf("sp1", "bare")))
        }
        assertTrue(e.message!!, e.message!!.contains("bare"))
        assertEquals("stereo", r.ma.players["sp1"]!!["output_channels"])
        assertFalse(r.session.maDirty)
    }

    @Test
    fun filmLatencyAppliesLiveOnlyInFilmMode() {
        val r = Rig()
        assertNull(r.session.applyFilmLatency(700))
        r.session.start(HouseMode.FILM, config)
        assertEquals(720, r.session.applyFilmLatency(720)!!.ms)
        r.session.stop(config)
        assertEquals(0, r.audio.latency)
    }
}
