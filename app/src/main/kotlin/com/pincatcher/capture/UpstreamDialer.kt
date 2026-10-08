package com.pincatcher.capture

import android.net.VpnService
import com.pincatcher.core.capture.net.Ipv4
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Opens the real sockets that carry traffic out of the tunnel.
 *
 * Everything here is the loop guard's layer 2. Layer 1 - excluding our own
 * package from the tun at build time - is the one that normally does the work,
 * but it is not sufficient on its own: `addDisallowedApplication` only covers
 * this UID, so a shared UID, a root-adjacent library opening a socket on another
 * process's behalf, or an OEM kernel that mishandles the exclusion all end with
 * our own traffic coming back into the tun. Without [VpnService.protect] on each
 * socket the kernel routes it straight back to us and the tunnel deadlocks
 * against itself, which is the single nastiest failure this service can have.
 */
class UpstreamDialer(private val service: VpnService) {

    /**
     * Connects to [address]:[port], outside the tunnel.
     *
     * Every exception becomes null. From the caller's side an unreachable
     * upstream is not an error worth propagating: the client is waiting on a
     * SYN, so the right answer is a reset, and a reset is what closing produces.
     */
    fun tcp(address: Int, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): Socket? = try {
        Socket().apply {
            // protect() before connect: once a socket is connected the kernel may
            // already have decided its route, and re-protecting then is a no-op
            // that reads like it worked.
            if (!service.protect(this)) {
                close()
                return null
            }
            tcpNoDelay = true
            // Small enough that a silently dead peer surfaces as a closed session
            // rather than a row that spins forever.
            soTimeout = timeoutMs
            connect(InetSocketAddress(InetAddress.getByAddress(address.toBytes()), port), timeoutMs)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /**
     * A datagram socket for forwarding DNS.
     *
     * Bound to the wildcard, not to the destination: the socket is created before
     * we know who will ask, and the reply is correlated by transaction id rather
     * than by source address.
     */
    fun datagram(timeoutMs: Int = DNS_TIMEOUT_MS): DatagramSocket? = try {
        DatagramSocket().apply {
            soTimeout = timeoutMs
            if (!service.protect(this)) {
                close()
                return null
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun Int.toBytes(): ByteArray = byteArrayOf(
        (this ushr 24).toByte(), (this ushr 16).toByte(),
        (this ushr 8).toByte(), this.toByte(),
    )

    fun describe(address: Int, port: Int): String = "${Ipv4.toString(address)}:$port"

    /** Marks a connect that timed out, which is a different diagnosis from refused. */
    fun isTimeout(failure: Throwable): Boolean = failure is SocketTimeoutException

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val DNS_TIMEOUT_MS = 5_000

        /**
         * Per-pump read size. One MTU, because a tun segment larger than that
         * would have to be fragmented on the way in and there is nothing to gain
         * from reading more in one go.
         */
        const val READ_BUFFER = 16 * 1024
    }
}