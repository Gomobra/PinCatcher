package com.pincatcher.capture

import com.pincatcher.core.capture.net.TcpSession
import com.pincatcher.data.FlowRecorder
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One TCP connection carried by the tunnel.
 *
 * ## Two threads, and why
 *
 * The tun reader thread owns the client-facing side: it feeds segments to the
 * [TcpSession] and writes back whatever the session fabricates. This class owns
 * the upstream socket and gets threads of its own. The reader thread is shared by
 * every connection on the device, so nothing here is allowed to block it - one
 * app that stops reading its socket would otherwise freeze the whole capture.
 * That is why client bytes go through [outbound] instead of straight to the
 * socket, and why reads and writes get a thread each rather than sharing a loop.
 *
 * ## What is not handled
 *
 * No retransmission. Packets written to the tun go to the local kernel and never
 * cross a lossy link, so a missing segment means the peer dropped something, and
 * that surfaces as a timeout instead. Retransmit logic is where userspace TCP
 * stacks grow without bound, and here it would buy nothing.
 */
class Connection(
    val session: TcpSession,
    val tap: FlowRecorder.Tap?,
) {

    private val closed = AtomicBoolean(false)
    private val outbound = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var upstream: Socket? = null

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var writer: Thread? = null

    /**
     * Hands the connection its upstream socket and starts pumping.
     *
     * Nothing runs before this. The thread cannot start earlier because it would
     * find no socket and exit, leaving a SYN answered by a session that then
     * silently never relays anything.
     */
    fun start(socket: Socket, bufferSize: Int) {
        upstream = socket
        reader = Thread({ readLoop(socket, bufferSize) }, "pc-conn-r-${session.clientPort}").apply {
            isDaemon = true
            start()
        }
    }

    /** Bytes from the upstream socket, on the pump thread. */
    fun onUpstreamData(bytes: ByteArray) {
        // Fabricating the reply packets is a side effect of being fed, so the
        // returned actions have nothing left to do here.
        session.onUpstreamData(bytes)
    }

    /** Queues client bytes for the upstream socket. Never blocks. */
    fun toUpstream(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (!outbound.offer(bytes)) outbound.clear()
    }

    /**
     * The upstream has finished.
     *
     * The FIN to the client is fabricated only now. Claiming the client is done
     * while the server is still sending is how a response body gets truncated in
     * a capture that otherwise looks complete.
     */
    fun onUpstreamClosed() {
        if (!closed.compareAndSet(false, true)) return
        session.onUpstreamClosed()
        tap?.onClosed()
        outbound.offer(POISON)
        runCatching { upstream?.close() }
    }

    /** Torn down because the client went away or the tunnel stopped. */
    fun abandon() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { upstream?.close() }
        outbound.offer(POISON)
        reader?.interrupt()
        writer?.interrupt()
    }

    private fun readLoop(socket: Socket, bufferSize: Int) {
        val stream = try {
            socket.getOutputStream()
        } catch (_: IOException) {
            finish(); return
        }
        writer = Thread({ drainOutbound(stream) }, "pc-conn-w-${session.clientPort}").apply {
            isDaemon = true
            start()
        }

        val from = try {
            socket.getInputStream()
        } catch (_: IOException) {
            finish(); return
        }
        val buffer = ByteArray(bufferSize)
        try {
            while (!closed.get()) {
                val read = from.read(buffer)
                if (read < 0) break
                if (read > 0) onUpstreamData(buffer.copyOf(read))
            }
        } catch (_: IOException) {
            // Reset, timeout, or a socket closed underneath us. All of them mean
            // the same thing to the client: this connection is over.
        } finally {
            finish()
        }
    }

    private fun drainOutbound(stream: OutputStream) {
        try {
            while (true) {
                val bytes = outbound.take()
                if (bytes === POISON) break
                stream.write(bytes)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: IOException) {
            finish()
        }
    }

    private fun finish() = onUpstreamClosed()

    /**
     * Identity marker rather than data.
     *
     * An empty array would do as a value but not as a sentinel, so this is
     * deliberately a distinct instance: [toUpstream] never enqueues one, because
     * `TcpSession` does not emit an empty `ToUpstream`.
     */
    private companion object {
        val POISON = ByteArray(0)
    }
}