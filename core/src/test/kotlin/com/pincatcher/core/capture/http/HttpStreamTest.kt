package com.pincatcher.core.capture.http

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Framing tests for [HttpStream].
 *
 * The cases that matter are the awkward ones: a head split across TCP segments, a
 * body and the next message in one read, and a body whose length is never stated.
 * All three happen on the first real capture, so they are checked here rather
 * than discovered on a device.
 */
class HttpStreamTest {

    private fun text(s: String) = s.encodeToByteArray()

    private fun request(body: String = ""): ByteArray = text(
        "POST /v1/login HTTP/1.1\r\n" +
            "Host: api.example.com\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "\r\n$body",
    )

    private fun response(body: String, chunked: Boolean = false): ByteArray = if (chunked) {
        text("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n" + chunked(body))
    } else {
        text("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body")
    }

    /** Wraps [body] in chunked framing, terminator included. */
    private fun chunked(body: String, size: Int = 3): String {
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val end = minOf(i + size, body.length)
            sb.append(Integer.toHexString(end - i)).append("\r\n")
                .append(body, i, end).append("\r\n")
            i = end
        }
        return sb.append("0\r\n\r\n").toString()
    }

    // ---- head framing ----

    @Test
    fun `a head split across reads is reassembled`() {
        val stream = HttpStream()
        val whole = request(body = "{\"a\":1}")
        // Cut inside the blank line that ends the head, which is exactly where a
        // real segment boundary tends to land.
        val cut = whole.size - 6
        assertTrue(stream.onClientBytes(whole.copyOfRange(0, cut)).isEmpty())
        stream.onClientBytes(whole.copyOfRange(cut, whole.size))

        val done = stream.onServerBytes(response("ok"))
        assertEquals(1, done.size)
        assertEquals("POST", done.single().request.method)
        assertArrayEquals("{\"a\":1}".encodeToByteArray(), done.single().requestBody)
    }

    @Test
    fun `a request and its response pair up`() {
        val stream = HttpStream()
        stream.onClientBytes(request(body = "{\"a\":1}"))
        val done = stream.onServerBytes(response("{\"ok\":true}"))
        assertEquals(1, done.size)
        val t = done.single()
        assertEquals("/v1/login", t.request.target)
        assertEquals(200, t.response.status)
        assertEquals("api.example.com", t.request.headers.first("Host"))
        assertArrayEquals("{\"ok\":true}".encodeToByteArray(), t.responseBody)
    }

    @Test
    fun `keep alive yields one transaction per exchange`() {
        val stream = HttpStream()
        stream.onClientBytes(request(body = "1"))
        assertEquals(1, stream.onServerBytes(response("a")).size)
        stream.onClientBytes(request(body = "2"))
        val done = stream.onServerBytes(response("b"))
        assertEquals(1, done.size)
        assertEquals("2", String(done.single().requestBody))
        assertEquals("b", String(done.single().responseBody))
    }

    @Test
    fun `pipelined responses arriving in one read are all paired`() {
        val stream = HttpStream()
        stream.onClientBytes(request(body = "1"))
        stream.onClientBytes(request(body = "2"))
        val done = stream.onServerBytes(response("a") + response("b"))
        assertEquals(2, done.size, "two requests, so two responses must both come back")
        assertEquals("a", String(done[0].responseBody))
        assertEquals("1", String(done[0].requestBody))
        assertEquals("b", String(done[1].responseBody))
        assertEquals("2", String(done[1].requestBody))
    }

    // ---- body framing ----

    @Test
    fun `a chunked body is stored decoded`() {
        val stream = HttpStream()
        stream.onClientBytes(request(body = "x"))
        val done = stream.onServerBytes(response("hello world", chunked = true))
        assertEquals("hello world", String(done.single().responseBody))
    }

    @Test
    fun `a partial chunked body waits for more`() {
        val stream = HttpStream()
        stream.onClientBytes(request(body = "x"))
        val framed = response("abcdefghij", chunked = true)
        val half = framed.copyOfRange(0, framed.size - 6)
        assertTrue(stream.onServerBytes(half).isEmpty(), "an unfinished chunk is not a message")
        val done = stream.onServerBytes(framed.copyOfRange(framed.size - 6, framed.size))
        assertEquals("abcdefghij", String(done.single().responseBody))
    }

    @Test
    fun `a chunked body split across seven reads is reassembled`() {
        val stream = HttpStream()
        stream.onClientBytes(request(body = "x"))
        val framed = response("the quick brown fox", chunked = true)
        val collected = mutableListOf<HttpStream.Transaction>()
        // Uneven slices, so chunk headers and payloads both straddle a boundary.
        var from = 0
        while (from < framed.size) {
            collected += stream.onServerBytes(framed.copyOfRange(from, minOf(from + 7, framed.size)))
            from += 7
        }
        assertEquals(1, collected.size)
        assertEquals("the quick brown fox", String(collected.single().responseBody))
    }

    @Test
    fun `a body with no stated length is emitted at close`() {
        val stream = HttpStream()
        stream.onClientBytes(text("GET /x HTTP/1.1\r\nHost: a.example\r\n\r\n"))
        assertTrue(
            stream.onServerBytes(text("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nbody")).isEmpty(),
            "a response with no stated length cannot be finished while the socket is open",
        )
        val done = stream.onClosed()
        assertEquals(1, done.size)
        assertEquals("body", String(done.single().responseBody))
        assertEquals("/x", done.single().request.target)
    }

    @Test
    fun `a body past the budget is dropped rather than truncated`() {
        val stream = HttpStream(maxBuffered = 64)
        stream.onClientBytes(request(body = "x"))
        val declared = text("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n" + "z".repeat(200))
        assertTrue(stream.onServerBytes(declared).isEmpty())
        assertTrue(
            stream.onClosed().isEmpty(),
            "an oversized body must not be recorded as a short one",
        )
    }

    // ---- refusals ----

    @Test
    fun `a tls client hello is not mistaken for http`() {
        val stream = HttpStream()
        // 0x16 0x03 0x01 is a TLS handshake record and contains no CRLFCRLF.
        val hello = ByteArray(512)
        hello[0] = 0x16.toByte(); hello[1] = 0x03; hello[2] = 0x01
        assertTrue(stream.onClientBytes(hello).isEmpty())
        stream.onClientBytes(text("GET / HTTP/1.1\r\nHost: x\r\n\r\n"))
        assertTrue(
            stream.onServerBytes(text("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")).isEmpty(),
            "after a head fails to parse, nothing further on that connection is trusted",
        )
    }

    @Test
    fun `a response with no matching request is dropped`() {
        val stream = HttpStream()
        assertTrue(stream.onServerBytes(response("orphan")).isEmpty())
    }

    @Test
    fun `an oversized head is refused rather than buffered`() {
        val stream = HttpStream()
        assertTrue(stream.onClientBytes(ByteArray(HttpHead.MAX_HEAD_BYTES + 16) { 'x'.code.toByte() }).isEmpty())
        assertTrue(
            stream.onServerBytes(text("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")).isEmpty(),
        )
    }
}