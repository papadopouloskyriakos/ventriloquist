package net.nuclearlighters.ventriloquist.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpParserTest {

    private fun parse(text: String) = HttpParser.parse(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)))

    private fun status(text: String): Int =
        assertThrows(HttpError::class.java) { parse(text) }.status

    @Test
    fun parsesAPostWithBody() {
        val r = parse(
            "POST /latency?x=1&y=a%20b HTTP/1.1\r\nHost: tv\r\nAuthorization: Bearer abc\r\n" +
                "Content-Type: application/json\r\nContent-Length: 10\r\n\r\n{\"ms\":120}",
        )!!
        assertEquals("POST", r.method)
        assertEquals("/latency", r.path)
        assertEquals(mapOf("x" to "1", "y" to "a b"), r.query)
        assertEquals("Bearer abc", r.header("AUTHORIZATION"))
        assertEquals("{\"ms\":120}", r.bodyText)
    }

    @Test
    fun parsesAGetWithBareLineFeeds() {
        val r = parse("GET /status HTTP/1.0\nHost: tv\n\n")!!
        assertEquals("GET", r.method)
        assertEquals("/status", r.path)
        assertEquals(0, r.body.size)
    }

    @Test
    fun emptyStreamIsNoRequest() {
        assertNull(parse(""))
    }

    @Test
    fun joinsRepeatedHeaders() {
        val r = parse("GET / HTTP/1.1\r\nX-A: 1\r\nx-a: 2\r\n\r\n")!!
        assertEquals("1, 2", r.header("x-a"))
    }

    @Test
    fun rejectsMalformedRequests() {
        assertEquals(400, status("GET /status\r\n\r\n"))
        assertEquals(400, status("get /status HTTP/1.1\r\n\r\n"))
        assertEquals(505, status("GET /status HTTP/2.0\r\n\r\n"))
        assertEquals(400, status("GET http://x/ HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nNoColon\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nA: 1\r\n folded\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: tv\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 5\r\n\r\nabc"))
        assertEquals(501, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
    }

    @Test
    fun enforcesSizeLimits() {
        assertEquals(413, status("POST / HTTP/1.1\r\nContent-Length: 70000\r\n\r\n"))
        assertEquals(431, status("GET / HTTP/1.1\r\nX: " + "a".repeat(9000) + "\r\n\r\n"))
    }

    @Test
    fun writesAResponse() {
        val out = ByteArrayOutputStream()
        HttpResponse(401, "{\"error\":\"é\"}", extraHeaders = mapOf("WWW-Authenticate" to "Bearer")).writeTo(out)
        val text = String(out.toByteArray(), Charsets.UTF_8)
        assertTrue(text.startsWith("HTTP/1.1 401 Unauthorized\r\n"))
        assertTrue(text.contains("Content-Length: 14\r\n"))
        assertTrue(text.contains("WWW-Authenticate: Bearer\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{\"error\":\"é\"}"))
        assertArrayEquals("{\"error\":\"é\"}".toByteArray(), out.toByteArray().takeLast(14).toByteArray())
    }
}
