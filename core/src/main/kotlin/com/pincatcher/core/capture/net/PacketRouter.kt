package com.pincatcher.core.capture.net

import java.nio.ByteBuffer

/**
 * What the tunnel decided to do with one tun packet.
 *
 * The Android layer turns each of these into work. Keeping the decision pure
 * is what makes the QUIC-drop and unreachable-reply behaviour testable without
 * a device.
 */
sealed interface PacketVerdict {
    /**
     * Hand to the TCP session for this flow.
     *
     * The parsed headers ride along rather than being re-read by the caller:
     * the Android layer has to feed both to `TcpSession.onClientSegment`, and
     * parsing the same 40 bytes twice per packet is work with no second opinion
     * to gain from it.
     */
    data class Tcp(
        val key: FlowKey,
        val ip: Ipv4Header,
        val tcp: TcpHeader,
        val payload: ByteArray,
    ) : PacketVerdict

    /** A DNS query to forward out of the tunnel, protect()ed so it does not recurse. */
    data class Dns(val key: FlowKey, val query: ByteArray) : PacketVerdict

    /**
     * Reply to the client with a synthesised ICMP unreachable and drop the payload.
     *
     * The client's own address and port ride along because [FlowKey] is
     * direction-blind and the reply has to go back to whoever sent the packet, not
     * to whichever end of the key happens to be smaller.
     */
    data class RejectWithUnreachable(
        val key: FlowKey,
        val client: Endpoint,
        val original: Ipv4Header,
        val code: Int,
        val reason: String,
    ) : PacketVerdict

    /** Silently ignored - multicast, broadcast, or a protocol we do not carry. */
    data class Ignore(val reason: String) : PacketVerdict
}

/**
 * Decides what to do with packets arriving from the tun.
 *
 * The interesting rule is the UDP one. Apps negotiate QUIC/HTTP3 over UDP/443,
 * and QUIC never reaches a TCP MITM - so a capture that forwards it looks like
 * it is working while silently missing every Google- or Cloudflare-fronted
 * request. Dropping UDP/443 forces the client down to TCP, where we can see it.
 * We answer with ICMP unreachable so the fallback is immediate rather than
 * waiting out a handshake timeout.
 */
object PacketRouter {

    const val UDP_PORT_DNS = 53
    const val UDP_PORT_QUIC = 443

    fun route(packet: ByteArray, length: Int = packet.size): PacketVerdict? {
        val buffer = ByteBuffer.wrap(packet, 0, length)

        // A non-first fragment carries no transport header. We do not reassemble,
        // so there is nothing honest to do with it.
        val ip = Ipv4Header.parse(buffer) ?: return PacketVerdict.Ignore("not ipv4")
        if (!ip.isFirstFragment) return PacketVerdict.Ignore("non-first fragment")
        if (ip.moreFragments) return PacketVerdict.Ignore("fragmented, no reassembly")
        if (length < ip.totalLength) return PacketVerdict.Ignore("truncated datagram")

        buffer.position(ip.headerLength)
        val payloadLength = ip.totalLength - ip.headerLength

        return when (ip.protocol) {
            Ip.PROTO_TCP -> routeTcp(buffer, ip, payloadLength)
            Ip.PROTO_UDP -> routeUdp(buffer, ip, payloadLength)
            else -> PacketVerdict.Ignore("protocol ${ip.protocol} not carried")
        }
    }

    private fun routeTcp(buffer: ByteBuffer, ip: Ipv4Header, payloadLength: Int): PacketVerdict? {
        val tcp = TcpHeader.parse(buffer) ?: return PacketVerdict.Ignore("bad tcp header")
        // payloadLength is measured from the end of the IP header, so the TCP
        // header has to come off as well. Getting this wrong makes every flow
        // carry 20 bytes of TCP header glued to the front of its body.
        val payloadLengthActual = payloadLength - tcp.dataOffset * 4
        if (payloadLengthActual < 0) return PacketVerdict.Ignore("negative payload")

        val payload = ByteArray(payloadLengthActual)
        if (payloadLengthActual > 0 && buffer.remaining() >= payloadLengthActual) {
            buffer.get(payload)
        }

        return PacketVerdict.Tcp(
            key = FlowKey.of(
                Ip.PROTO_TCP,
                ip.sourceAddress, tcp.sourcePort,
                ip.destinationAddress, tcp.destinationPort,
            ),
            ip = ip,
            tcp = tcp,
            payload = payload,
        )
    }

    private fun routeUdp(buffer: ByteBuffer, ip: Ipv4Header, payloadLength: Int): PacketVerdict {
        val udp = UdpHeader.parse(buffer) ?: return PacketVerdict.Ignore("bad udp header")
        val payload = ByteArray(payloadLength - Ip.UDP_HEADER_LEN)
        if (payload.size > 0 && buffer.remaining() >= payload.size) buffer.get(payload)

        val destinationPort = udp.destinationPort
        val key = FlowKey.of(
            Ip.PROTO_UDP,
            ip.sourceAddress, udp.sourcePort,
            ip.destinationAddress, destinationPort,
        )

        // Broadcast and multicast never get forwarded: we cannot open a usable
        // socket to 255.255.255.255 or 224.0.0.0/4 from inside the tunnel.
        val destination = ip.destinationAddress
        val isMulticast = (destination and 0xF0000000.toInt()) == 0xE0000000.toInt()
        val isBroadcast = destination == 0xFFFFFFFFL.toInt()
        if (isMulticast || isBroadcast) {
            return PacketVerdict.Ignore(if (isBroadcast) "broadcast" else "multicast")
        }

        return when (destinationPort) {
            UDP_PORT_DNS -> PacketVerdict.Dns(key, payload)
            UDP_PORT_QUIC -> PacketVerdict.RejectWithUnreachable(
                key = key,
                client = Endpoint(ip.sourceAddress, udp.sourcePort),
                original = ip,
                code = ICMP_PORT_UNREACHABLE,
                // Said plainly, because this is the single decision that most
                // often looks like a bug to anyone reading a capture log.
                reason = "UDP/$destinationPort dropped: QUIC is invisible to a TCP proxy, " +
                    "so the client falls back to TCP where it can be read",
            )

            else -> PacketVerdict.Ignore("udp/$destinationPort not carried")
        }
    }

    const val ICMP_PORT_UNREACHABLE = 3
}