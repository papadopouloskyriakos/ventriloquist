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
        override fun readLatency() = mapOf<String, Any?>("hdmi_latency_ms" to 0L).also { calls.add("readLatency") }
        override fun setLatency(ms: Int) = mapOf<String, Any?>("read_back_ms" to ms).also { calls.add("setLatency $ms") }
        override fun setTvMute(on: Boolean) = mapOf<String, Any?>("on" to on).also { calls.add("tvMute $on") }
        override fun captureTest(seconds: Int): Map<String, Any?> {
            if (captureFails) throw IllegalStateException("consent needed")
            calls.add("capture $seconds")
            return mapOf("seconds" to seconds)
        }
        override fun restore() = mapOf<String, Any?>("errors" to emptyList<String>()).also { calls.add("restore") }
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
    fun routesM1Endpoints() {
        val backend = FakeBackend(token)
        val api = ControlApi(backend)
        assertEquals(200, api.handle(request("GET", "/latency")).status)
        val set = api.handle(request("POST", "/latency", """{"ms":640}"""))
        assertEquals(200, set.status)
        assertEquals(640L, Json.parseObject(set.body)["read_back_ms"])
        assertEquals(200, api.handle(request("POST", "/tv-mute", """{"on":true}""")).status)
        assertEquals(200, api.handle(request("POST", "/capture-test", """{"seconds":5}""")).status)
        assertEquals(200, api.handle(request("POST", "/restore")).status)
        assertEquals(
            listOf("readLatency", "setLatency 640", "tvMute true", "capture 5", "restore"),
            backend.calls,
        )
    }

    @Test
    fun validatesArguments() {
        val backend = FakeBackend(token)
        val api = ControlApi(backend)
        assertEquals(400, api.handle(request("POST", "/latency", """{"ms":-5}""")).status)
        assertEquals(400, api.handle(request("POST", "/latency", """{"ms":"5"}""")).status)
        assertEquals(400, api.handle(request("POST", "/latency", """{"ms":1.5}""")).status)
        assertEquals(400, api.handle(request("POST", "/latency", "")).status)
        assertEquals(400, api.handle(request("POST", "/latency", "{oops")).status)
        assertEquals(400, api.handle(request("POST", "/tv-mute", """{"on":"yes"}""")).status)
        assertEquals(400, api.handle(request("POST", "/capture-test", """{"seconds":31}""")).status)
        assertTrue(backend.calls.isEmpty())
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
}
