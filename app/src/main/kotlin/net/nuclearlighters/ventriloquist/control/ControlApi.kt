package net.nuclearlighters.ventriloquist.control

import net.nuclearlighters.ventriloquist.http.HttpRequest
import net.nuclearlighters.ventriloquist.http.HttpResponse
import net.nuclearlighters.ventriloquist.util.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** What the control endpoint can ask of the service. Results are JSON-ready maps. */
interface ControlBackend {
    /** The configured bearer token; empty means the endpoint is not configured yet. */
    fun controlToken(): String

    fun status(): Map<String, Any?>
    fun readLatency(): Map<String, Any?>
    fun setLatency(ms: Int): Map<String, Any?>
    fun setTvMute(on: Boolean): Map<String, Any?>
    fun captureTest(seconds: Int): Map<String, Any?>
    fun restore(): Map<String, Any?>
}

/** A request the backend refuses, with the HTTP status to answer. */
class ApiException(val status: Int, message: String) : Exception(message)

/**
 * Routing, authentication and argument checks for the control endpoint. Every request needs
 * `Authorization: Bearer <control_token>`; bodies are JSON objects.
 */
class ControlApi(private val backend: ControlBackend) {

    fun handle(request: HttpRequest): HttpResponse {
        val token = backend.controlToken()
        if (token.isEmpty()) return error(503, "control_token is not configured")
        if (!authorized(request.header("authorization"), token)) {
            return HttpResponse(
                401,
                Json.write(mapOf("error" to "missing or wrong bearer token")),
                extraHeaders = mapOf("WWW-Authenticate" to "Bearer"),
            )
        }
        return try {
            route(request)
        } catch (e: ApiException) {
            error(e.status, e.message ?: "refused")
        } catch (e: Json.ParseException) {
            error(400, "invalid JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            error(400, e.message ?: "bad request")
        } catch (e: IllegalStateException) {
            error(409, e.message ?: "not possible now")
        } catch (e: Exception) {
            error(500, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun route(r: HttpRequest): HttpResponse {
        val known = ROUTES[r.path] ?: return error(404, "no such endpoint")
        if (r.method !in known) return error(405, "use ${known.joinToString(" or ")}")
        return when ("${r.method} ${r.path}") {
            "GET /status" -> ok(backend.status())
            "GET /latency" -> ok(backend.readLatency())
            "POST /latency" -> ok(backend.setLatency(intField(body(r), "ms", 0..MAX_LATENCY_MS)))
            "POST /tv-mute" -> ok(backend.setTvMute(boolField(body(r), "on")))
            "POST /capture-test" ->
                ok(backend.captureTest(intField(body(r), "seconds", 1..MAX_CAPTURE_TEST_S)))
            "POST /restore" -> ok(backend.restore())
            else -> error(404, "no such endpoint")
        }
    }

    companion object {
        const val MAX_LATENCY_MS = 5000
        const val MAX_CAPTURE_TEST_S = 30

        val ROUTES: Map<String, Set<String>> = mapOf(
            "/status" to setOf("GET"),
            "/latency" to setOf("GET", "POST"),
            "/tv-mute" to setOf("POST"),
            "/capture-test" to setOf("POST"),
            "/restore" to setOf("POST"),
        )

        fun authorized(header: String?, token: String): Boolean {
            if (header == null || header.length < 7) return false
            if (!header.substring(0, 7).equals("Bearer ", ignoreCase = true)) return false
            val given = header.substring(7).trim().toByteArray(StandardCharsets.UTF_8)
            return MessageDigest.isEqual(given, token.toByteArray(StandardCharsets.UTF_8))
        }

        fun body(r: HttpRequest): Map<String, Any?> {
            val text = r.bodyText.trim()
            if (text.isEmpty()) return emptyMap()
            return Json.parseObject(text)
        }

        fun intField(body: Map<String, Any?>, name: String, range: IntRange): Int {
            val raw = body[name] ?: throw IllegalArgumentException("'$name' is required")
            val v = when (raw) {
                is Long -> raw
                is Double -> if (raw == Math.floor(raw)) raw.toLong() else null
                else -> null
            } ?: throw IllegalArgumentException("'$name' must be an integer")
            if (v < range.first || v > range.last) {
                throw IllegalArgumentException("'$name' must be in ${range.first}..${range.last}")
            }
            return v.toInt()
        }

        fun boolField(body: Map<String, Any?>, name: String): Boolean =
            body[name] as? Boolean ?: throw IllegalArgumentException("'$name' must be true or false")

        fun stringField(body: Map<String, Any?>, name: String): String =
            body[name] as? String ?: throw IllegalArgumentException("'$name' must be a string")

        fun ok(result: Map<String, Any?>): HttpResponse = HttpResponse(200, Json.write(result))

        fun error(status: Int, message: String): HttpResponse =
            HttpResponse(status, Json.write(mapOf("error" to message)))
    }
}
