package net.nuclearlighters.ventriloquist.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A parsed HTTP/1.x request. Header names are lower case. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val bodyText: String get() = String(body, StandardCharsets.UTF_8)
}

/** A request that cannot be served; [status] is the HTTP status to answer with. */
class HttpError(val status: Int, message: String) : Exception(message)

class HttpResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json",
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    fun writeTo(out: OutputStream) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            .append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n")
            .append("Content-Length: ").append(bytes.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
        for ((k, v) in extraHeaders) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    companion object {
        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            505 -> "HTTP Version Not Supported"
            else -> "Status"
        }
    }
}

/**
 * Strict, bounded HTTP/1.0 and 1.1 request parser: one request per connection, no chunked
 * bodies, a Content-Length for any body, header block and body size capped.
 */
object HttpParser {
    const val MAX_HEADER_BYTES = 8 * 1024
    const val MAX_BODY_BYTES = 64 * 1024

    private val METHOD = Regex("[A-Z]{1,16}")
    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    /** Parses one request; returns null when the stream ends before the first byte. */
    fun parse(
        input: InputStream,
        maxHeaderBytes: Int = MAX_HEADER_BYTES,
        maxBodyBytes: Int = MAX_BODY_BYTES,
    ): HttpRequest? {
        val head = readHead(input, maxHeaderBytes) ?: return null
        val lines = head.split('\n').map { it.removeSuffix("\r") }
        val requestLine = lines.first()
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw HttpError(400, "malformed request line")
        val (method, target, version) = parts
        if (!METHOD.matches(method)) throw HttpError(400, "malformed method")
        if (version != "HTTP/1.1" && version != "HTTP/1.0") {
            throw HttpError(if (version.startsWith("HTTP/")) 505 else 400, "unsupported version")
        }
        if (!target.startsWith("/")) throw HttpError(400, "request target must be a path")

        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            if (line[0] == ' ' || line[0] == '\t') throw HttpError(400, "folded headers not supported")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "malformed header")
            val name = line.substring(0, colon)
            if (!TOKEN.matches(name)) throw HttpError(400, "malformed header name")
            val value = line.substring(colon + 1).trim()
            val key = name.lowercase()
            headers[key] = headers[key]?.let { "$it, $value" } ?: value
        }
        if (headers.containsKey("transfer-encoding")) throw HttpError(501, "chunked bodies not supported")

        val body: ByteArray = when (val lengthHeader = headers["content-length"]) {
            null -> ByteArray(0)
            else -> {
                if (lengthHeader.isEmpty() || !lengthHeader.all { it in '0'..'9' } || lengthHeader.length > 9) {
                    throw HttpError(400, "bad Content-Length")
                }
                val length = lengthHeader.toInt()
                if (length > maxBodyBytes) throw HttpError(413, "body larger than $maxBodyBytes bytes")
                readBody(input, length)
            }
        }
        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        val query = if (q < 0) emptyMap() else parseQuery(target.substring(q + 1))
        return HttpRequest(method, path, query, headers, body)
    }

    private fun readHead(input: InputStream, max: Int): String? {
        val out = ByteArrayOutputStream(512)
        var last = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (out.size() == 0) return null
                throw HttpError(400, "truncated request head")
            }
            if (out.size() == 0 && (b == '\r'.code || b == '\n'.code)) continue // stray CRLF before a request
            out.write(b)
            if (out.size() > max) throw HttpError(431, "request head larger than $max bytes")
            last = (last shl 8) or b
            val crlf2 = last == 0x0d0a0d0a
            val lf2 = (last and 0xffff) == 0x0a0a
            if (crlf2 || lf2) {
                val bytes = out.toByteArray()
                val cut = if (crlf2) 4 else 2
                return String(bytes, 0, bytes.size - cut, StandardCharsets.ISO_8859_1)
            }
        }
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw HttpError(400, "truncated body")
            read += n
        }
        return body
    }

    private fun parseQuery(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (pair in text.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq < 0) pair else pair.substring(0, eq)
            val v = if (eq < 0) "" else pair.substring(eq + 1)
            try {
                result[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (e: IllegalArgumentException) {
                throw HttpError(400, "bad query encoding")
            }
        }
        return result
    }
}
