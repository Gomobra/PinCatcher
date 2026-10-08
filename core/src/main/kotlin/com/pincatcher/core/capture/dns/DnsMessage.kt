package com.pincatcher.core.capture.dns

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Just enough DNS to answer queries from inside the tunnel.
 *
 * Why the tunnel has to handle DNS at all: once every packet from the target
 * app is routed into our tun, its queries to port 53 arrive here too. We either
 * answer them or forward them, and we cannot simply ignore them - the app
 * cannot resolve anything and appears to have no network at all.
 *
 * ## The loop this creates
 *
 * To forward a query we open a UDP socket. If that socket is not `protect()`ed,
 * the kernel routes it back into our own tun, the query arrives at this class
 * again, and we forward it again, forever. That is why [DnsMessage] and the
 * resolver are pure and the socket handling lives in the Android layer with
 * `protect()` on it.
 */
object Dns {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_CNAME = 5
    const val CLASS_IN = 1

    const val HEADER_LEN = 12
    const val FLAG_RESPONSE = 0x8000
    const val FLAG_TRUNCATED = 0x0200
    const val FLAG_RCODE_MASK = 0x000F

    const val RCODE_NOERROR = 0
    const val RCODE_FORMERR = 1
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    const val RCODE_NOTIMP = 4

    data class Question(val name: String, val type: Int, val clazz: Int = CLASS_IN)

    data class Message(
        val id: Int,
        val flags: Int,
        val questions: List<Question>,
        val answers: List<Answer>,
    ) {
        val isResponse: Boolean get() = flags and FLAG_RESPONSE != 0
        val rcode: Int get() = flags and FLAG_RCODE_MASK
        val truncated: Boolean get() = flags and FLAG_TRUNCATED != 0
        val primaryQuestion: Question? get() = questions.firstOrNull()
    }

    data class Answer(val name: String, val type: Int, val clazz: Int, val ttl: Int, val data: ByteArray) {
        // ByteArray in a data class gives reference equality, which silently
        // breaks every comparison. Compare content explicitly.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Answer && name == other.name && type == other.type &&
                    clazz == other.clazz && ttl == other.ttl && data.contentEquals(other.data))

        override fun hashCode(): Int {
            var h = name.hashCode()
            h = 31 * h + type
            h = 31 * h + clazz
            h = 31 * h + ttl
            h = 31 * h + data.contentHashCode()
            return h
        }
    }

    /** Longest name we will accept, per RFC 1035's 255-byte wire limit. */
    private const val MAX_NAME = 255

    fun parse(bytes: ByteArray, length: Int = bytes.size): Message? {
        if (length < HEADER_LEN) return null
        val buffer = ByteBuffer.wrap(bytes, 0, length)

        val id = buffer.short.toInt() and 0xFFFF
        val flags = buffer.short.toInt() and 0xFFFF
        val qdCount = buffer.short.toInt() and 0xFFFF
        val anCount = buffer.short.toInt() and 0xFFFF
        // Authority and additional counts are parsed away, not just read: the
        // section contents are not decoded, but the cursor still has to cross
        // the four bytes or the first question name is read from the wrong
        // offset and every name comes back empty.
        buffer.short
        buffer.short

        val questions = ArrayList<Question>(qdCount.coerceAtMost(MAX_RECORDS))
        repeat(qdCount.coerceAtMost(MAX_RECORDS)) {
            val name = readName(buffer) ?: return null
            if (buffer.remaining() < 4) return null
            val type = buffer.short.toInt() and 0xFFFF
            val clazz = buffer.short.toInt() and 0xFFFF
            questions += Question(name, type, clazz)
        }

        val answers = ArrayList<Answer>(anCount.coerceAtMost(MAX_RECORDS))
        repeat(anCount.coerceAtMost(MAX_RECORDS)) {
            val name = readName(buffer) ?: return null
            if (buffer.remaining() < 10) return null
            val type = buffer.short.toInt() and 0xFFFF
            val clazz = buffer.short.toInt() and 0xFFFF
            val ttl = buffer.int
            val dataLength = buffer.short.toInt() and 0xFFFF
            if (dataLength > buffer.remaining()) return null
            val data = ByteArray(dataLength)
            buffer.get(data)
            answers += Answer(name, type, clazz, ttl, data)
        }

        return Message(id, flags, questions, answers)
    }

    fun build(message: Message): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((message.id shr 8) and 0xFF)
        out.write(message.id and 0xFF)
        out.write((message.flags shr 8) and 0xFF)
        out.write(message.flags and 0xFF)
        out.write((message.questions.size shr 8) and 0xFF)
        out.write(message.questions.size and 0xFF)
        out.write((message.answers.size shr 8) and 0xFF)
        out.write(message.answers.size and 0xFF)
        out.write(0); out.write(0) // authority
        out.write(0); out.write(0) // additional

        message.questions.forEach { q ->
            writeName(out, q.name)
            out.write((q.type shr 8) and 0xFF); out.write(q.type and 0xFF)
            out.write((q.clazz shr 8) and 0xFF); out.write(q.clazz and 0xFF)
        }
        message.answers.forEach { a ->
            writeName(out, a.name)
            out.write((a.type shr 8) and 0xFF); out.write(a.type and 0xFF)
            out.write((a.clazz shr 8) and 0xFF); out.write(a.clazz and 0xFF)
            out.write((a.ttl shr 24) and 0xFF)
            out.write((a.ttl shr 16) and 0xFF)
            out.write((a.ttl shr 8) and 0xFF)
            out.write(a.ttl and 0xFF)
            out.write((a.data.size shr 8) and 0xFF); out.write(a.data.size and 0xFF)
            out.write(a.data)
        }
        return out.toByteArray()
    }

    /** A response carrying [rcode] and no answers. Used for NXDOMAIN and errors. */
    fun errorResponse(query: Message, rcode: Int): ByteArray = build(
        Message(
            id = query.id,
            flags = FLAG_RESPONSE or (rcode and FLAG_RCODE_MASK),
            questions = query.questions,
            answers = emptyList(),
        ),
    )

    fun ipv4Answer(address: Int): ByteArray = byteArrayOf(
        (address ushr 24).toByte(), (address ushr 16).toByte(),
        (address ushr 8).toByte(), address.toByte(),
    )

    fun ipv4From(bytes: ByteArray): Int? {
        if (bytes.size != 4) return null
        return (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    // ---- names ----

    /**
     * Decodes a wire name, following compression pointers.
     *
     * Two hardening decisions, both because the input is untrusted:
     *  - the returned name never has a trailing dot, so build/parse round trips
     *    are byte-identical rather than needing the caller to normalise;
     *  - every step re-checks the buffer bound. A pointer that aims outside the
     *    packet, or at itself, returns null instead of spinning or reading
     *    past the end.
     */
    private fun readName(buffer: ByteBuffer): String? {
        val sb = StringBuilder()
        var position = buffer.position()
        var hops = 0
        var consumed = -1

        while (hops <= MAX_POINTER_HOPS) {
            if (position < 0 || position >= buffer.limit()) return null
            val len = buffer.get(position).toInt() and 0xFF
            when {
                len == 0 -> {
                    position++
                    if (consumed < 0) consumed = position
                    if (consumed > buffer.limit()) return null
                    buffer.position(consumed)
                    // The root label is a separator, not part of the name.
                    return sb.toString().trimEnd('.')
                }

                len and 0xC0 == 0xC0 -> {
                    if (position + 1 >= buffer.limit()) return null
                    val target = ((len and 0x3F) shl 8) or (buffer.get(position + 1).toInt() and 0xFF)
                    if (target >= buffer.limit()) return null
                    if (consumed < 0) consumed = position + 2
                    position = target
                    hops++
                }

                len and 0xC0 != 0 -> return null // reserved label type

                else -> {
                    if (position + 1 + len > buffer.limit()) return null
                    for (i in 1..len) {
                        val c = buffer.get(position + i).toInt() and 0xFF
                        sb.append(if (c in 33..126) c.toChar() else '?')
                    }
                    sb.append('.')
                    position += 1 + len
                    if (sb.length > MAX_NAME) return null
                }
            }
        }
        return null
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        if (name.isNotEmpty()) {
            for (label in name.trimEnd('.').split('.').filter { it.isNotEmpty() }) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                val n = bytes.size.coerceAtMost(63)
                out.write(n)
                out.write(bytes, 0, n)
            }
        }
        out.write(0)
    }

    private const val MAX_POINTER_HOPS = 16
    private const val MAX_RECORDS = 64
}