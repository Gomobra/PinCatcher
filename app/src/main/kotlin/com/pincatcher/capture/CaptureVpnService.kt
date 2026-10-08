package com.pincatcher.capture

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.pincatcher.data.FlowRecorder
import com.pincatcher.data.FlowStore
import org.koin.android.ext.android.inject
import java.net.InetAddress

/**
 * Capture tunnel entry point.
 *
 * The system binds this with `BIND_VPN_SERVICE` to render the consent dialog;
 * `establish()` hands us the tun file descriptor that [CaptureEngine] reads.
 *
 * ## The three ways this goes wrong
 *
 *  - **Looping.** Every socket opened *out* of the tunnel - DNS, upstream
 *    connections - has to be passed to [protect], or the kernel routes it back
 *    into our own tun. See [UpstreamDialer].
 *  - **Silent gaps.** QUIC on UDP/443 never reaches a TCP relay, so apps fall
 *    back only if we say so. [com.pincatcher.core.capture.net.PacketRouter]
 *    answers those with an ICMP unreachable rather than dropping them silently.
 *  - **Consent.** `establish()` returns null when the user declined. That is not
 *    an error to retry - it is the user saying no, and the UI has to hear about it.
 */
class CaptureVpnService : VpnService() {

    private val flowStore: FlowStore by inject()
    private val flowRecorder: FlowRecorder by inject()

    @Volatile
    private var sessionId: Long = 0

    @Volatile
    private var engine: CaptureEngine? = null

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var state: CaptureNotifier.State = CaptureNotifier.State(0, 0, emptyList())

    @Volatile
    private var alive = false

    @Volatile
    private var notifierThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
        }

        val targets = intent?.getStringArrayListExtra(EXTRA_TARGET_PACKAGES).orEmpty()

        // establish() returning null is the consent being refused. Report it and
        // stop, rather than carrying on with no tun and a notification claiming a
        // capture is running.
        val descriptor = establishTun(targets)
        if (descriptor == null) {
            Log.w(TAG, "tunnel refused; consent not granted or the VPN slot is taken")
            stopSelf()
            return START_NOT_STICKY
        }

        val upstreamDns = resolveUpstreamDns()
        if (upstreamDns.isEmpty()) {
            Log.w(TAG, "no upstream resolver found; DNS would fail inside the tunnel")
        }

        startForegroundCompat(targets)

        sessionId = flowStore.startSession(
            name = SESSION_NAME,
            mode = if (targets.isEmpty()) MODE_ALL else MODE_TARGETED,
            targetPackages = targets,
        )

        val tun = TunIo(descriptor)
        val notifier = CaptureNotifier(this)
        alive = true
        CaptureControl.publish(running = true, packets = 0, rejected = 0, targets = targets)

        val capture = CaptureEngine(
            tun = tun,
            dialer = UpstreamDialer(this),
            upstreamDns = upstreamDns,
            // Every TCP connection gets a tap; the recorder decides from the first
            // bytes whether there is plaintext HTTP on it worth keeping.
            newTap = { _, _ -> flowRecorder.Tap(sessionId) },
            onLoopSuspected = {
                // Layer 3 of the loop guard, and the last one that can still help.
                Log.e(TAG, "loop guard tripped; stopping capture")
                stopCapture()
            },
        )
        engine = capture

        reader = Thread({
            try {
                capture.run()
            } finally {
                stopCapture()
            }
        }, "pc-tun").apply {
            isDaemon = true
            start()
        }

        // Notification updated from the engine's own counters, so what the
        // notification says and what the tunnel is doing cannot drift apart.
        notifierThread = Thread({
            while (!Thread.currentThread().isInterrupted && alive) {
                Thread.sleep(NOTIFICATION_INTERVAL_MS)
                val stats = capture.stats
                val next = CaptureNotifier.State(stats.read, stats.rejected, targets)
                if (next != state) {
                    state = next
                    CaptureControl.publish(true, stats.read, stats.rejected, targets)
                    runCatching { notifier.update(next) }
                }
            }
        }, "pc-notifier").apply {
            isDaemon = true
            start()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun stopCapture() {
        // Order matters. The notifier thread has to be told first: it is the thing
        // that would otherwise keep re-posting a notification for a capture that
        // has already been cancelled.
        alive = false
        notifierThread?.interrupt()
        notifierThread = null
        if (sessionId != 0L) {
            runCatching { flowStore.endSession(sessionId) }
            sessionId = 0L
        }
        engine?.stop()
        engine = null
        reader?.interrupt()
        reader = null
        runCatching { CaptureNotifier(this).cancel() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        CaptureControl.publish(running = false, packets = 0, rejected = 0, targets = emptyList())
        stopSelf()
    }

    /**
     * Builds the tun.
     *
     * Split out from `onStartCommand` so the routing decision is readable in one
     * place: the address, the routes, the MTU, and the loop-guard exclusions are
     * all here and nowhere else.
     *
     * @param targets packages to capture. Empty means every app except this one.
     */
    private fun establishTun(targets: List<String>): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(SESSION_NAME)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .setMtu(MTU)
            .addRoute(TUN_ADDRESS, TUN_PREFIX)

        // The tun owns DNS. Without this the system resolver stays reachable and
        // apps resolve outside the tunnel, so no hostname ever reaches the capture.
        resolverAddresses().forEach { host ->
            runCatching { builder.addDnsServer(host) }
        }

        // Loop guard layer 1: PinCatcher's own traffic never enters the tunnel it
        // is serving. `protect()` on each socket is layer 2, in UpstreamDialer.
        builder.addDisallowedApplication(packageName)

        if (targets.isEmpty()) {
            // All routes, so a target chosen later does not need a restart.
            builder.addRoute("0.0.0.0", 0)
        } else {
            // Per-app filter (PRD 6.3). One unresolvable package must not abort the
            // whole tunnel: it was uninstalled between picking it and starting.
            targets.forEach { target ->
                if (runCatching { builder.addAllowedApplication(target) }.isFailure) {
                    Log.w(TAG, "no such package to capture: $target")
                }
            }
        }

        return builder.establish()
    }

    /**
     * The system's current DNS servers, as addresses we can relay to.
     *
     * Read through [LinkProperties] because that is where the active network's
     * resolvers actually are. Hardcoding 8.8.8.8 instead would work on most
     * devices and fail on the ones behind a captive portal or a split-horizon
     * resolver, which is where someone running a capture tool most needs it.
     */
    private fun resolverAddresses(): List<String> {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val out = linkedSetOf<String>()
        manager.allNetworks.forEach { network ->
            val properties = runCatching { manager.getLinkProperties(network) }.getOrNull() ?: return@forEach
            properties.dnsServers.mapNotNullTo(out) { link ->
                // toString is the address with its prefix length; taking the text
                // side of the slash avoids depending on which accessor this API
                // level exposes.
                link.toString().substringBefore('/').takeIf { it.isNotEmpty() }
            }
        }
        // The tun address would be a loop, not a resolver.
        return out.filterNot { it == TUN_ADDRESS }
    }

    private fun resolveUpstreamDns(): List<InetAddress> = resolverAddresses().mapNotNull { host ->
        runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    private fun startForegroundCompat(targets: List<String>) {
        val notifier = CaptureNotifier(this)
        notifier.ensureChannel()
        state = CaptureNotifier.State(0, 0, targets)
        startForeground(CaptureNotifier.NOTIFICATION_ID, notifier.build(state))
    }

    companion object {
        const val MODE_ALL = "vpn-all"
        const val MODE_TARGETED = "vpn-targeted"
        const val ACTION_START = "com.pincatcher.capture.START"
        const val ACTION_STOP = "com.pincatcher.capture.STOP"
        const val EXTRA_TARGET_PACKAGES = "target_packages"

        private const val TAG = "CaptureVpnService"
        const val SESSION_NAME = "PinCatcher"
        const val TUN_ADDRESS = "10.111.222.1"
        const val TUN_PREFIX = 32
        const val MTU = 1500
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
    }
}
