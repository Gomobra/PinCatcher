package com.pincatcher.core.capture

import com.pincatcher.core.capture.dns.Dns
import com.pincatcher.core.capture.http.HttpHead
import com.pincatcher.core.capture.loop.LoopDetector
import com.pincatcher.core.capture.loop.LoopVerdict
import com.pincatcher.core.capture.net.ConnectionTable
import com.pincatcher.core.capture.net.FlowKey
import com.pincatcher.core.capture.net.Ip
import com.pincatcher.core.capture.net.Ipv4
import com.pincatcher.core.capture.net.PacketRouter
import com.pincatcher.core.capture.net.PacketVerdict
import com.pincatcher.core.capture.net.PacketWriter
import com.pincatcher.core.capture.net.ReplyPackets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** End-to-end checks on the packet-level pieces of the capture engine. */
class EnginePacketsTest {

    private val client = Ipv4.parse("10.111.222.1")
    private val server = Ipv4.parse("93.184.216.34")

    private fun tcpPacket(
        src: Int = client,
        dst: Int = server,
        sport: Int = 51514,
        dport: Int = 443,
        seq: Long = 1000,
        ack: Long = 0,
        flags: Int = Ip.FLAG_SYN,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = PacketWriter.tcpSegment(src, dst, sport, dport, seq, ack, flags, 65535, payload)

    /**
     * Routes and unwraps. `assertTrue(x is T)` would not narrow in Kotlin, and
     * a bare `as` would throw something unhelpful, so the failure is stated.
     */
    private fun route(packet: ByteArray): PacketVerdict =
        PacketRouter.route(packet) ?: fail("router dropped the packet")

    // ---- FlowKey ----

    @Test
    fun `flow key is direction blind`() {
        val forward = FlowKey.of(Ip.PROTO_TCP, client, 51514, server, 443)
        val reverse = FlowKey.of(Ip.PROTO_TCP, server, 443, client, 51514)
        assertEquals(forward, reverse, "the same connection must hash to one key")
    }

    @Test
    fun `flow key parses off a real packet`() {
        val key = requireNotNull(FlowKey.fromPacket(ByteBuffer.wrap(tcpPacket())))
        assertEquals(FlowKey.of(Ip.PROTO_TCP, client, 51514, server, 443), key)
    }

    @Test
    fun `flow key rejects a truncated packet`() {
        assertNull(FlowKey.fromPacket(ByteBuffer.wrap(tcpPacket().copyOf(12))))
    }

    // ---- router ----

    @Test
    fun `tcp is routed to a session with its payload`() {
        val payload = "GET / HTTP/1.1\r\n\r\n".encodeToByteArray()
        val verdict = route(tcpPacket(flags = Ip.FLAG_ACK or Ip.FLAG_PSH, payload = payload))
        assertArrayEquals(payload, (verdict as? PacketVerdict.Tcp ?: fail("expected Tcp, got $verdict")).payload)
    }

    @Test
    fun `quic on udp 443 is rejected with an icmp unreachable`() {
        val packet = ReplyPackets.udpDatagram(client, server, 51514, 443, byteArrayOf(1, 2, 3))
        val verdict = route(packet)
        val rejected = verdict as? PacketVerdict.RejectWithUnreachable
            ?: fail("expected RejectWithUnreachable, got $verdict")
        assertEquals(PacketRouter.ICMP_PORT_UNREACHABLE, rejected.code)
        assertTrue(
            rejected.reason.contains("QUIC"),
            "the reason has to explain itself - this looks like packet loss otherwise",
        )
    }

    @Test
    fun `dns on udp 53 is routed for forwarding`() {
        val query = Dns.build(
            Dns.Message(
                id = 0x1234,
                flags = 0,
                questions = listOf(Dns.Question("example.com", Dns.TYPE_A)),
                answers = emptyList(),
            ),
        )
        val packet = ReplyPackets.udpDatagram(client, server, 51514, 53, query)
        val verdict = route(packet)
        assertArrayEquals(query, (verdict as? PacketVerdict.Dns ?: fail("expected Dns, got $verdict")).query)
    }

    @Test
    fun `broadcast and multicast udp is ignored`() {
        val broadcast = ReplyPackets.udpDatagram(client, 0xFFFFFFFF.toInt(), 51514, 12345, byteArrayOf(1))
        assertTrue(PacketRouter.route(broadcast) is PacketVerdict.Ignore)
        val multicast = ReplyPackets.udpDatagram(client, 0xE0000001.toInt(), 51514, 12345, byteArrayOf(1))
        assertTrue(PacketRouter.route(multicast) is PacketVerdict.Ignore)
    }

    @Test
    fun `a fragment is ignored rather than misparsed`() {
        val packet = tcpPacket()
        packet[6] = 0x20 // more-fragments, offset 0
        assertTrue(PacketRouter.route(packet) is PacketVerdict.Ignore)
    }

    @Test
    fun `a non-ipv4 packet is ignored`() {
        val junk = ByteArray(40) { if (it == 0) 0x60.toByte() else 0 }
        assertTrue(PacketRouter.route(junk) is PacketVerdict.Ignore)
    }

    // ---- replies ----

    @Test
    fun `icmp unreachable verifies and quotes the offending header`() {
        val original = tcpPacket()
        val reply = ReplyPackets.icmpUnreachable(
            original = original,
            originalLength = original.size,
            destinationAddress = client,
            destinationPort = 51514,
            code = PacketRouter.ICMP_PORT_UNREACHABLE,
        )
        assertEquals(0, com.pincatcher.core.capture.net.Checksum.of(reply, 0, Ip.IPV4_HEADER_LEN))

        val buffer = ByteBuffer.wrap(reply)
        val ip = requireNotNull(com.pincatcher.core.capture.net.Ipv4Header.parse(buffer))
        assertEquals(Ip.PROTO_ICMP, ip.protocol)
        assertEquals(client, ip.destinationAddress)
        assertEquals(3, reply[Ip.IPV4_HEADER_LEN].toInt(), "ICMP type 3 = destination unreachable")

        // The quoted header must match the original, or the sender cannot match
        // the error to its socket and discards it.
        val quoted = reply.copyOfRange(Ip.IPV4_HEADER_LEN + 8, Ip.IPV4_HEADER_LEN + 8 + Ip.IPV4_HEADER_LEN)
        assertArrayEquals(original.copyOf(Ip.IPV4_HEADER_LEN), quoted)
    }

    @Test
    fun `a udp reply parses back as a udp packet`() {
        val payload = byteArrayOf(9, 8, 7)
        val packet = ReplyPackets.udpDatagram(server, client, 443, 51514, payload)
        val buffer = ByteBuffer.wrap(packet)
        val ip = requireNotNull(com.pincatcher.core.capture.net.Ipv4Header.parse(buffer))
        assertEquals(Ip.PROTO_UDP, ip.protocol)
        val udp = requireNotNull(com.pincatcher.core.capture.net.UdpHeader.parse(buffer))
        assertEquals(443, udp.sourcePort)
        assertEquals(51514, udp.destinationPort)
        assertArrayEquals(payload, buffer.readRemainingBytes())
    }

    @Test
    fun `an rst is well formed`() {
        val rst = ReplyPackets.tcpReset(server, client, 443, 51514, sequenceNumber = 7, acknowledgementNumber = 1000)
        val buffer = ByteBuffer.wrap(rst)
        requireNotNull(com.pincatcher.core.capture.net.Ipv4Header.parse(buffer))
        val tcp = requireNotNull(com.pincatcher.core.capture.net.TcpHeader.parse(buffer))
        assertTrue(tcp.rst)
        assertEquals(0, tcp.windowSize)
        assertEquals(1000L, tcp.acknowledgementNumber)
    }

    // ---- loop guard ----

    @Test
    fun `one self-addressed packet is not a loop`() {
        val detector = LoopDetector()
        assertEquals(LoopVerdict.Healthy, detector.observe("a", isOwnAddress = true, now = 0))
    }

    @Test
    fun `repeated self-addressed traffic trips the loop verdict`() {
        val detector = LoopDetector(suspectThreshold = 3)
        var verdict = LoopVerdict.Healthy
        repeat(3) { verdict = detector.observe("10.0.0.1:5000 <-> 1.2.3.4:443", isOwnAddress = true, now = 0) }
        assertEquals(LoopVerdict.LoopSuspected, verdict)
    }

    @Test
    fun `repeats on different tuples are not a loop`() {
        // One tuple looping is the signature. Many tuples each seen once is a
        // busy app, which is normal and must not stop a capture.
        val detector = LoopDetector(suspectThreshold = 3)
        repeat(20) { i ->
            assertEquals(
                LoopVerdict.Healthy,
                detector.observe("10.0.0.1:$i <-> 1.2.3.$i:443", isOwnAddress = true, now = 0),
                "distinct tuples must not be treated as a loop",
            )
        }
    }

    @Test
    fun `repeats outside the window reset`() {
        // Repetition only counts inside the window. Spread the same tuple out
        // past it and each sighting is a new burst, so a legitimately reconnecting
        // client never accumulates into a false positive.
        val detector = LoopDetector(suspectThreshold = 3, repeatWindowSeconds = 5)
        repeat(10) { i ->
            assertEquals(
                LoopVerdict.Healthy,
                detector.observe("k", isOwnAddress = true, now = i * 10L),
                "repeats outside the window must not accumulate",
            )
        }
    }

    // ---- connection table ----

    @Test
    fun `table evicts the oldest entry over budget`() {
        val table = ConnectionTable(maxEntries = 3, idleTimeoutMs = 10_000)
        repeat(5) { i ->
            val key = FlowKey.of(Ip.PROTO_TCP, client, 40000 + i, server, 443)
            table.put(key, "session-$i", now = i.toLong())
        }
        assertEquals(3, table.size)
        assertNull(table.get(FlowKey.of(Ip.PROTO_TCP, client, 40000, server, 443), now = 100))
        assertNotNull(table.get(FlowKey.of(Ip.PROTO_TCP, client, 40004, server, 443), now = 100))
    }

    @Test
    fun `table drops entries idle past the timeout`() {
        val table = ConnectionTable(maxEntries = 100, idleTimeoutMs = 1_000)
        val old = FlowKey.of(Ip.PROTO_TCP, client, 40000, server, 443)
        val live = FlowKey.of(Ip.PROTO_TCP, client, 40001, server, 443)

        // Still inside the timeout: nothing is idle.
        table.put(old, "a", now = 0)
        table.put(live, "b", now = 400)
        assertEquals(emptyList<FlowKey>(), table.stale(now = 500))

        // Past the timeout, but not yet past the live entry's: only the old one
        // is reported, oldest first.
        assertEquals(listOf(old), table.stale(now = 1_100))
        // A later insert drops it, which is how a stopped connection gets closed.
        table.put(live, "b", now = 1_100)
        assertNull(table.get(old, now = 1_100))
        assertEquals("b", table.get(live, now = 1_100))
    }

    @Test
    fun `table reports a loop verdict per packet`() {
        val table = ConnectionTable()
        var verdict = LoopVerdict.Healthy
        repeat(10) { verdict = table.onPacketObserved("k", isOwnAddress = true, now = 0) }
        assertEquals(LoopVerdict.LoopSuspected, verdict)
    }

    // ---- http ----

    @Test
    fun `parses a request head`() {
        val raw = ("POST /v1/login HTTP/1.1\r\nHost: api.example.com\r\n" +
            "Content-Type: application/json\r\nContent-Length: 17\r\n\r\n{\"user\":\"a\"}").encodeToByteArray()
        val head = requireNotNull(HttpHead.parseRequest(raw))
        assertEquals("POST", head.method)
        assertEquals("/v1/login", head.target)
        assertEquals("api.example.com", head.headers.first("host"), "field names are case-insensitive")
        assertEquals(17, head.bodyLength)
        assertFalse(head.isChunked)
    }

    @Test
    fun `an incomplete head returns null rather than an error`() {
        assertNull(HttpHead.parseRequest("GET / HTTP/1.1\r\nHost: a".encodeToByteArray()))
    }

    @Test
    fun `chunked beats a stale content-length`() {
        val raw = ("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n" +
            "Content-Length: 9999\r\n\r\n").encodeToByteArray()
        val head = requireNotNull(HttpHead.parseRequest(raw))
        assertTrue(head.isChunked)
        assertEquals(-1, head.bodyLength, "trusting the stale length would desynchronise the stream")
    }

    @Test
    fun `obsolete line folding is refused`() {
        val raw = "GET / HTTP/1.1\r\nHost: a\r\n  folded\r\n\r\n".encodeToByteArray()
        assertNull(HttpHead.parseRequest(raw))
    }

    @Test
    fun `parses a response head`() {
        val raw = ("HTTP/1.1 301 Moved Permanently\r\nLocation: https://x/\r\n" +
            "Content-Length: 0\r\n\r\n").encodeToByteArray()
        val head = requireNotNull(HttpHead.parseResponse(raw))
        assertEquals(301, head.status)
        assertEquals("Moved Permanently", head.reason)
    }

    @Test
    fun `decodes a chunked body`() {
        val raw = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".encodeToByteArray()
        val result = requireNotNull(HttpHead.decodeChunked(raw, 0, raw.size))
        assertEquals("hello world", String(result.body))
        assertEquals(raw.size, result.consumedBytes)
    }

    @Test
    fun `an incomplete chunked body returns null`() {
        val raw = "5\r\nhel".encodeToByteArray()
        assertNull(HttpHead.decodeChunked(raw, 0, raw.size))
    }

    @Test
    fun `chunk extensions are ignored`() {
        val raw = "5;name=value\r\nhello\r\n0\r\n\r\n".encodeToByteArray()
        assertEquals("hello", String(requireNotNull(HttpHead.decodeChunked(raw, 0, raw.size)).body))
    }

    @Test
    fun `extracts authority from connect and absolute targets`() {
        assertEquals("api.example.com" to 443, HttpHead.authorityOf("api.example.com:443"))
        assertEquals("api.example.com" to 80, HttpHead.authorityOf("http://api.example.com/path"))
        assertEquals("api.example.com" to 443, HttpHead.authorityOf("https://api.example.com"))
        assertNull(HttpHead.authorityOf(""))
        assertTrue(HttpHead.isAbsoluteForm("https://x/"))
        assertFalse(HttpHead.isAbsoluteForm("/relative"))
    }

    // ---- dns ----

    @Test
    fun `dns query round trips`() {
        val query = Dns.Message(
            id = 0xBEEF,
            flags = 0,
            questions = listOf(Dns.Question("api.example.com", Dns.TYPE_A)),
            answers = emptyList(),
        )
        val parsed = requireNotNull(Dns.parse(Dns.build(query)))
        assertEquals(0xBEEF, parsed.id)
        assertFalse(parsed.isResponse)
        assertEquals("api.example.com", parsed.questions.single().name)
        assertEquals(Dns.TYPE_A, parsed.questions.single().type)
    }

    @Test
    fun `dns response round trips with an answer`() {
        val response = Dns.Message(
            id = 7,
            flags = Dns.FLAG_RESPONSE,
            questions = listOf(Dns.Question("a.example", Dns.TYPE_A)),
            answers = listOf(
                Dns.Answer("a.example", Dns.TYPE_A, Dns.CLASS_IN, 300, Dns.ipv4Answer(Ipv4.parse("1.2.3.4"))),
            ),
        )
        val parsed = requireNotNull(Dns.parse(Dns.build(response)))
        assertTrue(parsed.isResponse)
        val answer = parsed.answers.single()
        assertEquals("a.example", answer.name)
        assertEquals(300, answer.ttl)
        assertEquals(Ipv4.parse("1.2.3.4"), Dns.ipv4From(answer.data))
    }

    @Test
    fun `dns name compression pointers are followed`() {
        // "b.example" then "a.example" where a's name points back into b's.
        val bytes = byteArrayOf(
            0, 1, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0,
            1, 'b'.code.toByte(), 7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 0,
            0, Dns.TYPE_A.toByte(), 0, Dns.CLASS_IN.toByte(),
            0xC0.toByte(), 0x0C, // the answer's name is a pointer back to offset 12
            0, Dns.TYPE_A.toByte(), 0, Dns.CLASS_IN.toByte(),
            0, 0, 0, 60, 0, 4, 1, 2, 3, 4,
        )
        val parsed = requireNotNull(Dns.parse(bytes))
        assertEquals("b.example", parsed.questions.single().name)
        assertEquals("b.example", parsed.answers.single().name)
    }

    @Test
    fun `a self-referential compression pointer is rejected`() {
        val bytes = byteArrayOf(
            0, 1, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 0, 0, 0, 0, 0,
            0xC0.toByte(), 0x0C, // points at itself
        )
        assertNull(Dns.parse(bytes), "a pointer loop must not hang the parser")
    }

    @Test
    fun `dns error response carries the rcode and keeps the question`() {
        val query = requireNotNull(
            Dns.parse(
                Dns.build(Dns.Message(1, 0, listOf(Dns.Question("x.example", Dns.TYPE_A)), emptyList())),
            ),
        )
        val parsed = requireNotNull(Dns.parse(Dns.errorResponse(query, Dns.RCODE_NXDOMAIN)))
        assertTrue(parsed.isResponse)
        assertEquals(Dns.RCODE_NXDOMAIN, parsed.rcode)
        assertEquals("x.example", parsed.questions.single().name)
    }

    @Test
    fun `a truncated dns payload returns null`() {
        assertNull(Dns.parse(ByteArray(6)))
    }
}

private fun ByteBuffer.readRemainingBytes(): ByteArray = ByteArray(remaining()).also { get(it) }