package com.pincatcher.core.domain

/**
 * Ring-buffer budget for a capture session (PRD 10.3).
 *
 * Trim triggers on whichever limit is hit first, oldest flow first. Pure so it
 * can be unit-tested without a database; the DAO does the actual deleting.
 */
data class StoragePolicy(
    val maxFlows: Int = DEFAULT_MAX_FLOWS,
    val maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES,
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    init {
        require(maxFlows > 0) { "maxFlows must be positive" }
        require(maxStorageBytes > 0) { "maxStorageBytes must be positive" }
        require(maxAgeMs > 0) { "maxAgeMs must be positive" }
    }

    /** True when [storedBytes] exceeds the budget and a trim is due. */
    fun shouldTrim(storedBytes: Long): Boolean = storedBytes > maxStorageBytes

    /** Flows older than this may be deleted. */
    fun ageCutoff(now: Long): Long = now - maxAgeMs

    /** Surviving flow count, given the current count, after an age-based trim. */
    fun maxFlowsAfterAgeTrim(currentFlows: Int, oldestRetainedAt: Long, now: Long): Int =
        if (oldestRetainedAt < ageCutoff(now)) currentFlows else maxFlows

    companion object {
        const val DEFAULT_MAX_FLOWS = 5_000
        const val DEFAULT_MAX_STORAGE_BYTES = 50L * 1024 * 1024
        const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
