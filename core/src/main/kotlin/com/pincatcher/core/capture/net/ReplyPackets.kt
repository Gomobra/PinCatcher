package com.pincatcher.core.capture.net

import java.nio.ByteBuffer

/**
 * Builders for the packets we synthesise *back into* the tun: UDP replies and
 * ICMP errors.
 *
 * Separate from [PacketWriter] because a tun reply and a normal outbound
 * segment differ in a way that matters: the tun write path must not fragment,
 * so DF is always set, and the TTL is ours rather than the peer's.
 */
object ReplyPackets {

    /**
     * IPv4 + ICMP destination-unreachable back to the original sender.
     *
     * The ICMP message quotes the offending IP header and the first 8 bytes of
     * its payload, per RFC 792. Quoting is what lets the sender match the error
     * to its socket; sending an empty message gets the ICMP silently discarded
     * by many stacks, and the client then waits out a full handshake timeout.
     */
    fun icmpUnreachable(
        original: ByteArray,
        originalLength: Int,
        destinationAddress: Int,
        destinationPort: Int,
        code: Int,
    ): ByteArray {
        val quoteLength = (Ip.IPV4_HEADER_LEN + 8).coerceAtMost(originalLength)
        val payload = ByteArray(8 + quoteLength)
        payload[0] = 3 // destination unreachable
        payload[1] = code.toByte()
        payload[2] = 0; payload[3] = 0 // checksum placeholder
        System.arraycopy(original, 0, payload, 8, quoteLength)

        val total = Ip.IPV4_HEADER_LEN + payload.size
        val out = ByteBuffer.allocate(total)
        out.put(0x45.toByte())
        out.put(0)
        out.putShort(total.toShort())
        out.putShort(0) // identification
        out.putShort(0x4000.toShort()) // DF, so the tun never has to fragment
        out.put(TUN_TTL.toByte())
        out.put(Ip.PROTO_ICMP.toByte())
        out.putShort(0) // checksum placeholder
        out.putInt(destinationAddress)
        // The ICMP is sent *from* the address the client was trying to reach,
        // otherwise the reply looks like it came from a third party and is
        // dropped as a spoof.
        out.putInt(addressOf(original, originalLength))

        out.put(payload)

        val bytes = out.array()
        val payloadSum = Checksum.of(bytes, Ip.IPV4_HEADER_LEN, payload.size)
        bytes[2] = (payloadSum ushr 8).toByte()
        bytes[3] = (payloadSum and 0xFF).toByte()

        val ipSum = Checksum.of(bytes, 0, Ip.IPV4_HEADER_LEN)
        bytes[10] = (ipSum ushr 8).toByte()
        bytes[11] = (ipSum and 0xFF).toByte()
        return bytes
    }

    /**
     * A UDP datagram back to the client.
     *
     * @param ttl ours, not the peer's: this never leaves the device.
     */
    fun udpDatagram(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
        ttl: Int = TUN_TTL,
    ): ByteArray {
        val udpLength = Ip.UDP_HEADER_LEN + payload.size
        val total = Ip.IPV4_HEADER_LEN + udpLength
        val out = ByteBuffer.allocate(total)

        out.put(0x45.toByte())
        out.put(0)
        out.putShort(total.toShort())
        out.putShort(0)
        out.putShort(0x4000.toShort())
        out.put(ttl.toByte())
        out.put(Ip.PROTO_UDP.toByte())
        out.putShort(0)
        out.putInt(sourceAddress)
        out.putInt(destinationAddress)

        out.putShort(sourcePort.toShort())
        out.putShort(destinationPort.toShort())
        out.putShort(udpLength.toShort())
        out.putShort(0) // UDP checksum; zero means "not computed", legal in IPv4
        out.put(payload)

        val bytes = out.array()
        val ipSum = Checksum.of(bytes, 0, Ip.IPV4_HEADER_LEN)
        bytes[10] = (ipSum ushr 8).toByte()
        bytes[11] = (ipSum and 0xFF).toByte()
        return bytes
    }

    /**
     * TCP RST, sent when we have no session for a segment and cannot open one.
     *
     * The alternative is silence, and silence is much worse: the client's SYN
     * gets no answer, it retransmits twice, then fails with a generic timeout.
     * An immediate RST surfaces as "connection refused" in one round trip.
     *
     * Sequence numbers are copied from the offending segment, which is what
     * tells the client which half of the conversation is being reset.
     */
    fun tcpReset(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
    ): ByteArray = PacketWriter.tcpSegment(
        sourceAddress = sourceAddress,
        destinationAddress = destinationAddress,
        sourcePort = sourcePort,
        destinationPort = destinationPort,
        sequenceNumber = sequenceNumber,
        acknowledgementNumber = acknowledgementNumber,
        // RST | ACK: no SYN to echo, so the ACK bit carries the peer's sequence.
        flags = Ip.FLAG_RST or Ip.FLAG_ACK,
        windowSize = 0,
        ttl = TUN_TTL,
    )

    private fun addressOf(packet: ByteArray, length: Int): Int {
        if (length < 12) return 0
        return (packet[12].toInt() and 0xFF shl 24) or
            (packet[13].toInt() and 0xFF shl 16) or
            (packet[14].toInt() and 0xFF shl 8) or
            (packet[15].toInt() and 0xFF)
    }

    const val TUN_TTL = 64
}