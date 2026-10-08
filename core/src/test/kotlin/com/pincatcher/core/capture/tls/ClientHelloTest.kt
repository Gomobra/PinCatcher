package com.pincatcher.core.capture.tls

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * [ClientHello] tests.
 *
 * The fixtures are assembled field by field rather than pasted as hex, because the
 * whole point of these tests is that the wire layout is understood. A hex blob
 * would let a wrong offset still pass.
 */
class ClientHelloTest {

    // ---- fixture construction ----

    private class HelloBuilder(
        private val legacyVersion: Int = 0x0303,
        private val sessionId: ByteArray = ByteArray(0),
    ) {
        private val extensions = ByteArrayOutputStream()

        // ServerNameList and ProtocolNameList are both a 2-byte length followed by
        // entries. The length is part of the list, not the enclosing extension, so
        // the enclosing size counts it too.
        private fun lengthPrefixed(body: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(body.size shr 8); write(body.size and 0xFF)
            write(body)
        }.toByteArray()

        fun serverName(name: String) = apply { serverNameEntry(0, name) }

        fun serverNameEntry(entryType: Int, name: String) = apply {
            val raw = name.toByteArray(Charsets.US_ASCII)
            val entries = ByteArrayOutputStream().apply {
                write(entryType) // 0 = host_name
                write(raw.size shr 8); write(raw.size and 0xFF)
                write(raw)
            }.toByteArray()
            extension(0x0000, lengthPrefixed(entries))
        }

        fun alpn(vararg protocols: String) = apply {
            val entries = ByteArrayOutputStream()
            protocols.forEach {
                entries.write(it.length)
                entries.write(it.toByteArray(Charsets.US_ASCII))
            }
            extension(0x0010, lengthPrefixed(entries.toByteArray()))
        }

        /** An extension whose declared length runs past the message. */
        fun lyingExtension(type: Int, declaredSize: Int, actual: ByteArray) = apply {
            extensions.write(type shr 8); extensions.write(type and 0xFF)
            extensions.write(declaredSize shr 8); extensions.write(declaredSize and 0xFF)
            extensions.write(actual)
        }

        private fun extension(type: Int, body: ByteArray) {
            extensions.write(type shr 8); extensions.write(type and 0xFF)
            extensions.write(body.size shr 8); extensions.write(body.size and 0xFF)
            extensions.write(body)
        }

        /** The full TLS record, ready to hand the parser. */
        fun build(): ByteArray {
            val extensionsBody = extensions.toByteArray()
            val handshake = ByteArrayOutputStream().apply {
                write(0x03); write(legacyVersion and 0xFF)
                write(ByteArray(32)) // random
                write(sessionId.size); write(sessionId)
                write(0); write(2) // cipher_suites length
                write(0x13); write(0x01) // TLS_AES_128_GCM_SHA256
                write(1); write(0) // compression_methods
                write(extensionsBody.size shr 8); write(extensionsBody.size and 0xFF)
                write(extensionsBody)
            }.toByteArray()

            val message = ByteArrayOutputStream().apply {
                write(0x01) // ClientHello
                write(handshake.size shr 16)
                write(handshake.size shr 8 and 0xFF)
                write(handshake.size and 0xFF)
                write(handshake)
            }.toByteArray()

            return ByteArrayOutputStream().apply {
                write(0x16) // handshake record
                write(0x03); write(0x01) // legacy record version
                write(message.size shr 8); write(message.size and 0xFF)
                write(message)
            }.toByteArray()
        }
    }

    // ---- the name we are after ----

    @Test
    fun `reads the server name from a normal client hello`() {
        val hello = HelloBuilder().serverName("api.example.com").alpn("h2", "http/1.1").build()
        val parsed = requireNotNull(ClientHello.parse(hello))
        assertEquals("api.example.com", parsed.serverName)
        assertEquals(0x0303, parsed.legacyVersion)
        assertEquals(listOf("h2", "http/1.1"), parsed.alpn)
        assertTrue(parsed.prefersHttp2)
        assertTrue(parsed.isUsable)
    }

    @Test
    fun `lower cases the name`() {
        // Certificates are matched case-insensitively, so two spellings of the
        // same host must not become two cache entries.
        val hello = HelloBuilder().serverName("API.Example.COM").build()
        assertEquals("api.example.com", requireNotNull(ClientHello.parse(hello)).serverName)
    }

    @Test
    fun `a session id is skipped correctly`() {
        // Resumed connections carry a non-empty session_id. Reading the wrong
        // length here shifts every following field and the name comes back wrong.
        val resumed = ByteArray(32) { (it + 1).toByte() }
        val hello = HelloBuilder(sessionId = resumed).serverName("resumed.example").build()
        assertEquals("resumed.example", requireNotNull(ClientHello.parse(hello)).serverName)
    }

    @Test
    fun `only host_name entries are taken as the server name`() {
        // Extension type 1 and 2 carry certificates for client authentication. A
        // proxy that read one of those would ask for the identity the *client*
        // offered, not the server's.
        val hello = HelloBuilder()
            .serverNameEntry(entryType = 2, name = "client.example")
            .build()
        assertNull(requireNotNull(ClientHello.parse(hello)).serverName)
    }

    @Test
    fun `a host name after a client cert entry is still found`() {
        val hello = HelloBuilder()
            .serverNameEntry(entryType = 1, name = "skipped.example")
            .serverName("wanted.example")
            .build()
        assertEquals("wanted.example", requireNotNull(ClientHello.parse(hello)).serverName)
    }

    @Test
    fun `a hello with no server name is parsed but unusable`() {
        val hello = HelloBuilder().alpn("h2").build()
        val parsed = requireNotNull(ClientHello.parse(hello))
        assertNull(parsed.serverName)
        assertFalse(parsed.isUsable, "no name means nothing to mint a certificate for")
    }

    @Test
    fun `a name that is not a hostname is refused`() {
        // Whatever is in that field is attacker-controlled and ends up in a
        // certificate. Only characters a hostname can contain are accepted.
        val hello = HelloBuilder().serverName("evil/../../x").build()
        assertNull(requireNotNull(ClientHello.parse(hello)).serverName)
    }

    // ---- refusals ----

    @Test
    fun `a truncated hello waits for more bytes`() {
        // A segment boundary routinely lands mid-handshake. Returning null here is
        // what lets the caller wait instead of dropping a live connection.
        val hello = HelloBuilder().serverName("api.example.com").build()
        for (cut in 1 until hello.size) {
            val prefix = hello.copyOfRange(0, cut)
            val parsed = ClientHello.parse(prefix)
            if (parsed != null) {
                assertNull(
                    parsed.serverName,
                    "a $cut-byte prefix must not yield a name from bytes it does not have",
                )
            }
        }
    }

    @Test
    fun `an extension claiming more bytes than the message holds is refused`() {
        val hello = HelloBuilder()
            .lyingExtension(0x0000, declaredSize = 4096, actual = byteArrayOf(0, 20, 0, 3, 'a'.code.toByte()))
            .build()
        assertNull(requireNotNull(ClientHello.parse(hello)).serverName)
    }

    @Test
    fun `non-tls bytes are rejected outright`() {
        // Plain HTTP on port 443, or a protocol that is not TLS at all.
        assertNull(ClientHello.parse("GET / HTTP/1.1\r\nHost: x\r\n\r\n".encodeToByteArray()))
        assertNull(ClientHello.parse(ByteArray(0)))
        assertNull(ClientHello.parse(byteArrayOf(0x17), 1))
    }

    @Test
    fun `only a client hello is treated as one`() {
        val hello = HelloBuilder().serverName("api.example.com").build()
        // Same record, different handshake type: a renegotiation, not an opening.
        val notAHello = hello.copyOf().also { it[5] = 0x02 }
        assertNull(ClientHello.parse(notAHello))
    }

    @Test
    fun `a record spanning more than one segment is not truncated mid name`() {
        val hello = HelloBuilder().serverName("api.example.com").build()
        // The record length covers a body longer than we hold: a valid state for a
        // stream, and the name must still be read if it is already present.
        val short = hello.copyOf(hello.size - 4)
        assertNull(requireNotNull(ClientHello.parse(short)).serverName)
    }

    @Test
    fun `an alpn list is bounded`() {
        val many = (1..100).map { "p$it" }.toTypedArray()
        val parsed = requireNotNull(ClientHello.parse(HelloBuilder().serverName("a.example").alpn(*many).build()))
        assertTrue(parsed.alpn.size <= 32, "an unbounded list would be a memory lever")
    }
}