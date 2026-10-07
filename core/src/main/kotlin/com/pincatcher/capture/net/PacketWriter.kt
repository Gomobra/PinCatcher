package com.pincatcher.capture.net

import java.nio.ByteBuffer

/**
 * Builds the reply packets injected back into the tun device.
 *
 * Everything here writes an IPv4 header, a TCP header and a payload in one
 * buffer and fixes up both checksums, because a segment with a wrong checksum
 * is silently dropped by the sender and looks exactly like packet loss.
 */
object PacketWriter {

    /**
     * @param sourceAddress our tun address
     * @param destinationAddress the client
     * @param payload already in peer sequence space, or empty
     */
    fun tcpSegment(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        flags: Int,
        windowSize: Int,
        payload: ByteArray = ByteArray(0),
        identification: Int = 0,
        ttl: Int = 64,
    ): ByteArray {
        val ipHeaderLength = Ip.IPV4_HEADER_LEN
        val tcpHeaderLength = Ip.TCP_HEADER_LEN
        val total = ipHeaderLength + tcpHeaderLength + payload.size
        val buffer = ByteBuffer.allocate(total)

        // --- IPv4 ---
        buffer.put(0x45.toByte()) // version 4, IHL 5 (no options)
        buffer.put(0x00) // DSCP / ECN
        buffer.putShort(total.toShort())
        buffer.putShort(identification.toShort())
        buffer.putShort(0x4000.toShort()) // don't fragment
        buffer.put(ttl.toByte())
        buffer.put(Ip.PROTO_TCP.toByte())
        buffer.putShort(0) // checksum placeholder
        buffer.putInt(sourceAddress)
        buffer.putInt(destinationAddress)

        // --- TCP ---
        val tcpStart = ipHeaderLength
        buffer.putShort(sourcePort.toShort())
        buffer.putShort(destinationPort.toShort())
        buffer.putInt((sequenceNumber and 0xFFFFFFFFL).toInt())
        buffer.putInt((acknowledgementNumber and 0xFFFFFFFFL).toInt())
        buffer.put((5 shl 4).toByte()) // data offset 5, no options
        buffer.put(flags.toByte())
        buffer.putShort(windowSize.toShort())
        buffer.putShort(0) // checksum placeholder
        buffer.putShort(0) // urgent pointer
        buffer.put(payload)

        val bytes = buffer.array()

        // The IP checksum covers only the IPv4 header.
        val ipChecksum = Checksum.of(bytes, 0, ipHeaderLength)
        bytes[10] = (ipChecksum ushr 8).toByte()
        bytes[11] = (ipChecksum and 0xFF).toByte()

        // The TCP checksum covers the pseudo-header plus the whole segment.
        val tcpChecksum = Checksum.tcp(sourceAddress, destinationAddress, bytes, tcpStart, tcpHeaderLength + payload.size)
        val csOffset = tcpStart + 16
        bytes[csOffset] = (tcpChecksum ushr 8).toByte()
        bytes[csOffset + 1] = (tcpChecksum and 0xFF).toByte()

        return bytes
    }
}