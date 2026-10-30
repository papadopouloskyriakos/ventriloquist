package net.nuclearlighters.ventriloquist.ma

import net.nuclearlighters.ventriloquist.http.HttpResponse
import net.nuclearlighters.ventriloquist.http.HttpServer
import net.nuclearlighters.ventriloquist.util.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MaClient (the JVM's real HttpURLConnection) against a fake Music Assistant. The fake runs on
 * the app's own plain-socket HttpServer: the JDK's com.sun.net.httpserver is not on the unit-test
 * compile classpath of an Android module.
 */
class MaClientTest {

    private data class Seen(val method: String, val path: String, val auth: String?, val type: String?, val body: Map<String, Any?>)

    private lateinit var server: HttpServer
    private val seen = mutableListOf<Seen>()
    private var reply: (Map<String, Any?>) -> Pair<Int, String> = { 200 to "null" }
    private lateinit var client: MaClient
    private var stopped = false

    @Before
    fun startServer() {
        server = HttpServer(0, { request ->
            val body = Json.parseObject(request.bodyText)
            synchronized(seen) {
                seen.add(Seen(request.method, request.path, request.header("authorization"), request.header("content-type"), body))
            }
            val (status, text) = reply(body)
            HttpResponse(status, text)
        })
        server.start()
        client = MaClient("http://127.0.0.1:${server.localPort}/", "secret-token")
    }

    @After
    fun stopServer() {
        if (!stopped) server.stop()
    }

    @Test
    fun sendsTheCommandEnvelopeWithBearerToken() {
        client.playMedia("group1", "vban_receiver--abc://audio_source/main")
        val r = seen.single()
        assertEquals("POST", r.method)
        assertEquals("/api", r.path)
        assertEquals("Bearer secret-token", r.auth)
        assertEquals("application/json", r.type)
        assertEquals("player_queues/play_media", r.body["command"])
        assertTrue((r.body["message_id"] as String).isNotEmpty())
        assertEquals(
            mapOf("queue_id" to "group1", "media" to "vban_receiver--abc://audio_source/main", "option" to "replace"),
            r.body["args"],
        )
    }

    @Test
    fun stops() {
        client.stop("group1")
        assertEquals("players/cmd/stop", seen[0].body["command"])
        assertEquals(mapOf("player_id" to "group1"), seen[0].body["args"])
    }

    @Test
    fun readsTheGroupVolumeOfAGroupAndTheVolumeOfAPlayer() {
        reply = { body ->
            assertEquals("players/get", body["command"])
            when ((body["args"] as Map<*, *>)["player_id"]) {
                "house" -> 200 to """{"player_id":"house","type":"group","group_volume":16,"volume_level":null}"""
                else -> 200 to """{"player_id":"one","type":"player","volume_level":11,"group_volume":30}"""
            }
        }
        assertEquals(16, client.volume("house"))
        assertEquals(11, client.volume("one"))
    }

    @Test
    fun setsTheGroupVolumeWithinRange() {
        client.setGroupVolume("house", 120)
        assertEquals("players/cmd/group_volume", seen[0].body["command"])
        assertEquals(mapOf("player_id" to "house", "volume_level" to 100L), seen[0].body["args"])
    }

    @Test
    fun listsVisiblePlayersGroupsFirst() {
        reply = {
            200 to """[
                {"player_id":"b","display_name":"Kitchen","type":"player","available":true},
                {"player_id":"g","display_name":"House","type":"group","available":true},
                {"player_id":"h","display_name":"Hidden","type":"player","hidden":true},
                {"player_id":"d","display_name":"Disabled","type":"player","enabled":false},
                {"player_id":"a","name":"Attic","type":"player","available":false}]"""
        }
        assertEquals(
            listOf(
                MaPlayer("g", "House", group = true, available = true),
                MaPlayer("a", "Attic", group = false, available = false),
                MaPlayer("b", "Kitchen", group = false, available = true),
            ),
            client.players(),
        )
    }

    @Test
    fun anHttpErrorFails() {
        reply = { 401 to "Authentication required" }
        val e = assertThrows(MaException::class.java) { client.stop("group1") }
        assertTrue(e.message!!, e.message!!.contains("HTTP 401 Unauthorized"))
        reply = { 500 to "Internal server error" }
        val f = assertThrows(MaException::class.java) { client.stop("group1") }
        assertTrue(f.message!!, f.message!!.contains("HTTP 500 Internal server error"))
    }

    @Test
    fun anErrorObjectFails() {
        reply = { 200 to """{"message_id":"x","error_code":999,"details":"Player not found"}""" }
        val e = assertThrows(MaException::class.java) { client.stop("nobody") }
        assertTrue(e.message!!, e.message!!.contains("999"))
        assertTrue(e.message!!, e.message!!.contains("Player not found"))
    }

    @Test
    fun anUnreachableServerFails() {
        val port = server.localPort
        server.stop()
        stopped = true
        val dead = MaClient("http://127.0.0.1:$port", "t", connectTimeoutMs = 1000, readTimeoutMs = 1000)
        assertThrows(MaException::class.java) { dead.stop("group1") }
    }

    @Test
    fun aGarbledReplyFails() {
        reply = { 200 to "{not json" }
        assertThrows(MaException::class.java) { client.stop("group1") }
    }
}
