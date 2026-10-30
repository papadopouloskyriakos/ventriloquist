package net.nuclearlighters.ventriloquist.config

import net.nuclearlighters.ventriloquist.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    @Test
    fun defaultsHoldNoSiteValues() {
        val c = Config()
        assertEquals("", c.controlToken)
        assertEquals("", c.maUrl)
        assertEquals("", c.playerId)
        assertEquals("", c.vbanHost)
        assertEquals("", c.haWebhookUrl)
        assertEquals(6980, c.vbanPort)
        assertEquals("TV", c.vbanStream)
        assertTrue(c.enabled)
        assertTrue(c.muteBox)
        assertTrue(c.volumeKeys)
        assertEquals(2, c.volumeStep)
        assertEquals(40, c.maxVolume)
        assertEquals(3, c.startDebounceS)
        assertEquals(180, c.stopDelayS)
        assertEquals(23 * 60, c.quietStartMinute)
        assertEquals(8 * 60, c.quietEndMinute)
    }

    @Test
    fun mergesAndRoundTripsThroughJson() {
        val update = Json.parseObject(
            """{"control_token":"0123456789abcdef","ma_url":"http://ma.example:8095/","ma_token":"t",
               "player_id":"group1","vban_host":"192.0.2.10","vban_port":6981,"enabled":false,
               "volume_step":3,"max_volume":55,"ha_webhook_url":"https://ha.example/api/webhook/x",
               "quiet_start":"22:30"}""",
        )
        val c = Config().merge(update)
        assertEquals("http://ma.example:8095", c.maUrl)
        assertEquals("group1", c.playerId)
        assertFalse(c.enabled)
        assertEquals(3, c.volumeStep)
        assertEquals(55, c.maxVolume)
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
                    "volume_step" to 11L,
                    "max_volume" to 0L,
                    "ha_webhook_url" to "ftp://x",
                    "player_id" to "fine",
                ),
            )
        }
        assertEquals(7, e.errors.size)
        assertTrue(e.errors.any { it.startsWith("nope:") })
    }

    @Test
    fun filmKeysAreUnknownNow() {
        assertThrows(Config.ConfigException::class.java) { Config().merge(mapOf("film_latency_ms" to 100L)) }
    }

    @Test
    fun migratesAStoredFilmConfiguration() {
        val stored = mapOf<String, Any?>(
            "control_token" to "0123456789abcdef",
            "film_player_id" to "tv",
            "music_player_id" to "house",
            "surround_player_ids" to listOf("a"),
            "film_latency_ms" to 0L,
            "tv_mute" to false,
            "film_packages" to emptyList<String>(),
            "music_packages" to emptyList<String>(),
            "surround_sync_adjust_ms" to -15L,
        )
        val c = Config().merge(Config.migrate(stored))
        assertEquals("house", c.playerId)
        assertEquals("0123456789abcdef", c.controlToken)
        // an explicit player wins over the old music player
        assertEquals("x", Config().merge(Config.migrate(stored + ("player_id" to "x"))).playerId)
        // unknown keys stay, so a damaged store is reported
        assertTrue(Config.migrate(mapOf("nope" to 1L)).containsKey("nope"))
    }

    @Test
    fun redactsSecrets() {
        val m = Config(controlToken = "0123456789abcdef", maToken = "x", haWebhookUrl = "https://h/api/webhook/id")
            .toMap(redactSecrets = true)
        assertEquals("set", m["control_token"])
        assertEquals("set", m["ma_token"])
        assertEquals("set", m["ha_webhook_url"])
        assertEquals("", Config().toMap(redactSecrets = true)["ma_token"])
    }

    @Test
    fun listsWhatHouseModeStillNeeds() {
        assertEquals(
            listOf("ma_url", "ma_token", "source_uri", "vban_host", "player_id"),
            Config().missingForHouse(),
        )
        val ready = Config(maUrl = "http://m", maToken = "t", sourceUri = "s", vbanHost = "h", playerId = "p")
        assertEquals(emptyList<String>(), ready.missingForHouse())
    }

    @Test
    fun everyKeyIsMergeable() {
        val all = Config().toMap(redactSecrets = false)
        assertEquals(Config.KEYS, all.keys.toList())
        assertEquals(Config(), Config().merge(all))
    }
}
