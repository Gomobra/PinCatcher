package com.pincatcher.core.capture.net

/**
 * Signed 32-bit sequence arithmetic.
 *
 * Isolated because getting it wrong is the classic way to hang a TCP proxy: an
 * acknowledgement that crosses the wrap point looks like it went *backwards*,
 * the segment is discarded as stale, and the connection stalls with no error.
 */
object Seq {
    const val MODULUS = 0x1_0000_0000L
    private const val HALF = 0x8000_0000L

    /** `a - b` in wrapped 32-bit space, as a signed value. */
    fun diff(a: Long, b: Long): Long {
        val d = (a - b) and 0xFFFFFFFFL
        return if (d >= HALF) d - MODULUS else d
    }

    fun plus(seq: Long, n: Long): Long = (seq + n) and 0xFFFFFFFFL
}

/**
 * A user-space TCP endpoint for one tun connection.
 *
 * ## The problem
 *
 * The app behind the tun believes it is talking to a server on the internet.
 * It is not: we are. Every ACK it waits for has to be invented, and every byte
 * it sends has to be acknowledged before more is accepted.
 *
 * ## Two sequence spaces
 *
 * [clientSeq] is theirs, observed from the tun. [peerSeq] is ours, fabricated,
 * starting at a random offset. They advance independently and are never
 * compared across spaces.
 *
 * ## Two windows, easily confused
 *
 * [ourWindow] is what we advertise as willing to receive - it bounds how far
 * ahead a client's acknowledgement may legitimately run. [theirWindow] is what
 * the client advertised - it bounds how much we may inject at them. Using one
 * for both silently accepts spoofed acknowledgements or overruns the peer's
 * buffer.
 *
 * ## Why no congestion control
 *
 * Packets written to the tun go to the local kernel and never cross a lossy
 * link, so retransmission exists only as a backstop for a peer that dropped
 * something. Respecting the advertised window and dropping anything outside it
 * is enough, and it keeps this class small enough to read.
 *
 * Deliberately not a full RFC 793 implementation: no window scaling (the peer
 * window is used as-is), no timestamps option, no keepalive timers, and no
 * reassembly buffer - out-of-order segments are dropped, not queued.
 */
class TcpSession(
    val clientAddress: Int,
    val clientPort: Int,
    val serverAddress: Int,
    val serverPort: Int,
    initialPeerSequence: Long,
    private val emit: (ByteArray) -> Unit,
) {
    /** What the state machine wants the caller to do next. */
    sealed interface Action {
        /** Open a real socket to the destination and start relaying. */
        data object ConnectUpstream : Action

        /** Bytes from the client, ready for the upstream socket. */
        data class ToUpstream(val payload: ByteArray) : Action

        /** Bytes from the upstream socket, ready for the client. */
        data class ToClient(val payload: ByteArray) : Action

        /** The session is finished; tear the upstream socket down. */
        data object CloseUpstream : Action
    }

    enum class State { SYN_RECEIVED, ESTABLISHED, CLOSING, CLOSED }

    var state: State = State.SYN_RECEIVED
        private set

    /** Their next expected sequence number. */
    var clientSeq: Long = 0
        private set

    /** Our next sequence number. */
    var peerSeq: Long = initialPeerSequence and 0xFFFFFFFFL
        private set

    /** What we advertise as willing to receive. */
    var ourWindow: Int = DEFAULT_WINDOW
        private set

    /** What the client advertised as willing to receive. */
    var theirWindow: Int = DEFAULT_WINDOW
        private set

    /** Diagnostics only - nothing schedules a retransmit timer off this. */
    var droppedSegments: Int = 0
        private set

    /**
     * Feeds a segment read from the tun.
     *
     * Returns what the caller should do. An unparseable or out-of-window
     * segment is dropped, which is the correct response: acting on it means
     * acting on a sequence number the peer chose.
     */
    fun onClientSegment(ip: Ipv4Header, tcp: TcpHeader, payload: ByteArray): List<Action> {
        val actions = mutableListOf<Action>()

        if (tcp.rst) {
            state = State.CLOSED
            return actions + Action.CloseUpstream
        }

        theirWindow = if (tcp.windowSize == 0) DEFAULT_WINDOW else tcp.windowSize

        if (tcp.syn) {
            // A SYN establishes the space, so it is accepted before any window
            // check would make sense.
            clientSeq = Seq.plus(tcp.sequenceNumber, 1)
            emitSegment(flags = Ip.FLAG_SYN or Ip.FLAG_ACK, acknowledgement = clientSeq)
            state = State.ESTABLISHED
            return actions + Action.ConnectUpstream
        }

        if (state == State.SYN_RECEIVED) return actions

        // The acknowledgement must fall inside what we have actually offered to
        // receive. Behind clientSeq is a stale retransmit; ahead of
        // clientSeq + ourWindow claims data that was never sent.
        val ahead = Seq.diff(tcp.acknowledgementNumber, clientSeq)
        if (ahead < 0 || ahead > ourWindow) {
            droppedSegments++
            return actions
        }

        if (payload.isNotEmpty()) {
            clientSeq = Seq.plus(clientSeq, payload.size.toLong())
            actions += Action.ToUpstream(payload)
            // Acknowledge before the upstream socket can block. An
            // unacknowledged segment makes the client retransmit, and we would
            // then drop the retransmit as out-of-window.
            emitSegment(flags = Ip.FLAG_ACK, acknowledgement = clientSeq)
        }

        if (tcp.fin) {
            clientSeq = Seq.plus(clientSeq, 1)
            emitSegment(flags = Ip.FLAG_ACK, acknowledgement = clientSeq)
            state = State.CLOSING
            actions += Action.CloseUpstream
        }

        return actions
    }

    /** Feeds bytes received from the real upstream socket. */
    fun onUpstreamData(payload: ByteArray): List<Action> {
        if (state == State.CLOSED || payload.isEmpty()) return emptyList()
        emitSegment(flags = Ip.FLAG_ACK or Ip.FLAG_PSH, acknowledgement = clientSeq, payload = payload)
        return listOf(Action.ToClient(payload))
    }

    /** Closes our side once the upstream has closed. */
    fun onUpstreamClosed(): List<Action> {
        if (state == State.CLOSED) return emptyList()
        emitSegment(flags = Ip.FLAG_ACK or Ip.FLAG_FIN, acknowledgement = clientSeq)
        state = State.CLOSED
        return listOf(Action.CloseUpstream)
    }

    private fun emitSegment(flags: Int, acknowledgement: Long, payload: ByteArray = ByteArray(0)) {
        emit(
            PacketWriter.tcpSegment(
                sourceAddress = serverAddress,
                destinationAddress = clientAddress,
                sourcePort = serverPort,
                destinationPort = clientPort,
                sequenceNumber = peerSeq,
                acknowledgementNumber = acknowledgement,
                flags = flags,
                windowSize = ourWindow.coerceIn(0, MAX_WINDOW),
                payload = payload,
            ),
        )
        // SYN and FIN each consume one sequence number; data consumes its length.
        if (flags and (Ip.FLAG_SYN or Ip.FLAG_FIN) != 0) {
            peerSeq = Seq.plus(peerSeq, 1)
        }
        if (payload.isNotEmpty()) {
            peerSeq = Seq.plus(peerSeq, payload.size.toLong())
        }
    }

    companion object {
        const val DEFAULT_WINDOW = 65535
        const val MAX_WINDOW = 0xFFFF
    }
}