package com.pincatcher.core.capture.http

import java.io.ByteArrayOutputStream

/**
 * HTTP/1.1 message head parsing (PRD 6.1 "HTTP parser").
 *
 * Hand-rolled rather than pulled from a server framework: this sits in front of
 * a tun, not behind a socket, and the failure modes that matter here are
 * different. A peer can send a head we would rather truncate than trust, and the
 * parser must never block waiting for bytes that may not come.
 *
 * Deliberately not a general HTTP library. No chunked *decoding* beyond framing,
 * no multipart, no HTTP/2 - those land with the body viewer, not with framing.
 */
object HttpHead {

    const val MAX_HEAD_BYTES = 64 * 1024
    const val MAX_LINE = 8 * 1024
    const val MAX_HEADERS = 200

    data class Headers(private val list: List<Pair<String, String>>) {
        /** Case-insensitive, as RFC 9110 requires for field names. */
        fun first(name: String): String? =
            list.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        fun all(name: String): List<String> =
            list.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

        fun asList(): List<Pair<String, String>> = list
    }

    /**
     * What both kinds of head have in common.
     *
     * `bodyLength` and `isChunked` are on the interface rather than only on the
     * subclasses because the reassembler asks those two questions of every head
     * it parses without caring which kind it is, and a caller forced to branch on
     * the subtype to ask will eventually forget the branch.
     */
    sealed interface Head {
        val headers: Headers

        /** Declared body size, or -1 when the body is chunked or unstated. */
        val bodyLength: Int

        val isChunked: Boolean

        data class Request(
            val method: String,
            val target: String,
            val version: String,
            override val headers: Headers,
            override val bodyLength: Int,
            val headBytes: Int,
            override val isChunked: Boolean,
        ) : Head

        data class Response(
            val version: String,
            val status: Int,
            val reason: String,
            override val headers: Headers,
            override val bodyLength: Int,
            val headBytes: Int,
            override val isChunked: Boolean,
        ) : Head
    }

    /**
     * Returns null when the head is not yet complete. Callers must keep
     * buffering rather than treating null as an error - a head legitimately
     * arrives in pieces.
     */
    fun parseRequest(bytes: ByteArray, length: Int = bytes.size): Head.Request? {
        val end = findHeadEnd(bytes, length) ?: return null
        val text = String(bytes, 0, end, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        if (lines.isEmpty()) return null

        val requestLine = lines.first().split(' ')
        if (requestLine.size < 3) return null
        val method = requestLine[0]
        if (method.isEmpty() || !method.all { it.isLetter() || it == '-' }) return null
        if (requestLine[1].isEmpty() || requestLine[1].any { it <= ' ' }) return null

        val headers = parseHeaders(lines.drop(1)) ?: return null
        val chunked = headers.first("Transfer-Encoding")
            ?.split(',')
            ?.any { it.trim().equals("chunked", ignoreCase = true) } == true

        return Head.Request(
            method = method,
            target = requestLine[1],
            version = requestLine[2],
            headers = headers,
            bodyLength = contentLength(headers, chunked),
            headBytes = end,
            isChunked = chunked,
        )
    }

    fun parseResponse(bytes: ByteArray, length: Int = bytes.size): Head.Response? {
        val end = findHeadEnd(bytes, length) ?: return null
        val text = String(bytes, 0, end, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val statusLine = lines.firstOrNull()?.split(' ', limit = 3) ?: return null
        if (statusLine.size < 2) return null
        val status = statusLine[1].toIntOrNull() ?: return null
        if (status !in 100..599) return null

        val headers = parseHeaders(lines.drop(1)) ?: return null
        val chunked = headers.first("Transfer-Encoding")
            ?.split(',')
            ?.any { it.trim().equals("chunked", ignoreCase = true) } == true

        return Head.Response(
            version = statusLine[0],
            status = status,
            reason = statusLine.getOrElse(2) { "" },
            headers = headers,
            bodyLength = contentLength(headers, chunked),
            headBytes = end,
            isChunked = chunked,
        )
    }

    private fun contentLength(headers: Headers, chunked: Boolean): Int {
        // Chunked wins: a message may carry a stale Content-Length from a proxy
        // that re-chunked it, and believing that length desynchronises the stream.
        if (chunked) return -1
        return headers.first("Content-Length")?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: -1
    }

    /** Byte offset just past the blank line, or null if the head is incomplete. */
    fun findHeadEnd(bytes: ByteArray, length: Int): Int? {
        var i = 0
        while (i + 3 < length) {
            if (bytes[i] == CR && bytes[i + 1] == LF && bytes[i + 2] == CR && bytes[i + 3] == LF) {
                return i + 4
            }
            i++
        }
        return null
    }

    private fun parseHeaders(lines: List<String>): Headers? {
        if (lines.size > MAX_HEADERS) return null
        val out = ArrayList<Pair<String, String>>(lines.size)
        for (line in lines) {
            if (line.isEmpty()) continue
            if (line.length > MAX_LINE) return null
            // Obsolete line folding: a continuation starting with space. Refused
            // rather than joined, because folded values are a smuggling vector.
            if (line[0] == ' ' || line[0] == '\t') return null
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val name = line.substring(0, colon)
            if (name.any { it <= ' ' || it == ':' }) return null
            out += name to line.substring(colon + 1).trim()
        }
        return Headers(out)
    }

    private const val CR: Byte = 13
    private const val LF: Byte = 10

    // ---- chunked framing ----

    /**
     * Splits a chunked body into its payloads.
     *
     * Returns null when the framing is incomplete, so the caller can wait for
     * more bytes instead of treating a partial body as a parse error.
     */
    fun decodeChunked(bytes: ByteArray, offset: Int, length: Int): ChunkedResult? {
        val out = ByteArrayOutputStream()
        var i = offset
        val end = offset + length

        while (true) {
            val lineEnd = indexOfCrLf(bytes, i, end) ?: return null
            val sizeLine = String(bytes, i, lineEnd - i, Charsets.ISO_8859_1)
            // A chunk extension after ';' is legal and ignored.
            val sizeText = sizeLine.substringBefore(';').trim()
            val size = sizeText.toIntOrNull(16) ?: return null
            if (size < 0) return null

            i = lineEnd + 2
            if (size == 0) {
                // Trailer section, then the final CRLF. We do not surface
                // trailers; accepting them keeps the stream in sync.
                while (true) {
                    val trailerEnd = indexOfCrLf(bytes, i, end) ?: return null
                    if (trailerEnd == i) return ChunkedResult(out.toByteArray(), trailerEnd + 2)
                    i = trailerEnd + 2
                }
            }
            if (i + size + 2 > end) return null
            out.write(bytes, i, size)
            i += size
            if (i + 2 > end || bytes[i] != CR || bytes[i + 1] != LF) return null
            i += 2
        }
    }

    data class ChunkedResult(val body: ByteArray, val consumedBytes: Int)

    private fun indexOfCrLf(bytes: ByteArray, from: Int, end: Int): Int? {
        var i = from
        while (i + 1 < end) {
            if (bytes[i] == CR && bytes[i + 1] == LF) return i
            i++
        }
        return null
    }

    /** Does this request target start with `https://`? Drives the CONNECT decision. */
    fun isAbsoluteForm(target: String): Boolean =
        target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)

    /**
     * The host:port an absolute-form or CONNECT target refers to.
     *
     * The scheme decides the default port. Routing `https://host` to 80 is not
     * a fallback that works - it produces a plaintext connection to a TLS port
     * and the proxy reads nothing useful.
     */
    fun authorityOf(target: String): Pair<String, Int>? {
        val https = target.startsWith("https://", ignoreCase = true)
        val withoutScheme = target.substringAfter("://", target)
        val authority = withoutScheme.substringBefore('/').substringBefore('?')
        if (authority.isEmpty()) return null
        val colon = authority.lastIndexOf(':')
        // A colon inside brackets is an IPv6 literal, not a port separator.
        if (colon <= authority.lastIndexOf(']')) {
            return authority to if (https) DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
        }
        val port = authority.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return authority.substring(0, colon) to port
    }

    const val DEFAULT_HTTP_PORT = 80
    const val DEFAULT_HTTPS_PORT = 443
}