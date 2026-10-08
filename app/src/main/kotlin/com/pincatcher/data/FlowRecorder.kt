package com.pincatcher.data

import com.pincatcher.core.capture.http.HttpHead
import com.pincatcher.core.capture.http.HttpStream
import java.security.MessageDigest

/**
 * Writes finished HTTP exchanges to the database.
 *
 * All framing happens in [HttpStream]; this class only maps a completed
 * transaction onto a `flows` row and stores the bodies. Keeping the two apart is
 * the point: the framing is the part with interesting failure modes and it is
 * unit-tested off-device, while this is a column mapping.
 */
class FlowRecorder(
    private val flowStore: FlowStore,
    private val bodyStore: BodyStore,
) {

    private val sequence = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * One live connection. Not thread-safe: the connection's pump thread owns it.
     *
     * ## Why the first bytes are sniffed
     *
     * A TLS connection starts with a handshake record: 0x16, 0x03, 0x0x. Without
     * this check each one buffers up to [HttpStream]'s head limit looking for a
     * CRLFCRLF that will never arrive, and a capture with a few hundred live
     * https connections holds tens of megabytes of handshake bytes it will throw
     * away. Three bytes is enough to know, and it is the reason this class can
     * hand out a tap per connection without knowing the port in advance - so
     * plaintext HTTP on a non-standard port is still recorded.
     */
    inner class Tap(private val sessionId: Long) {
        private val stream = HttpStream()
        private val startedAt = System.currentTimeMillis()
        private val startNanos = System.nanoTime()

        /** 0 until the first client byte arrives, then whether this is plaintext. */
        private var plaintext: Boolean? = null

        fun onClientBytes(bytes: ByteArray, length: Int) {
            if (plaintext == null) plaintext = !looksLikeTls(bytes, length)
            if (plaintext == false) return
            stream.onClientBytes(bytes, length).forEach(::record)
        }

        fun onServerBytes(bytes: ByteArray, length: Int) {
            if (plaintext == false) return
            stream.onServerBytes(bytes, length).forEach(::record)
        }

        fun onClosed() {
            if (plaintext == false) return
            stream.onClosed().forEach(::record)
        }

        private fun looksLikeTls(bytes: ByteArray, length: Int): Boolean {
            if (length < 3) return false
            val type = bytes[0].toInt() and 0xFF
            val major = bytes[1].toInt() and 0xFF
            return type == 0x16 && major == 0x03
        }

        private fun record(transaction: HttpStream.Transaction) {
            // One malformed row must not take down a capture that is otherwise fine.
            runCatching { write(transaction) }
        }

        private fun write(transaction: HttpStream.Transaction) {
            val request = transaction.request
            val response = transaction.response

            // The Host header is the only authority there is: in VPN mode the
            // tunnel sees an IP address, and the target is origin-form or CONNECT,
            // never absolute.
            val host = request.headers.first("Host")?.substringBefore(':')?.trim()
                ?: request.headers.first("Host")?.trim()
                ?: return
            val target = request.target
            val path = if (HttpHead.isAbsoluteForm(target)) {
                val afterScheme = target.substringAfter("://")
                afterScheme.substringAfter('/', "/")
            } else {
                target
            }
            val (pathOnly, query) = splitQuery(path.ifEmpty { "/" })

            flowStore.insert(
                Flow(
                    sessionId = sessionId,
                    seq = sequence.incrementAndGet(),
                    method = request.method,
                    // Plaintext at this layer. A decrypted https flow reports its
                    // real scheme; a relayed one is recorded as what it was.
                    scheme = "http",
                    host = host,
                    path = pathOnly,
                    query = query,
                    url = Flow.urlOf("http", host, pathOnly, query),
                    statusCode = response.status,
                    reqHeaders = render(request.headers),
                    resHeaders = render(response.headers),
                    reqBodyHash = hash(transaction.requestBody, request.headers.first("Content-Type")),
                    resBodyHash = hash(transaction.responseBody, response.headers.first("Content-Type")),
                    reqSize = transaction.requestBody.size.toLong(),
                    resSize = transaction.responseBody.size.toLong(),
                    startTime = startedAt,
                    endTime = System.currentTimeMillis(),
                    durationMs = (System.nanoTime() - startNanos) / 1_000_000,
                ),
            )
        }
    }

    private fun splitQuery(path: String): Pair<String, String?> {
        val at = path.indexOf('?')
        return if (at < 0) path to null else path.substring(0, at) to path.substring(at + 1)
    }

    /** Headers as text, for the detail view. Redundant fields are kept as sent. */
    private fun render(headers: HttpHead.Headers): String =
        headers.asList().joinToString("\n") { "${it.first}: ${it.second}" }

    /**
     * Stores a body and returns its hash.
     *
     * Bodies are stored exactly as they arrived, with `Content-Encoding` left in
     * the headers. Decompressing costs CPU on every response of a long capture,
     * and the bytes on the wire are what the app actually received; the viewer
     * decodes on demand.
     */
    private fun hash(body: ByteArray, contentType: String?): String? {
        if (body.isEmpty()) return null
        val hex = MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(it) }
        bodyStore.storeOrDeduplicate(
            BodyStore.Body(
                hash = hex,
                sizeRaw = body.size.toLong(),
                sizeStored = body.size.toLong(),
                encoding = null,
                contentType = contentType,
                refcount = 0,
                createdAt = System.currentTimeMillis(),
                filePath = null,
            ),
        )
        return hex
    }
}