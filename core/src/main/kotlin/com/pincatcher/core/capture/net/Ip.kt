package com.pincatcher.core.capture.net

import java.nio.ByteBuffer

/**
 * IPv4 and TCP/UDP header handling for the capture tunnel.
 *
 * Pure Kotlin over [ByteBuffer] with no Android or native dependency, so the
 * whole packet layer is unit-testable off-device.
 *
 * ## Why the packet layer exists at all
 *
 * `VpnService` hands us a tun device carrying raw IP packets from other apps.
 * We terminate TCP ourselves: synthesise the ACKs the sender expects, carry the
 * payload to a real socket, and inject the reply packets back into the tun. So
 * both directions have to be parsed and rebuilt by hand.
 *
 * ## Sequence spaces
 *
 * Two, and conflating them is the classic source of hangs. The *client* space is
 * whatever numbers arrive from the tun; the *peer* space is our own, starting
 * from a random offset per session. They advance independently and must never be
 * compared to each other.
 */
object Ip {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val PROTO_ICMP = 1

    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10
    const val FLAG_URG = 0x20

    /** IPv4 header length in bytes, from the IHL nibble. */
    const val IPV4_HEADER_LEN = 20
    const val TCP_HEADER_LEN = 20
    const val UDP_HEADER_LEN = 8
}

/** A parsed IPv4 datagram, header fields only. */
data class Ipv4Header(
    val totalLength: Int,
    val identification: Int,
    val flagsAndOffset: Int,
    val ttl: Int,
    val protocol: Int,
    val sourceAddress: Int,
    val destinationAddress: Int,
    val headerLength: Int,
) {
    val dontFragment: Boolean get() = flagsAndOffset and 0x4000 != 0
    val moreFragments: Boolean get() = flagsAndOffset and 0x2000 != 0

    /** True when this datagram is the first of a fragment sequence. */
    val isFirstFragment: Boolean get() = flagsAndOffset and 0x1FFF == 0

    /**
     * True when the header claims more payload than the buffer holds. A
     * truncated or spoofed header must never make us read past the array.
     */
    fun bodyLengthIn(buffer: ByteBuffer): Int = totalLength - headerLength

    companion object {
        /**
         * Parses an IPv4 header, leaving the buffer positioned at the start of
         * the payload so the transport header can be parsed straight after.
         *
         * Returns null and leaves the buffer untouched when the header is not
         * trustworthy - a wrong version, a sub-minimum IHL, or a total length
         * that would run past the header.
         */
        fun parse(buffer: ByteBuffer): Ipv4Header? {
            if (buffer.remaining() < Ip.IPV4_HEADER_LEN) return null
            val start = buffer.position()
            val versionAndIhl = buffer.get(start).toInt() and 0xFF
            if (versionAndIhl shr 4 != 4) return null
            val ihl = versionAndIhl and 0x0F
            if (ihl < 5) return null
            val headerLength = ihl * 4
            if (buffer.remaining() < headerLength) return null

            val totalLength = buffer.getShort(start + 2).toInt() and 0xFFFF
            if (totalLength < headerLength) return null

            buffer.position(start + headerLength)
            return Ipv4Header(
                totalLength = totalLength,
                identification = buffer.getShort(start + 4).toInt() and 0xFFFF,
                flagsAndOffset = buffer.getShort(start + 6).toInt() and 0xFFFF,
                ttl = buffer.get(start + 8).toInt() and 0xFF,
                protocol = buffer.get(start + 9).toInt() and 0xFF,
                sourceAddress = buffer.getInt(start + 12),
                destinationAddress = buffer.getInt(start + 16),
                headerLength = headerLength,
            )
        }
    }
}

/** A parsed TCP segment header. */
data class TcpHeader(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val acknowledgementNumber: Long,
    val flags: Int,
    val windowSize: Int,
    val checksum: Int,
    val urgentPointer: Int,
    val dataOffset: Int,
) {
    val syn: Boolean get() = flags and Ip.FLAG_SYN != 0
    val ack: Boolean get() = flags and Ip.FLAG_ACK != 0
    val fin: Boolean get() = flags and Ip.FLAG_FIN != 0
    val rst: Boolean get() = flags and Ip.FLAG_RST != 0
    val psh: Boolean get() = flags and Ip.FLAG_PSH != 0

    /** Delegates to [Seq]; reads better next to the header it belongs to. */
    fun seqDiff(a: Long, b: Long): Long = Seq.diff(a, b)

    /** True when [acknowledgementNumber] is at or past [theirSeq] in wrapped 32-bit space. */
    fun acknowledges(theirSeq: Long): Boolean = Seq.diff(acknowledgementNumber, theirSeq) >= 0

    companion object {
        /**
         * Parses a TCP header, leaving the buffer at the first payload byte.
         * Returns null when the data offset is below the 20-byte minimum or
         * the header would run past the end of the buffer.
         */
        fun parse(buffer: ByteBuffer): TcpHeader? {
            if (buffer.remaining() < Ip.TCP_HEADER_LEN) return null
            val start = buffer.position()
            val dataOffset = (buffer.get(start + 12).toInt() and 0xF0) shr 4
            if (dataOffset < 5) return null
            val headerLength = dataOffset * 4
            if (buffer.remaining() < headerLength) return null

            buffer.position(start + headerLength)
            return TcpHeader(
                sourcePort = buffer.getShort(start).toInt() and 0xFFFF,
                destinationPort = buffer.getShort(start + 2).toInt() and 0xFFFF,
                sequenceNumber = buffer.getInt(start + 4).toLong() and 0xFFFFFFFFL,
                acknowledgementNumber = buffer.getInt(start + 8).toLong() and 0xFFFFFFFFL,
                flags = buffer.get(start + 13).toInt() and 0xFF,
                windowSize = buffer.getShort(start + 14).toInt() and 0xFFFF,
                checksum = buffer.getShort(start + 16).toInt() and 0xFFFF,
                urgentPointer = buffer.getShort(start + 18).toInt() and 0xFFFF,
                dataOffset = dataOffset,
            )
        }
    }
}

/** The internet checksum (RFC 1071). Used by both IP and TCP. */
object Checksum {

    fun of(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    /** Folds a one's-complement sum into the pseudo-header for TCP/UDP. */
    fun pseudoHeaderSum(
        sourceAddress: Int,
        destinationAddress: Int,
        protocol: Int,
        transportLength: Int,
    ): Long = (
        ((sourceAddress ushr 16).toLong() and 0xFFFF) +
            (sourceAddress.toLong() and 0xFFFF) +
            ((destinationAddress ushr 16).toLong() and 0xFFFF) +
            (destinationAddress.toLong() and 0xFFFF) +
            protocol.toLong() +
            transportLength.toLong()
        )

    /**
     * Full TCP checksum including the pseudo-header. The pseudo-header is summed
     * in as a raw integer rather than materialised as bytes, which avoids
     * allocating a throwaway buffer per segment.
     */
    fun tcp(
        sourceAddress: Int,
        destinationAddress: Int,
        tcpSegment: ByteArray,
        offset: Int = 0,
        length: Int = tcpSegment.size - offset,
    ): Int {
        var sum = pseudoHeaderSum(sourceAddress, destinationAddress, Ip.PROTO_TCP, length)
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((tcpSegment[i].toInt() and 0xFF) shl 8) or (tcpSegment[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (tcpSegment[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt().inv() and 0xFFFF
    }
}

/** Helpers for turning 32-bit addresses into text and back. */
object Ipv4 {
    fun toString(address: Int): String =
        "${address ushr 24 and 0xFF}.${address ushr 16 and 0xFF}.${address ushr 8 and 0xFF}.${address and 0xFF}"

    fun parse(text: String): Int {
        val parts = text.split('.')
        require(parts.size == 4) { "not an IPv4 address: $text" }
        return parts.fold(0) { acc, p -> (acc shl 8) or (p.toInt() and 0xFF) }
    }
}