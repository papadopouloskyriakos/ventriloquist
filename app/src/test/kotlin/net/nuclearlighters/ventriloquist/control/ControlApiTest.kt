package net.nuclearlighters.ventriloquist.control

import net.nuclearlighters.ventriloquist.http.HttpRequest
import net.nuclearlighters.ventriloquist.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlApiTest {

    private val token = "0123456789abcdef0123"

    private class FakeBackend(var token: String) : ControlBackend {
        val calls = mutableListOf<String>()
        var captureFails = false
        override fun controlToken() = token
        override fun status() = mapOf<String, Any?>("ok" to true).also { calls.add("status") }
        override fun captureTest(seconds: Int): Map<String, Any?> {
            if (captureFails) throw IllegalStateException("consent needed")
            calls.add("capture $seconds")
            return mapOf("seconds" to seconds)
        }
        override fun restore() = mapOf<String, Any?>("errors" to emptyList<String>()).also { calls.add("restore") }
        override fun setMode(mode: String) = mapOf<String, Any?>("mode" to mode).also { calls.add("mode $mode") }
        override fun configure(update: Map<String, Any?>) =
            mapOf<String, Any?>("updated" to update.keys.sorted()).also { calls.add("config ${update.keys.sorted()}") }
        override fun nudgeVolume(steps: Int) = mapOf<String, Any?>("volume" to 13L).also { calls.add("volume $steps") }
        override fun players() = mapOf<String, Any?>("players" to emptyList<Any>()).also { calls.add("players") }
    }

    private fun request(method: String, path: String, body: String = "", auth: String? = "Bearer $token") =
        HttpRequest(
            method, path, emptyMap(),
            if (auth == null) emptyMap() else mapOf("authorization" to auth),
            body.toByteArray(),
        )

    @Test
    fun refusesEverythingUntilATokenIsConfigured() {
        val api = ControlApi(FakeBackend(""))
        assertEquals(503, api.handle(request("GET", "/status", auth = "Bearer ")).status)
    }

    @Test
    fun requiresTheBearerToken() {
        val backend = FakeBackend(token)
        val api = ControlApi(backend)
        assertEquals(401, api.handle(request("GET", "/status", auth = null)).status)
        assertEquals(401, api.handle(request("GET", "/status", auth = "Bearer wrong")).status)
        assertEquals(401, api.handle(request("GET", "/status", auth = "Basic $token")).status)
        assertEquals(200, api.handle(request("GET", "/status", auth = "bearer $token")).status)
        assertEquals(listOf("status"), backend.calls)
    }

    @Test
    fun routesEndpoints() {
        val backend = FakeBackend(token)
        val api = ControlApi(backend)
        assertEquals(200, api.handle(request("POST", "/capture-test", """{"seconds":5}""")).status)
        assertEquals(200, api.handle(request("POST", "/restore")).status)
        for (mode in listOf("auto", "off", "on")) {
            assertEquals(200, api.handle(request("POST", "/mode", """{"mode":"$mode"}""")).status)
        }
        assertEquals(200, api.handle(request("POST", "/config", """{"vban_port":6980,"vban_host":"x"}""")).status)
        val v = api.handle(request("POST", "/volume", """{"steps":-2}"""))
        assertEquals(200, v.status)
        assertEquals(13L, Json.parseObject(v.body)["volume"])
        assertEquals(200, api.handle(request("GET", "/players")).status)
        assertEquals(
            listOf(
                "capture 5", "restore", "mode auto", "mode off", "mode on",
                "config [vban_host, vban_port]", "volume -2", "players",
            ),
            backend.calls,
        )
    }

    @Test
    fun validatesArguments() {
        val backend = FakeBackend(token)
        val api = ControlApi(backend)
        assertEquals(400, api.handle(request("POST", "/capture-test", """{"seconds":31}""")).status)
        assertEquals(400, api.handle(request("POST", "/capture-test", """{"seconds":"5"}""")).status)
        assertEquals(400, api.handle(request("POST", "/capture-test", "")).status)
        assertEquals(400, api.handle(request("POST", "/capture-test", "{oops")).status)
        assertEquals(400, api.handle(request("POST", "/mode", """{"mode":"film"}""")).status)
        assertEquals(400, api.handle(request("POST", "/mode", """{"mode":1}""")).status)
        assertEquals(400, api.handle(request("POST", "/config", "{}")).status)
        assertEquals(400, api.handle(request("POST", "/volume", """{"steps":11}""")).status)
        assertEquals(400, api.handle(request("POST", "/volume", """{"steps":1.5}""")).status)
        assertEquals(405, api.handle(request("GET", "/mode")).status)
        assertEquals(405, api.handle(request("POST", "/players")).status)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun filmEndpointsAreGone() {
        val api = ControlApi(FakeBackend(token))
        assertEquals(404, api.handle(request("GET", "/latency")).status)
        assertEquals(404, api.handle(request("POST", "/latency/step", """{"ms":20}""")).status)
        assertEquals(404, api.handle(request("POST", "/tv-mute", """{"on":true}""")).status)
    }

    @Test
    fun mapsUnknownPathsMethodsAndStates() {
        val backend = FakeBackend(token)
        val api = ControlApi(backend)
        assertEquals(404, api.handle(request("GET", "/nope")).status)
        assertEquals(405, api.handle(request("POST", "/status")).status)
        assertEquals(405, api.handle(request("GET", "/restore")).status)
        backend.captureFails = true
        val r = api.handle(request("POST", "/capture-test", """{"seconds":2}"""))
        assertEquals(409, r.status)
        assertEquals("consent needed", Json.parseObject(r.body)["error"])
    }

    @Test
    fun comparesTokensExactly() {
        assertTrue(ControlApi.authorized("Bearer $token", token))
        assertFalse(ControlApi.authorized("Bearer ${token}x", token))
        assertFalse(ControlApi.authorized("Bearer", token))
        assertFalse(ControlApi.authorized(null, token))
    }

    @Test
    fun aRefusedModeChangeIsAConflict() {
        val api = ControlApi(object : ControlBackend by FakeBackend(token) {
            override fun setMode(mode: String): Map<String, Any?> = throw ApiException(409, "quiet hours")
        })
        val r = api.handle(request("POST", "/mode", """{"mode":"on"}"""))
        assertEquals(409, r.status)
        assertEquals("quiet hours", Json.parseObject(r.body)["error"])
    }
}
