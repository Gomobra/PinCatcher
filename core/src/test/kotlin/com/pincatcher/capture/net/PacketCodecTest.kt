package com.pincatcher.capture.net

import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketCodecTest {

    private val client = Ipv4.parse("10.0.0.2")
    private val server = Ipv4.parse("93.184.216.34")

    @Test
    fun `builds an ipv4 header that parses back`() {
        val segment = PacketWriter.tcpSegment(
            sourceAddress = server,
            destinationAddress = client,
            sourcePort = 443,
            destinationPort = 51000,
            sequenceNumber = 0x11223344L,
            acknowledgementNumber = 0x55667788L,
            flags = Ip.FLAG_SYN or Ip.FLAG_ACK,
            windowSize = 65535,
        )

        val buffer = ByteBuffer.wrap(segment)
        assertEquals(0, buffer.position())
        val ip = requireNotNull(Ipv4Header.parse(buffer))
        assertEquals(Ip.IPV4_HEADER_LEN, buffer.position(), "parser must stop at the transport header")
        assertEquals(Ip.IPV4_HEADER_LEN + Ip.TCP_HEADER_LEN, ip.totalLength)
        assertEquals(Ip.PROTO_TCP, ip.protocol)
        assertEquals(server, ip.sourceAddress)
        assertEquals(client, ip.destinationAddress)
        assertTrue(ip.dontFragment)
        assertTrue(ip.isFirstFragment)
        assertEquals(64, ip.ttl)

        val tcp = requireNotNull(TcpHeader.parse(buffer))
        assertEquals(segment.size, buffer.position(), "parser must stop at the first payload byte")
        assertEquals(443, tcp.sourcePort)
        assertEquals(51000, tcp.destinationPort)
        assertEquals(0x11223344L, tcp.sequenceNumber)
        assertEquals(0x55667788L, tcp.acknowledgementNumber)
        assertTrue(tcp.syn)
        assertTrue(tcp.ack)
        assertFalse(tcp.fin)
        assertEquals(5, tcp.dataOffset, "data offset counts 32-bit words, so 5 words = a 20-byte header")
    }

    @Test
    fun `ip header checksum verifies as zero over the header`() {
        val segment = PacketWriter.tcpSegment(server, client, 443, 51000, 1, 1, Ip.FLAG_ACK, 65535)
        // Summing a correct header, including its checksum, yields zero.
        assertEquals(0, Checksum.of(segment, 0, Ip.IPV4_HEADER_LEN))
    }

    @Test
    fun `tcp checksum verifies with the pseudo header`() {
        val payload = "hello".encodeToByteArray()
        val segment = PacketWriter.tcpSegment(server, client, 443, 51000, 7, 9, Ip.FLAG_ACK or Ip.FLAG_PSH, 65535, payload)
        val tcpStart = Ip.IPV4_HEADER_LEN

        var sum = Checksum.pseudoHeaderSum(server, client, Ip.PROTO_TCP, segment.size - tcpStart)
        var i = tcpStart
        while (i + 1 < segment.size) {
            sum += ((segment[i].toInt() and 0xFF) shl 8) or (segment[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < segment.size) sum += (segment[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)

        assertEquals(0, sum.toInt().inv() and 0xFFFF, "TCP checksum should verify to zero")
    }

    @Test
    fun `payload survives the round trip`() {
        val payload = ByteArray(1500) { (it % 251).toByte() }
        val segment = PacketWriter.tcpSegment(server, client, 443, 51000, 1, 1, Ip.FLAG_ACK or Ip.FLAG_PSH, 65535, payload)

        val buffer = ByteBuffer.wrap(segment)
        val ip = requireNotNull(Ipv4Header.parse(buffer))
        assertEquals(payload.size, ip.totalLength - ip.headerLength - Ip.TCP_HEADER_LEN)
        requireNotNull(TcpHeader.parse(buffer))
        val body = ByteArray(buffer.remaining())
        buffer.get(body)
        assertArrayEquals(payload, body)
    }

    @Test
    fun `rejects a non-ipv4 packet`() {
        // Version 6 in the high nibble.
        val v6 = ByteArray(40) { if (it == 0) 0x60.toByte() else 0 }
        assertNull(Ipv4Header.parse(ByteBuffer.wrap(v6)))
    }

    @Test
    fun `rejects a header that claims to be longer than the buffer`() {
        val truncated = ByteArray(12)
        assertNull(Ipv4Header.parse(ByteBuffer.wrap(truncated)))
    }

    @Test
    fun `rejects an implausible ihl`() {
        val packet = PacketWriter.tcpSegment(server, client, 1, 2, 1, 1, Ip.FLAG_ACK, 1)
        packet[0] = 0x43.toByte() // IHL 3, below the minimum of 5
        assertNull(Ipv4Header.parse(ByteBuffer.wrap(packet)))
    }

    @Test
    fun `acknowledgement comparison is wrap-safe`() {
        val tcp = TcpHeader(
            sourcePort = 1, destinationPort = 2,
            sequenceNumber = 0, acknowledgementNumber = 0,
            flags = Ip.FLAG_ACK, windowSize = 1, checksum = 0,
            urgentPointer = 0, dataOffset = 5,
        )
        // A window crossing 2^32 must still read as "in order".
        assertTrue(tcp.copy(acknowledgementNumber = 0xFFFFFFFFL).acknowledges(0xFFFFFFFEL))
        // ack=1 is seq 0xFFFFFFFE + 3 once the wrap is accounted for, so it is
        // in order - an unsigned compare would call this "before".
        assertTrue(tcp.copy(acknowledgementNumber = 1L).acknowledges(0xFFFFFFFEL))
        assertTrue(tcp.copy(acknowledgementNumber = 3L).acknowledges(0xFFFFFFFEL))
        // ack == seq covers the segment, so it is in order.
        assertTrue(tcp.copy(acknowledgementNumber = 0xFFFFFFFEL).acknowledges(0xFFFFFFFEL))
        // Acknowledging less than what we hold is a stale segment.
        assertFalse(tcp.copy(acknowledgementNumber = 0xFFFFFFFDL).acknowledges(0xFFFFFFFEL))
        assertFalse(tcp.copy(acknowledgementNumber = 0xFFFFFF00L).acknowledges(0xFFFFFFFEL))
    }

    @Test
    fun `address round trips`() {
        assertEquals("93.184.216.34", Ipv4.toString(server))
        assertEquals(0, Ipv4.parse("0.0.0.0"))
        assertEquals(-1, Ipv4.parse("255.255.255.255"))
    }
}