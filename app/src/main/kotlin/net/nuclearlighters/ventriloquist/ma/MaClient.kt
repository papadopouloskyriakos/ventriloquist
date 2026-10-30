package net.nuclearlighters.ventriloquist.ma

import net.nuclearlighters.ventriloquist.util.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/** A player or group as the settings screen lists it. */
data class MaPlayer(val id: String, val name: String, val group: Boolean, val available: Boolean)

/** The Music Assistant commands house mode uses. */
interface MaApi {
    fun playMedia(queueId: String, media: String)

    fun stop(playerId: String)

    /** The level (0..100) a group plays at, or a single player's volume; null when unknown. */
    fun volume(playerId: String): Int?

    /** Sets the level of a group as a whole (every member scaled), or of a single player. */
    fun setGroupVolume(playerId: String, level: Int)

    /** Every enabled, visible player and group. */
    fun players(): List<MaPlayer>
}

/** A Music Assistant call that failed: transport, HTTP status or an error object. */
class MaException(message: String) : IOException(message)

/**
 * Music Assistant's HTTP API: `POST {base}/api` with a bearer token and
 * `{"message_id", "command", "args"}`; the reply body is the command's result itself. A
 * status other than 200, or a JSON object carrying `error_code`, is a failure.
 */
class MaClient(
    baseUrl: String,
    private val token: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 20_000,
) : MaApi {
    private val endpoint = URL(baseUrl.trimEnd('/') + "/api")

    fun call(command: String, args: Map<String, Any?>): Any? {
        val body = Json.write(
            linkedMapOf("message_id" to UUID.randomUUID().toString(), "command" to command, "args" to args),
        ).toByteArray(StandardCharsets.UTF_8)
        val conn = endpoint.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            // For a 401 the JDK consumes the body itself (authentication handling), so the
            // error stream can be null; the reason phrase is then all there is.
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { String(it.readBytes(), StandardCharsets.UTF_8) } ?: ""
            if (status != 200) {
                val detail = text.trim().ifEmpty { conn.responseMessage ?: "" }.take(200)
                throw MaException("$command: HTTP $status $detail".trim())
            }
            val result = if (text.isBlank()) null else Json.parse(text)
            if (result is Map<*, *> && result.containsKey("error_code")) {
                val detail = result["details"] ?: result["error"] ?: ""
                throw MaException("$command: error ${result["error_code"]} $detail".trim())
            }
            return result
        } catch (e: MaException) {
            throw e
        } catch (e: IOException) {
            throw MaException("$command: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw MaException("$command: unreadable reply: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    override fun playMedia(queueId: String, media: String) {
        call("player_queues/play_media", mapOf("queue_id" to queueId, "media" to media, "option" to "replace"))
    }

    override fun stop(playerId: String) {
        call("players/cmd/stop", mapOf("player_id" to playerId))
    }

    override fun volume(playerId: String): Int? {
        val p = call("players/get", mapOf("player_id" to playerId)) as? Map<*, *>
            ?: throw MaException("players/get: reply is not an object")
        val v = if (p["type"] == "group") p["group_volume"] else p["volume_level"] ?: p["group_volume"]
        return (v as? Number)?.toInt()
    }

    override fun setGroupVolume(playerId: String, level: Int) {
        call("players/cmd/group_volume", mapOf("player_id" to playerId, "volume_level" to level.coerceIn(0, 100)))
    }

    override fun players(): List<MaPlayer> {
        val all = call("players/all", emptyMap()) as? List<*>
            ?: throw MaException("players/all: reply is not a list")
        return all.mapNotNull { item ->
            val p = item as? Map<*, *> ?: return@mapNotNull null
            val id = p["player_id"] as? String ?: return@mapNotNull null
            if (p["enabled"] == false || p["hidden"] == true) return@mapNotNull null
            val name = (p["display_name"] as? String)?.takeIf { it.isNotBlank() } ?: p["name"] as? String ?: id
            MaPlayer(id, name, p["type"] == "group", p["available"] != false)
        }.sortedWith(compareBy({ !it.group }, { it.name.lowercase() }))
    }
}
