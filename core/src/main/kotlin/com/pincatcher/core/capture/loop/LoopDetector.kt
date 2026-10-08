package com.pincatcher.core.capture.loop

/**
 * Loop detection, PRD 6.4 Layer 3.
 *
 * Layers 1 and 2 are structural and live in `CaptureVpnService`: the tun
 * builder excludes this package with `addDisallowedApplication`, and every
 * socket we open *out* of the tunnel goes through `VpnService.protect()`. Those
 * two are what actually stop a loop. This class is the backstop for when they
 * fail - a mis-protected socket, an OEM kernel that ignores `protect()`, or a
 * library opening its own socket behind our back.
 *
 * ## What a loop looks like
 *
 * Our own traffic re-enters the tun and comes back to us. That is
 * self-observable: the same 5-tuple arriving from our own tunnel address, over
 * and over, inside a short window. One observation proves nothing - a busy app
 * legitimately opens many connections - so the signal is repetition of *one*
 * tuple, not volume.
 */
class LoopDetector(
    private val suspectThreshold: Int = DEFAULT_SUSPECT_THRESHOLD,
    private val repeatWindowSeconds: Long = DEFAULT_REPEAT_WINDOW_SECONDS,
) {
    private class Seen(val firstSeenAt: Long, var count: Int)

    private val suspects = LinkedHashMap<String, Seen>()

    /**
     * Records one packet and reports whether capture should stop.
     *
     * @param showKey the flow's canonical string, e.g. `10.0.0.2:51514 <-> 1.2.3.4:443`
     * @param isOwnAddress true when the packet's source is this device's own tun
     *   address, which is the signature of our traffic coming back around
     * @param now monotonic seconds, not wall clock
     */
    fun observe(showKey: String, isOwnAddress: Boolean, now: Long): LoopVerdict {
        // Count every tuple, not just the self-addressed ones: the whole point
        // is to see whether one connection is being repeated, and a connection
        // that first looks innocent is still the connection that loops.
        val previous = suspects.remove(showKey)
        val seen = when {
            previous == null -> Seen(firstSeenAt = now, count = 1)
            // Outside the window this is a fresh burst, not a continuation.
            now - previous.firstSeenAt > repeatWindowSeconds -> Seen(firstSeenAt = now, count = 1)
            else -> Seen(firstSeenAt = previous.firstSeenAt, count = previous.count + 1)
        }

        // Bounded: a long capture must not accumulate a key per host ever seen.
        if (suspects.size >= MAX_TRACKED) {
            suspects.entries.minByOrNull { it.value.firstSeenAt }?.let { suspects.remove(it.key) }
        }
        suspects[showKey] = seen

        return when {
            seen.count < suspectThreshold -> LoopVerdict.Healthy
            !isOwnAddress -> LoopVerdict.Watch
            else -> LoopVerdict.LoopSuspected
        }
    }

    fun reset() = suspects.clear()

    /** Highest repetition count currently tracked, for diagnostics. */
    fun worstCount(): Int = suspects.values.maxOfOrNull { it.count } ?: 0

    companion object {
        const val DEFAULT_SUSPECT_THRESHOLD = 8
        const val DEFAULT_REPEAT_WINDOW_SECONDS = 5L
        const val MAX_TRACKED = 512
    }
}

enum class LoopVerdict {
    Healthy,
    Watch,
    LoopSuspected,
}