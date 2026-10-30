package net.nuclearlighters.ventriloquist.config

import net.nuclearlighters.ventriloquist.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    @Test
    fun defaultsHoldNoSiteValues() {
        val c = Config()
        assertEquals("", c.controlToken)
        assertEquals("", c.maUrl)
        assertEquals("", c.vbanHost)
        assertEquals(6980, c.vbanPort)
        assertEquals("TV", c.vbanStream)
        assertEquals(-15, c.surroundSyncAdjustMs)
        assertEquals(0, c.filmLatencyMs)
        assertEquals(3, c.startDebounceS)
        assertEquals(180, c.stopDelayS)
        assertEquals(23 * 60, c.quietStartMinute)
        assertEquals(8 * 60, c.quietEndMinute)
    }

    @Test
    fun mergesAndRoundTripsThroughJson() {
        val update = Json.parseObject(
            """{"control_token":"0123456789abcdef","ma_url":"http://ma.example:8095/","ma_token":"t",
               "surround_player_ids":["a","b"],"vban_host":"192.0.2.10","vban_port":6981,
               "film_latency_ms":600,"film_packages":["com.example.video"],"quiet_start":"22:30"}""",
        )
        val c = Config().merge(update)
        assertEquals("http://ma.example:8095", c.maUrl)
        assertEquals(listOf("a", "b"), c.surroundPlayerIds)
        assertEquals(600, c.filmLatencyMs)
        assertEquals(22 * 60 + 30, c.quietStartMinute)
        val again = Config().merge(Json.parseObject(Json.write(c.toMap(redactSecrets = false))))
        assertEquals(c, again)
    }

    @Test
    fun nullResetsToDefault() {
        val c = Config(vbanPort = 7000).merge(mapOf("vban_port" to null))
        assertEquals(6980, c.vbanPort)
    }

    @Test
    fun rejectsTheWholeUpdateOnOneBadValue() {
        val e = assertThrows(Config.ConfigException::class.java) {
            Config().merge(
                mapOf(
                    "vban_port" to 70000L,
                    "control_token" to "short",
                    "quiet_end" to "8:00",
                    "nope" to 1L,
                    "film_latency_ms" to 100L,
                ),
            )
        }
        assertEquals(4, e.errors.size)
        assertTrue(e.errors.any { it.startsWith("nope:") })
    }

    @Test
    fun redactsSecrets() {
        val m = Config(controlToken = "0123456789abcdef", maToken = "x").toMap(redactSecrets = true)
        assertEquals("set", m["control_token"])
        assertEquals("set", m["ma_token"])
        assertEquals("", Config().toMap(redactSecrets = true)["ma_token"])
    }

    @Test
    fun listsWhatHouseModeStillNeeds() {
        assertEquals(
            listOf("ma_url", "ma_token", "source_uri", "vban_host", "film_player_id"),
            Config().missingForHouse(film = true),
        )
        val ready = Config(
            maUrl = "http://m", maToken = "t", sourceUri = "s", vbanHost = "h", musicPlayerId = "p",
        )
        assertEquals(emptyList<String>(), ready.missingForHouse(film = false))
        assertEquals(listOf("film_player_id"), ready.missingForHouse(film = true))
    }

    @Test
    fun everyKeyIsMergeable() {
        val all = Config().toMap(redactSecrets = false)
        assertEquals(Config.KEYS, all.keys.toList())
        assertEquals(Config(), Config().merge(all))
    }
}
