package com.pincatcher.capture

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

/**
 * Capture tunnel entry point.
 *
 * The system binds this with `BIND_VPN_SERVICE` to render the consent dialog;
 * `establish()` hands us the tun file descriptor that the packet pipeline reads.
 *
 * Design notes that are easy to get wrong:
 *  - Every socket we open *out* of the tunnel (DNS, upstream connections) must
 *    be passed to [protect], otherwise the kernel routes it back into our own
 *    tun and we deadlock.
 *  - UDP/443 must be dropped. Apps negotiate QUIC/HTTP3 over it, which never
 *    reaches a TCP MITM, so without this they silently bypass capture.
 */
class CaptureVpnService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        // TODO: tear down the packet pipeline and the proxy listener.
        super.onDestroy()
    }

    /** Builds the tun interface. Split out so it can be exercised in tests. */
    @Suppress("unused")
    private fun establishTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("PinCatcher")
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addDnsServer(DNS_SERVER)
            .setMtu(MTU)

        // Loop guard layer 1: never route PinCatcher's own traffic into the
        // tunnel it is serving.
        builder.addDisallowedApplication(packageName)

        return builder.establish()
    }

    private companion object {
        const val TUN_ADDRESS = "10.111.222.1"
        const val TUN_PREFIX = 32
        const val DNS_SERVER = "10.111.222.2"
        const val MTU = 1500
    }
}
