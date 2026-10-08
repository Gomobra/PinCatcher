package com.pincatcher.core.capture.net

import java.nio.ByteBuffer

/** One end of a flow. Ordered so a flow key can canonicalise its two ends. */
data class Endpoint(val address: Int, val port: Int) : Comparable<Endpoint> {
    override fun compareTo(other: Endpoint): Int {
        val byAddress = address.compareTo(other.address)
        return if (byAddress != 0) byAddress else port.compareTo(other.port)
    }

    override fun toString(): String = "${Ipv4.toString(address)}:$port"
}

/**
 * Identity of a tun connection.
 *
 * The two endpoints are stored in a canonical order rather than as
 * "source/destination". A TCP connection is one object for its whole life even
 * though its packets arrive with source and destination swapped, so
 * normalising at construction is what stops the table opening two sessions for
 * one connection. Canonical order is by address then port, ascending.
 *
 * Nothing here decides which end is the client. That is a presentation
 * question, and guessing it from port numbers goes wrong on exactly the
 * traffic a capture tool exists to look at.
 */
data class FlowKey private constructor(
    val protocol: Int,
    val first: Endpoint,
    val second: Endpoint,
) {
    val show: String get() = "$first <-> $second"

    companion object {
        fun of(protocol: Int, srcAddress: Int, srcPort: Int, dstAddress: Int, dstPort: Int): FlowKey {
            val a = Endpoint(srcAddress, srcPort)
            val b = Endpoint(dstAddress, dstPort)
            return if (a <= b) FlowKey(protocol, a, b) else FlowKey(protocol, b, a)
        }

        /** Parses straight off a tun packet, consuming the transport header. */
        fun fromPacket(buffer: ByteBuffer): FlowKey? {
            val ip = Ipv4Header.parse(buffer) ?: return null
            buffer.position(ip.headerLength)
            return when (ip.protocol) {
                Ip.PROTO_TCP -> TcpHeader.parse(buffer)?.let {
                    of(Ip.PROTO_TCP, ip.sourceAddress, it.sourcePort, ip.destinationAddress, it.destinationPort)
                }

                Ip.PROTO_UDP -> UdpHeader.parse(buffer)?.let {
                    of(Ip.PROTO_UDP, ip.sourceAddress, it.sourcePort, ip.destinationAddress, it.destinationPort)
                }

                else -> null
            }
        }
    }
}

data class UdpHeader(
    val sourcePort: Int,
    val destinationPort: Int,
    val length: Int,
    val checksum: Int,
) {
    companion object {
        /**
         * Advances the buffer to the payload so the caller can read it without
         * knowing the header length. Returns null on a truncated header rather
         * than throwing: tun delivers whatever the wire gave us.
         */
        fun parse(buffer: ByteBuffer): UdpHeader? {
            if (buffer.remaining() < Ip.UDP_HEADER_LEN) return null
            val start = buffer.position()
            val header = UdpHeader(
                sourcePort = buffer.getShort(start).toInt() and 0xFFFF,
                destinationPort = buffer.getShort(start + 2).toInt() and 0xFFFF,
                length = buffer.getShort(start + 4).toInt() and 0xFFFF,
                checksum = buffer.getShort(start + 6).toInt() and 0xFFFF,
            )
            buffer.position(start + Ip.UDP_HEADER_LEN)
            return header
        }
    }
}