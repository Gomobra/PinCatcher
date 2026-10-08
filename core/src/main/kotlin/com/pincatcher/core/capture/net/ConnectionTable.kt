package com.pincatcher.core.capture.net

import com.pincatcher.core.capture.loop.LoopDetector
import com.pincatcher.core.capture.loop.LoopVerdict

/**
 * Which connections the tunnel is currently carrying.
 *
 * Bounded on purpose. A long capture on a chatty device will open far more
 * connections than the ring buffer keeps flows, and a table that only grows is
 * how a capture app gets OOM-killed on exactly the device it is meant to help
 * with. Eviction is oldest-touched-first, and the oldest entries are the least
 * interesting: a connection idle for the whole timeout is not something anyone
 * is reading.
 *
 * Not thread-safe. The tun reader owns it.
 */
class ConnectionTable(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
) {
    private class Entry<T>(val value: T, var lastTouchedAt: Long)

    private val entries = LinkedHashMap<FlowKey, Entry<Any>>(maxEntries)

    /** Tracks whether any socket we opened escaped the tunnel. Feeds the loop guard. */
    val loopDetector = LoopDetector()

    val size: Int get() = entries.size

    fun <T : Any> get(key: FlowKey, now: Long): T? {
        val entry = entries[key] ?: return null
        entry.lastTouchedAt = now
        @Suppress("UNCHECKED_CAST")
        return entry.value as T
    }

    fun <T : Any> put(key: FlowKey, value: T, now: Long) {
        entries.remove(key)
        entries[key] = Entry(value, now)
        evict(now)
    }

    fun remove(key: FlowKey): Boolean = entries.remove(key) != null

    /** Closes and forgets every entry, newest last. Used when capture stops. */
    fun drain(): List<Any> {
        val out = entries.values.map { it.value }
        entries.clear()
        return out
    }

    /** Keys idle beyond the timeout, oldest first. */
    fun stale(now: Long): List<FlowKey> =
        entries.entries.filter { now - it.value.lastTouchedAt > idleTimeoutMs }
            .sortedBy { it.value.lastTouchedAt }
            .map { it.key }

    fun onPacketObserved(showKey: String, isOwnAddress: Boolean, now: Long): LoopVerdict =
        loopDetector.observe(showKey, isOwnAddress, now)

    private fun evict(now: Long) {
        // Drop expired first; it is usually enough and keeps ordering honest.
        stale(now).forEach { entries.remove(it) }
        // Still over budget: shed oldest-touched.
        while (entries.size > maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value.lastTouchedAt }?.key ?: break
            entries.remove(oldest)
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 512
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
    }
}