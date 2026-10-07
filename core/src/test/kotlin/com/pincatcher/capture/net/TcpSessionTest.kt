package com.pincatcher.capture.net

import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TcpSessionTest {

    private val client = Ipv4.parse("10.0.0.2")
    private val server = Ipv4.parse("93.184.216.34")
    private val outbound = mutableListOf<ByteArray>()

    private fun newSession(peerSeq: Long = 1000L) =
        TcpSession(client, 51000, server, 443, peerSeq) { outbound += it }

    /** Feeds a segment from the tun. */
    private fun TcpSession.clientSegment(
        seq: Long,
        ack: Long = 0,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        window: Int = 65535,
    ): List<TcpSession.Action> {
        val raw = PacketWriter.tcpSegment(
            sourceAddress = client,
            destinationAddress = server,
            sourcePort = 51000,
            destinationPort = 443,
            sequenceNumber = seq,
            acknowledgementNumber = ack,
            flags = flags,
            windowSize = window,
            payload = payload,
        )
        val buffer = ByteBuffer.wrap(raw)
        val ip = requireNotNull(Ipv4Header.parse(buffer))
        val tcp = requireNotNull(TcpHeader.parse(buffer))
        // Read the payload back out of the wire bytes rather than trusting the
        // argument, so the test exercises the same path the tun reader will.
        val wire = ByteArray(buffer.remaining())
        buffer.get(wire)
        return onClientSegment(ip, tcp, wire)
    }

    /** ByteArray equality in a data class is reference equality, so compare bytes. */
    private fun assertToUpstream(actions: List<TcpSession.Action>, expected: ByteArray) {
        assertEquals(1, actions.size, "expected exactly one action, got $actions")
        val action = actions.single()
        assertTrue(action is TcpSession.Action.ToUpstream, "expected ToUpstream, got $action")
        assertArrayEquals(expected, (action as TcpSession.Action.ToUpstream).payload)
    }

    private fun assertToClient(actions: List<TcpSession.Action>, expected: ByteArray) {
        assertEquals(1, actions.size, "expected exactly one action, got $actions")
        val action = actions.single()
        assertTrue(action is TcpSession.Action.ToClient, "expected ToClient, got $action")
        assertArrayEquals(expected, (action as TcpSession.Action.ToClient).payload)
    }

    private fun lastReply(): TcpHeader {
        val buffer = ByteBuffer.wrap(outbound.last())
        requireNotNull(Ipv4Header.parse(buffer))
        return requireNotNull(TcpHeader.parse(buffer))
    }

    @Test
    fun `syn is answered with syn-ack and asks for an upstream connection`() {
        val session = newSession(peerSeq = 1000)
        val actions = session.clientSegment(seq = 500, flags = Ip.FLAG_SYN)

        assertEquals(TcpSession.State.ESTABLISHED, session.state)
        assertEquals(listOf(TcpSession.Action.ConnectUpstream), actions)

        val reply = lastReply()
        assertTrue(reply.syn)
        assertTrue(reply.ack)
        assertEquals(1000L, reply.sequenceNumber)
        assertEquals(501L, reply.acknowledgementNumber, "SYN consumes one sequence number")
    }

    @Test
    fun `client payload is forwarded upstream and acknowledged`() {
        val session = newSession(peerSeq = 1000)
        session.clientSegment(seq = 500, flags = Ip.FLAG_SYN)

        val body = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".encodeToByteArray()
        val actions = session.clientSegment(seq = 501, ack = 1001, flags = Ip.FLAG_ACK or Ip.FLAG_PSH, payload = body)

        assertToUpstream(actions, body)
        val reply = lastReply()
        assertTrue(reply.ack)
        assertFalse(reply.syn)
        // The ack we return reflects what we have *received*, not what the
        // client claimed: 501 (after SYN) + the request length.
        assertEquals(Seq.plus(501, body.size.toLong()), reply.acknowledgementNumber)
        assertEquals(1001L, reply.sequenceNumber, "our SYN-ACK consumed exactly one sequence number")
    }

    @Test
    fun `acknowledgement number advances by the payload length`() {
        val session = newSession(peerSeq = 1000)
        session.clientSegment(seq = 500, flags = Ip.FLAG_SYN)

        session.clientSegment(seq = 501, ack = 1001, flags = Ip.FLAG_ACK, payload = ByteArray(10))
        assertEquals(511L, lastReply().acknowledgementNumber)

        session.clientSegment(seq = 511, ack = 1001, flags = Ip.FLAG_ACK, payload = ByteArray(4))
        assertEquals(515L, lastReply().acknowledgementNumber)
    }

    @Test
    fun `upstream data is handed to the client and acknowledged`() {
        val session = newSession(peerSeq = 1000)
        session.clientSegment(seq = 500, flags = Ip.FLAG_SYN)
        session.clientSegment(seq = 501, ack = 1001, flags = Ip.FLAG_ACK, payload = "ping".encodeToByteArray())

        val response = "HTTP/1.1 200 OK\r\n\r\n".encodeToByteArray()
        val actions = session.onUpstreamData(response)

        assertToClient(actions, response)
        val reply = lastReply()
        assertTrue(reply.psh)
        assertEquals(505L, reply.acknowledgementNumber, "ack covers the client's request bytes")
        assertEquals(1001L, reply.sequenceNumber)
    }

    @Test
    fun `fin is acknowledged and closes the session`() {
        val session = newSession(peerSeq = 1000)
        session.clientSegment(seq = 500, flags = Ip.FLAG_SYN)
        val actions = session.clientSegment(seq = 501, ack = 1001, flags = Ip.FLAG_ACK or Ip.FLAG_FIN)

        assertEquals(listOf(TcpSession.Action.CloseUpstream), actions)
        assertEquals(TcpSession.State.CLOSING, session.state)

        val reply = lastReply()
        assertTrue(reply.ack)
        assertEquals(502L, reply.acknowledgementNumber, "FIN consumes one sequence number")
    }

    @Test
    fun `rst closes immediately without acking`() {
        val session = newSession(peerSeq = 1000)
        session.clientSegment(seq = 500, flags = Ip.FLAG_SYN)
        val before = outbound.size

        val actions = session.clientSegment(seq = 501, flags = Ip.FLAG_RST)
        assertEquals(TcpSession.State.CLOSED, session.state)
        assertEquals(listOf(TcpSession.Action.CloseUpstream), actions)
        assertEquals(before, outbound.size, "RST gets no reply")
    }

    @Test
    fun `out-of-order empty segment is dropped and counted`() {
        val session = newSession(peerSeq = 1000)
        session.clientSegment(seq = 500, flags = Ip.FLAG_SYN)

        // Acknowledges far ahead of anything we have received.
        val actions = session.clientSegment(seq = 501, ack = 900_000, flags = Ip.FLAG_ACK)
        assertTrue(actions.isEmpty())
        assertEquals(1, session.droppedSegments)
    }

    @Test
    fun `sequence numbers wrap at 32 bits without stalling`() {
        val session = newSession(peerSeq = 0xFFFFFFFEL)
        session.clientSegment(seq = 0xFFFFFFFEL, flags = Ip.FLAG_SYN)

        // The client's next sequence number has wrapped past zero.
        session.clientSegment(seq = 0xFFFFFFFFL, ack = 0xFFFFFFFFL, flags = Ip.FLAG_ACK, payload = ByteArray(8))
        assertEquals(7L, lastReply().acknowledgementNumber)

        val response = "ok".encodeToByteArray()
        session.onUpstreamData(response)
        assertEquals(7L, lastReply().acknowledgementNumber, "acknowledgement wrapped correctly")
        assertEquals(0xFFFFFFFFL, lastReply().sequenceNumber)
    }

    @Test
    fun `upstream close sends fin and shuts the session`() {
        val session = newSession(peerSeq = 1000)
        session.clientSegment(seq = 500, flags = Ip.FLAG_SYN)
        session.clientSegment(seq = 501, ack = 1001, flags = Ip.FLAG_ACK, payload = ByteArray(3))

        val actions = session.onUpstreamClosed()
        assertEquals(listOf(TcpSession.Action.CloseUpstream), actions)
        assertEquals(TcpSession.State.CLOSED, session.state)

        val reply = lastReply()
        assertTrue(reply.fin)
        assertTrue(reply.ack)
    }

    @Test
    fun `upstream data after close is ignored`() {
        val session = newSession(peerSeq = 1000)
        session.clientSegment(seq = 500, flags = Ip.FLAG_SYN)
        session.onUpstreamClosed()
        val before = outbound.size

        assertTrue(session.onUpstreamData("late".encodeToByteArray()).isEmpty())
        assertEquals(before, outbound.size)
    }
}