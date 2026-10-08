package com.pincatcher.core.capture.tls

/**
 * Just enough of the TLS ClientHello to know who the client is asking for.
 *
 * ## Why we parse this ourselves
 *
 * The alternative is letting a handshake fail and reading the hostname out of the
 * error, which is how a proxy ends up doing TLS with the wrong certificate
 * exactly when the network is worst. The ClientHello is the only place the name
 * appears in a form we are allowed to trust before we have authenticated anything.
 *
 * ## What is read
 *
 * Only three things, because only three are needed: the server name the client
 * asked for, the protocol versions it offered, and any ALPN list. ALPN matters
 * because a client that offers `h2` and is handed a `http/1.1` connection will
 * still work, but one offered only `h2` will not - and recording an exchange that
 * was never spoken as HTTP/1.1 is worse than not recording it.
 *
 * ## What is not
 *
 * No cipher selection, no session resumption, no ECH. Encrypted ClientHello hides
 * the name entirely, and a proxy cannot recover it; when that is all we get, the
 * connection is passed through undecrypted rather than guessed at.
 *
 * Every length is bounds-checked against the buffer, because this is the first
 * bytes of a connection from an app we do not control.
 */
object ClientHello {

    private const val CONTENT_TYPE_HANDSHAKE = 0x16
    private const val HANDSHAKE_CLIENT_HELLO = 0x01

    /** extension server_name (RFC 6066). */
    private const val EXTENSION_SERVER_NAME = 0x0000

    /** extension ALPN (RFC 7301). */
    private const val EXTENSION_ALPN = 0x0010

    /** application_layer_protocol_negotiation */
    private const val PROTOCOL_ALPN = 16

    /** Highest name we will accept. DNS is 253; anything longer is not a hostname. */
    private const val MAX_NAME = 253

    data class Hello(
        /** The requested server name, or null when the client sent none. */
        val serverName: String?,
        /** Low bytes of the client's supported version, e.g. 0x0303 for TLS 1.2. */
        val legacyVersion: Int,
        /** Offered ALPN protocols, in preference order. Empty when none offered. */
        val alpn: List<String>,
    ) {
        /** TLS 1.3 offered as a supported_versions entry rather than in the legacy field. */
        val offersTls13: Boolean get() = legacyVersion >= 0x0304 || alpn.isEmpty()

        val prefersHttp2: Boolean get() = alpn.contains("h2")

        /** True when nothing here is worth a decrypted session. */
        val isUsable: Boolean get() = serverName != null
    }

    /**
     * Parses a ClientHello record, or returns null.
     *
     * [bytes] may be a prefix of the record: a TCP segment boundary routinely
     * falls mid-handshake, and returning null for "not enough bytes yet" is what
     * lets the caller wait rather than drop the connection.
     */
    fun parse(bytes: ByteArray, length: Int = bytes.size): Hello? {
        if (length < 5) return null
        if (bytes[0].toInt() and 0xFF != CONTENT_TYPE_HANDSHAKE) return null

        // The record layer is length-prefixed and may span several TLS records on
        // the wire; what matters is that the handshake message fits inside it.
        val recordLength = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        val end = (5 + recordLength).coerceAtMost(length)

        // Record header is 5 bytes, handshake header is 4 (type + 3-byte length).
        if (end < 9) return null
        if (bytes[5].toInt() and 0xFF != HANDSHAKE_CLIENT_HELLO) return null

        // The handshake length lives *in* the header, so it is read before the
        // cursor moves past it. It has to be honoured: extensions are walked by a
        // declared length, and trusting more than the message contains would read
        // whatever happens to follow it in the buffer.
        val handshakeLength = ((bytes[6].toInt() and 0xFF) shl 16) or
            ((bytes[7].toInt() and 0xFF) shl 8) or
            (bytes[8].toInt() and 0xFF)
        var at = 9
        val messageEnd = (at + handshakeLength).coerceAtMost(end)
        if (at + 2 > messageEnd) return null

        val legacyVersion = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        at += 2

        // random
        if (at + 32 > messageEnd) return null
        at += 32

        // legacy_session_id
        if (at >= messageEnd) return null
        at += 1 + (bytes[at].toInt() and 0xFF)
        if (at + 2 > messageEnd) return null

        // cipher_suites
        val cipherLength = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        at += 2 + cipherLength
        if (at > messageEnd) return null

        // legacy_compression_methods
        if (at >= messageEnd) return null
        at += 1 + (bytes[at].toInt() and 0xFF)
        if (at + 2 > messageEnd) return null

        // extensions: a 2-byte total, then each extension length-prefixed
        val extensionsLength = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        at += 2
        val extensionsEnd = (at + extensionsLength).coerceAtMost(messageEnd)

        var serverName: String? = null
        val alpn = mutableListOf<String>()

        while (at + 4 <= extensionsEnd) {
            val type = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
            val size = ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            at += 4
            if (at + size > extensionsEnd) break

            when (type) {
                EXTENSION_SERVER_NAME -> serverName = readServerName(bytes, at, size)
                EXTENSION_ALPN -> readAlpn(bytes, at, size, alpn)
            }
            at += size
        }

        return Hello(
            serverName = serverName,
            legacyVersion = legacyVersion,
            alpn = alpn,
        )
    }

    /**
     * The `host_name` entry of the server_name extension.
     *
     * The extension carries a list of typed entries and only entry type 0 is a
     * hostname. Entries 1 and 2 are certificates for client authentication, which
     * a proxy must not mistake for the server's identity.
     */
    private fun readServerName(bytes: ByteArray, at: Int, size: Int): String? {
        // Smallest legal body: 2 list length + 1 type + 2 name length + 1 name byte.
        if (size < 6) return null
        // The extension body *is* the ServerNameList, so its length is the first
        // two bytes. Reading it from further in silently walks past the entries.
        val listLength = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        var cursor = at + 2
        val listEnd = (cursor + listLength).coerceAtMost(at + size)

        while (cursor + 3 <= listEnd) {
            val type = bytes[cursor].toInt() and 0xFF
            val nameLength = ((bytes[cursor + 1].toInt() and 0xFF) shl 8) or (bytes[cursor + 2].toInt() and 0xFF)
            cursor += 3
            if (cursor + nameLength > listEnd) return null
            if (type == 0) {
                val nameLengthClamped = nameLength.coerceAtMost(MAX_NAME)
                if (nameLength == 0) return null
                val raw = bytes.copyOfRange(cursor, cursor + nameLengthClamped)
                val name = raw.toString(Charsets.US_ASCII).lowercase()
                return name.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
            }
            cursor += nameLength
        }
        return null
    }

    private fun readAlpn(bytes: ByteArray, at: Int, size: Int, into: MutableList<String>) {
        if (size < 2) return
        val listLength = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        var cursor = at + 2
        val listEnd = (cursor + listLength).coerceAtMost(at + size)
        while (cursor < listEnd) {
            val nameLength = bytes[cursor].toInt() and 0xFF
            cursor += 1
            if (nameLength == 0 || cursor + nameLength > listEnd) break
            into += bytes.copyOfRange(cursor, cursor + nameLength).toString(Charsets.US_ASCII)
            cursor += nameLength
        }
        if (into.size > MAX_ALPN) into.subList(MAX_ALPN, into.size).clear()
    }

    /** Content types we can forward without understanding them. */
    fun isTlsRecord(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() && bytes[0].toInt() and 0xFF == CONTENT_TYPE_HANDSHAKE

    private const val MAX_ALPN = 32
}