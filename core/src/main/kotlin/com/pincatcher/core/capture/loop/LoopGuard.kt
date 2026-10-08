package com.pincatcher.core.capture.loop

/**
 * Layer 2 of the loop guard, as a filter rather than a detector (PRD 6.4).
 *
 * ## What a loop looks like from inside the tun
 *
 * The intuition that "a packet from our own address means we looped" is wrong on
 * Android, and getting it wrong here drops every real packet the device has.
 * When a VPN is up the tun *is* the default route, so apps send with the tun
 * address as their source. Every captured app packet therefore has that source.
 *
 * Which means the signature of a loop is the opposite: a packet in the tun whose
 * source is *not* the tun address. That can only be
 *
 *  - our own upstream traffic that `protect()` failed to exclude — a socket that
 *    escaped, a shared UID, an OEM kernel that mishandles the exclusion — in
 *    which case it has just been routed back to us and will keep arriving; or
 *  - a spoof, which has no business being here either.
 *
 * Both are dropped without being answered, because answering them is how a
 * tunnel gets into a conversation with itself.
 *
 * Layer 1 (`addDisallowedApplication`) is what normally prevents this. Layer 2 is
 * what keeps one failure from becoming a device-wide stall.
 */
object LoopGuard {

    /** What a packet's source address means, given the tun's own address. */
    enum class Origin {
        /** From an app behind the tunnel. Hand it to the engine. */
        Captured,

        /**
         * Not from behind the tunnel: our own traffic that came back around, or a
         * spoof. Drop it silently.
         */
        Foreign,
    }

    /**
     * @param sourceAddress the packet's IPv4 source, packed
     * @param tunAddress this tunnel's address, packed
     */
    fun classify(sourceAddress: Int, tunAddress: Int): Origin =
        if (sourceAddress == tunAddress) Origin.Captured else Origin.Foreign

    /**
     * How many repeats of one foreign source stop the capture.
     *
     * One is not interesting: a single mis-protected socket, or one stray packet
     * from a neighbouring device on the same subnet, both look like a single
     * occurrence. Repetition is the signal, and [LoopDetector] is what counts it -
     * this is only the value it counts against, kept here so the filter and the
     * threshold describing it are read together.
     */
    const val STOP_AFTER_REPEATS = 8
}