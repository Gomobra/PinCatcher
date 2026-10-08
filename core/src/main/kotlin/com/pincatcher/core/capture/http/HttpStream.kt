package com.pincatcher.core.capture.http

/**
 * Reassembles HTTP/1.1 messages from a raw byte tap (PRD 6.1 "HTTP parser").
 *
 * ## The problem
 *
 * The tunnel sees TCP payload, not messages. Turning that back into requests and
 * responses needs three things the tap cannot be asked for:
 *  - where the head ends - [HttpHead.findHeadEnd] scans for CRLFCRLF;
 *  - how the body is delimited - `Content-Length`, `Transfer-Encoding: chunked`,
 *    or neither, in which case the body ends only when the connection does;
 *  - whether another message follows - keep-alive makes one TCP connection carry
 *    many exchanges.
 *
 * So bytes are buffered until the head is complete, then the body is buffered
 * until its stated end is reached. Only then is anything handed out.
 *
 * ## The pairing rule
 *
 * This class pairs requests with responses rather than leaving it to the caller:
 * HTTP/1.1 responses arrive in request order, so a response belongs to the oldest
 * request still waiting. A response with no unmatched request is dropped, because
 * a status line on its own does not say what it is a response to.
 *
 * ## What is refused
 *
 * A body past [maxBuffered] is dropped whole rather than truncated. A truncated
 * JSON document stored as if it were complete is worse than a missing row,
 * because it looks like the server sent it that way.
 *
 * Once a head fails to parse the connection is marked dead and nothing further is
 * taken from it: guessing where the next message starts is how a capture ends up
 * with requests that were never made.
 *
 * ## Not here
 *
 * Body decoding, WebSocket framing, and HTTP/2. Those belong to the viewer, not to
 * reassembly, and each needs its own answer to when a message is done.
 */
class HttpStream(
    private val maxBuffered: Int = DEFAULT_MAX_BUFFERED,
) {

    /** One request, its response, and the bodies of both. */
    data class Transaction(
        val request: HttpHead.Head.Request,
        val requestBody: ByteArray,
        val response: HttpHead.Head.Response,
        val responseBody: ByteArray,
    ) {
        // ByteArray fields, so the generated identity equality would be wrong.
        override fun equals(other: Any?): Boolean = other is Transaction &&
            request == other.request &&
            response == other.response &&
            requestBody.contentEquals(other.requestBody) &&
            responseBody.contentEquals(other.responseBody)

        override fun hashCode(): Int {
            var h = request.hashCode()
            h = 31 * h + response.hashCode()
            h = 31 * h + requestBody.contentHashCode()
            h = 31 * h + responseBody.contentHashCode()
            return h
        }
    }

    /** One direction's framing state. */
    private class Direction {
        /**
         * Everything seen on this direction that has not been framed out yet.
         *
         * This is the stream buffer, not the message buffer: it holds the tail of
         * the current message *and* the beginning of the next one. So it is never
         * cleared between messages - only at close.
         */
        val pending = Accumulator()
        var head: HttpHead.Head? = null

        /** Sticky for the life of the connection once a head fails to parse. */
        var dead = false

        fun resetMessage() {
            head = null
        }
    }

    private val toServer = Direction()
    private val toClient = Direction()
    private val unmatched = ArrayDeque<Pair<HttpHead.Head.Request, ByteArray>>()

    /** Feeds client-to-server bytes. Returns every transaction that completed. */
    fun onClientBytes(bytes: ByteArray, length: Int = bytes.size): List<Transaction> =
        consume(toServer, bytes, length, isRequest = true)

    /** Feeds server-to-client bytes. */
    fun onServerBytes(bytes: ByteArray, length: Int = bytes.size): List<Transaction> =
        consume(toClient, bytes, length, isRequest = false)

    /**
     * Ends the connection.
     *
     * A body with no stated length is only finished now, so the last exchange is
     * emitted here. A half-seen head is discarded: a request we only partly
     * observed did not happen as far as the record is concerned.
     */
    fun onClosed(): List<Transaction> {
        val leftover = mutableListOf<Pair<HttpHead.Head, ByteArray>>()
        listOf(toServer, toClient).forEach { direction ->
            val head = direction.head
            // A body with no stated length is only finished now. A half-seen head
            // is discarded: a request we only partly observed did not happen as
            // far as the record is concerned.
            if (head != null && !direction.dead) leftover += head to direction.pending.bytes()
            direction.pending.reset()
            direction.resetMessage()
        }
        // Requests still waiting, then the responses that answer them.
        pair(leftover.filter { it.first is HttpHead.Head.Request }, isRequest = true)
        return pair(leftover.filter { it.first is HttpHead.Head.Response }, isRequest = false)
    }

    /**
     * Frames whatever messages [bytes] completes, in order.
     *
     * There is exactly one buffer per direction. A head and the body after it are
     * both framed out of it, and each message takes precisely what it declared -
     * never the whole tail, because on a keep-alive connection that tail belongs
     * to the next exchange and swallowing it merges two responses into one body.
     */
    private fun consume(
        direction: Direction,
        bytes: ByteArray,
        length: Int,
        isRequest: Boolean,
    ): List<Transaction> {
        if (direction.dead) return emptyList()
        direction.pending.append(bytes, length)
        val finished = mutableListOf<Pair<HttpHead.Head, ByteArray>>()

        while (true) {
            if (direction.head == null) {
                val end = HttpHead.findHeadEnd(direction.pending.bytes(), direction.pending.size())
                if (end == null) {
                    if (direction.pending.size() > HttpHead.MAX_HEAD_BYTES) direction.dead = true
                    break
                }
                val raw = direction.pending.bytes().copyOf(end)
                direction.pending.consume(end)
                val parsed: HttpHead.Head? = if (isRequest) {
                    HttpHead.parseRequest(raw)
                } else {
                    HttpHead.parseResponse(raw)
                }
                if (parsed == null) {
                    direction.dead = true
                    break
                }
                direction.head = parsed
            }

            val taken = takeBody(direction) ?: break
            finished += direction.head!! to taken
            direction.resetMessage()
        }

        if (direction.pending.size() > maxBuffered) direction.dead = true
        return pair(finished, isRequest)
    }

    /**
     * The whole body if it has arrived, taking exactly that much out of the
     * buffer. Null when it has not, or when the body runs to end-of-connection -
     * that one is only finished at close.
     *
     * Chunked bodies are decoded here, so what leaves the buffer and what gets
     * stored are the bytes the application would have seen, not the framing.
     */
    private fun takeBody(direction: Direction): ByteArray? {
        val head = direction.head ?: return null
        if (head.isChunked) {
            val decoded = HttpHead.decodeChunked(direction.pending.bytes(), 0, direction.pending.size())
                ?: return null
            direction.pending.consume(decoded.consumedBytes)
            return decoded.body
        }
        if (head.bodyLength < 0) return null
        if (direction.pending.size() < head.bodyLength) return null
        val body = direction.pending.bytes().copyOf(head.bodyLength)
        direction.pending.consume(head.bodyLength)
        return body
    }

    private fun pair(finished: List<Pair<HttpHead.Head, ByteArray>>, isRequest: Boolean): List<Transaction> {
        if (finished.isEmpty()) return emptyList()
        if (isRequest) {
            finished.forEach { (head, body) ->
                if (head is HttpHead.Head.Request) unmatched.addLast(head to body)
            }
            return emptyList()
        }
        val out = mutableListOf<Transaction>()
        finished.forEach { (head, body) ->
            if (head !is HttpHead.Head.Response) return@forEach
            val request = unmatched.removeFirstOrNull() ?: return@forEach
            out += Transaction(request.first, request.second, head, body)
        }
        return out
    }

    /**
     * Growable byte sink.
     *
     * Not `ByteArrayOutputStream`, because every append here is followed by a
     * re-scan for CRLFCRLF, and its `writeTo` copies the whole accumulated array
     * on each such scan.
     */
    private class Accumulator(initial: ByteArray = ByteArray(0)) {
        private var buffer: ByteArray = initial
        private var length: Int = initial.size

        fun bytes(): ByteArray = if (length == buffer.size) buffer else buffer.copyOf(length)
        fun size(): Int = length

        fun append(bytes: ByteArray, count: Int) {
            val needed = length + count
            if (needed > buffer.size) {
                var capacity = maxOf(64, buffer.size)
                while (capacity < needed) capacity *= 2
                buffer = buffer.copyOf(capacity)
            }
            bytes.copyInto(buffer, length, 0, count)
            length = needed
        }

        fun consume(count: Int) {
            if (count >= length) {
                reset()
                return
            }
            buffer.copyInto(buffer, 0, count, length)
            length -= count
        }

        fun reset() {
            length = 0
        }
    }

    companion object {
        const val DEFAULT_MAX_BUFFERED = 8 * 1024 * 1024
    }
}