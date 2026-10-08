package com.pincatcher.capture

import android.util.Log
import com.pincatcher.core.capture.loop.LoopVerdict
import com.pincatcher.core.capture.net.Ipv4
import com.pincatcher.data.FlowRecorder
import com.pincatcher.core.capture.net.ConnectionTable
import com.pincatcher.core.capture.net.Endpoint
import com.pincatcher.core.capture.net.FlowKey
import com.pincatcher.core.capture.net.PacketRouter
import com.pincatcher.core.capture.net.PacketVerdict
import com.pincatcher.core.capture.net.ReplyPackets
import com.pincatcher.core.capture.net.TcpSession
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * The packet loop: reads the tun, decides, acts.
 *
 * ## One reader thread
 *
 * The tun is one file descriptor, so reads from it must be serialised. One thread
 * doing all of them is simpler than a pool and also the honest limit: the decision
 * per packet is microseconds of parsing.
 *
 * Everything that can block is pushed off to a thread of its own. A reader that
 * blocks on a connect is a tunnel that stops capturing for every app on the
 * device while one app waits for a server that is not answering.
 *
 * ## Why DNS is forwarded rather than answered
 *
 * The tun advertises a DNS server, so every app resolves through us. We do not
 * implement resolution: the query is relayed to the device's real resolvers and
 * the answer handed back. An invented answer would break every app's idea of the
 * network, and a cache of our own is a second source of stale answers sitting on
 * top of the system's.
 *
 * The relay socket is `protect()`ed, or the query is routed back into this tun,
 * arrives here again unanswered, and the app waits out its full timeout.
 */
class CaptureEngine(
    private val tun: TunIo,
    private val dialer: UpstreamDialer,
    private val upstreamDns: List<InetAddress>,
    private val newTap: (FlowKey, Int) -> FlowRecorder.Tap?,
    private val onLoopSuspected: (LoopVerdict) -> Unit,
) {

    private val table = ConnectionTable()
    private val resolver = Executors.newFixedThreadPool(
        RESOLVER_THREADS,
        ThreadFactory { r -> Thread(r, "pc-dns").apply { isDaemon = true } },
    )

    @Volatile
    private var running = false

    private val packetsRead = AtomicLong()
    private val packetsRejected = AtomicLong()

    val stats: Stats get() = Stats(packetsRead.get(), packetsRejected.get(), table.size)

    data class Stats(val read: Long, val rejected: Long, val connections: Int)

    /** Blocks until [stop] or until the tun goes away. */
    fun run() {
        running = true
        val buffer = ByteArray(TunIo.BUFFER_SIZE)
        while (running) {
            val length = tun.read(buffer)
            if (length < 0) break
            packetsRead.incrementAndGet()
            try {
                handle(buffer.copyOf(length), length)
            } catch (e: Exception) {
                // A malformed packet must never kill the tunnel. Losing one packet
                // is invisible; losing the capture is not.
                Log.w(TAG, "dropped a packet: ${e.message}")
            }
        }
    }

    /**
     * Stops the loop and releases the tun.
     *
     * Closing the descriptor is not optional bookkeeping: the reader thread is
     * blocked inside a file read, and a read on a file descriptor is not
     * interruptible. Without the close the thread sits there for the life of the
     * process, still holding the tunnel open after the user asked to stop.
     */
    fun stop() {
        running = false
        table.drain().forEach { (it as? Connection)?.abandon() }
        resolver.shutdownNow()
        tun.close()
    }

    private fun handle(packet: ByteArray, length: Int) {
        when (val verdict = PacketRouter.route(packet, length)) {
            is PacketVerdict.Tcp -> onTcp(verdict)
            is PacketVerdict.Dns -> resolver.execute { forwardDns(verdict) }
            is PacketVerdict.RejectWithUnreachable -> {
                packetsRejected.incrementAndGet()
                tun.write(
                    ReplyPackets.icmpUnreachable(
                        original = packet,
                        originalLength = length,
                        destinationAddress = verdict.client.address,
                        destinationPort = verdict.client.port,
                        code = verdict.code,
                    ),
                )
            }

            is PacketVerdict.Ignore, null -> Unit
        }
    }

    private fun onTcp(verdict: PacketVerdict.Tcp) {
        val existing = table.get<Connection>(verdict.key, now = now())
        if (existing != null) {
            feed(existing, verdict)
            return
        }
        // Only a SYN opens a session. A segment for an unknown flow means the SYN
        // was lost or the entry was evicted, and answering it would fabricate a
        // connection the client never asked for.
        if (!verdict.tcp.syn || verdict.tcp.rst) return

        val connection = Connection(
            session = TcpSession(
                clientAddress = verdict.ip.sourceAddress,
                clientPort = verdict.tcp.sourcePort,
                serverAddress = verdict.ip.destinationAddress,
                serverPort = verdict.tcp.destinationPort,
                // A random start keeps our sequence numbers from looking like a
                // fixed signature to anything that has seen this tool before.
                initialPeerSequence = kotlin.random.Random.nextLong() and 0xFFFFFFFFL,
                emit = tun::write,
            ),
            tap = newTap(verdict.key, verdict.ip.destinationAddress),
        )
        table.put(verdict.key, connection, now = now())
        feed(connection, verdict)
    }

    private fun feed(connection: Connection, verdict: PacketVerdict.Tcp) {
        connection.tap?.onClientBytes(verdict.payload, verdict.payload.size)
        connection.session.onClientSegment(verdict.ip, verdict.tcp, verdict.payload)
            .forEach { action ->
                when (action) {
                    is TcpSession.Action.ToUpstream -> connection.toUpstream(action.payload)
                    TcpSession.Action.ConnectUpstream -> connectUpstream(connection, verdict)
                    TcpSession.Action.CloseUpstream -> connection.abandon()
                    is TcpSession.Action.ToClient -> Unit
                }
            }
    }

    /**
     * Opens the real socket for a session, here rather than on the reader thread.
     * A connect can take its whole timeout to fail, and failing to reach a server
     * is exactly when several connections arrive at once.
     */
    private fun connectUpstream(connection: Connection, verdict: PacketVerdict.Tcp) {
        val loopVerdict = table.onPacketObserved(verdict.key.show, isOwnAddress = false, now = now())
        if (loopVerdict == LoopVerdict.LoopSuspected) {
            onLoopSuspected(loopVerdict)
            return
        }
        val socket = dialer.tcp(verdict.ip.destinationAddress, verdict.tcp.destinationPort)
        if (socket == null) {
            connection.abandon()
            return
        }
        connection.start(socket, UpstreamDialer.READ_BUFFER)
    }

    /**
     * Relays a DNS query and writes the answer back into the tun.
     *
     * The reply is sent from the address the client actually queried. Anything
     * else and the client drops it as arriving from an address it never asked,
     * which looks exactly like a server that does not answer.
     */
    private fun forwardDns(verdict: PacketVerdict.Dns) {
        if (verdict.query.isEmpty()) return
        val client = clientEndOf(verdict.key) ?: return
        val server = dialer.datagram() ?: return
        try {
            for (resolverAddress in upstreamDns) {
                // The reply is rebuilt with this address, so it has to be the
                // packed form the packet writer speaks.
                val serverAddress = Ipv4.parse(resolverAddress.hostAddress ?: continue)
                server.send(DatagramPacket(verdict.query, verdict.query.size, resolverAddress, DNS_PORT))
                server.soTimeout = UpstreamDialer.DNS_TIMEOUT_MS
                val reply = DatagramPacket(ByteArray(MAX_DNS_REPLY), MAX_DNS_REPLY)
                try {
                    server.receive(reply)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                // receive() fills the packet rather than returning a length, so the
                // bytes actually read live on it.
                if (reply.length <= 0) continue

                tun.write(
                    ReplyPackets.udpDatagram(
                        sourceAddress = serverAddress,
                        destinationAddress = client.address,
                        sourcePort = DNS_PORT,
                        destinationPort = client.port,
                        payload = reply.data.copyOf(reply.length),
                    ),
                )
                return
            }
        } catch (_: Exception) {
            // Nothing worth reporting: the client falls back to another resolver,
            // and logging a failure per query would drown the log.
        } finally {
            runCatching { server.close() }
        }
    }

    /**
     * The client end of a DNS flow.
     *
     * [FlowKey] is deliberately direction-blind, so the client is found by being
     * the side that is not the well-known port. For DNS that is exact: the server
     * is on 53 and the client is not.
     */
    private fun clientEndOf(key: FlowKey): Endpoint? =
        if (key.first.port == DNS_PORT) key.second else key.first

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val TAG = "CaptureEngine"
        const val DNS_PORT = 53
        const val RESOLVER_THREADS = 4
        const val MAX_DNS_REPLY = 4096
    }
}