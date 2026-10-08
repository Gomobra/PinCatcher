package com.pincatcher.capture

/**
 * The tunnel's live status, published by the service for the UI to read.
 *
 * ## Why the state lives here
 *
 * Android has no API for asking whether a VpnService of yours is running. The
 * nearest thing is whether consent has been granted, which is a different
 * question: consent can be granted while nothing is capturing, and revoked while a
 * capture is running. So the service publishes its own state and this reads it.
 * One process, so a plain object is enough - no binder, nothing to keep in sync
 * with a lifecycle, and nothing to leak.
 */
object CaptureControl {

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var packetsRead: Long = 0
        private set

    /** QUIC packets answered with an ICMP unreachable, forcing the TCP fallback. */
    @Volatile
    var quicBlocked: Long = 0
        private set

    @Volatile
    var targetPackages: List<String> = emptyList()
        private set

    internal fun publish(running: Boolean, packets: Long, rejected: Long, targets: List<String>) {
        this.running = running
        packetsRead = packets
        quicBlocked = rejected
        targetPackages = targets
    }

    fun stop(context: android.content.Context) {
        context.startService(
            android.content.Intent(context, CaptureVpnService::class.java)
                .setAction(CaptureVpnService.ACTION_STOP),
        )
    }
}