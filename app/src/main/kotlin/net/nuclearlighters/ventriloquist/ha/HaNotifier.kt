package net.nuclearlighters.ventriloquist.ha

import net.nuclearlighters.ventriloquist.util.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Tells a Home Assistant webhook that house mode started or stopped, so an automation there
 * can mute the TV and unmute it again. A webhook URL carries its own credential (the webhook
 * id), so no token is sent. Best effort: Home Assistant should also poll `GET /status`.
 */
class HaNotifier(
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000,
) {
    /** The last failure, for the status page; null after a success. */
    @Volatile
    var lastError: String? = null
        private set

    /** POSTs [event] as JSON to [url]; true on a 2xx answer. Never throws. */
    fun post(url: String, event: Map<String, Any?>): Boolean {
        if (url.isEmpty()) return true
        val body = Json.write(event).toByteArray(StandardCharsets.UTF_8)
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.doOutput = true
                conn.useCaches = false
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                val status = conn.responseCode
                if (status in 200..299) {
                    lastError = null
                    true
                } else {
                    lastError = "webhook: HTTP $status"
                    false
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            lastError = "webhook: ${e.javaClass.simpleName}: ${e.message}"
            false
        } catch (e: IllegalArgumentException) {
            lastError = "webhook: ${e.message}"
            false
        }
    }
}
