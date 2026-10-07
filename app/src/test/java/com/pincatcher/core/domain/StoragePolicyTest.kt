package com.pincatcher.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StoragePolicyTest {

    @Test
    fun `trims only when over the byte budget`() {
        val policy = StoragePolicy(maxStorageBytes = 1000)
        assertFalse(policy.shouldTrim(1000), "exactly at budget is not over")
        assertTrue(policy.shouldTrim(1001))
    }

    @Test
    fun `cutoff is one max-age behind now`() {
        val policy = StoragePolicy(maxAgeMs = 1_000)
        assertEquals(9_000, policy.ageCutoff(now = 10_000))
    }

    @Test
    fun `rejects nonsensical limits`() {
        assertThrows<IllegalArgumentException> { StoragePolicy(maxFlows = 0) }
        assertThrows<IllegalArgumentException> { StoragePolicy(maxStorageBytes = 0) }
        assertThrows<IllegalArgumentException> { StoragePolicy(maxAgeMs = -1) }
    }

    @Test
    fun `age trim only bites once the oldest retained flow is actually stale`() {
        val policy = StoragePolicy(maxAgeMs = 1_000, maxFlows = 100)
        val now = 10_000L

        // Oldest retained flow is fresh -> cap still applies, nothing age-deleted.
        assertEquals(100, policy.maxFlowsAfterAgeTrim(currentFlows = 40, oldestRetainedAt = 9_500, now = now))

        // Oldest retained flow is already past the cutoff -> age trim has run,
        // so the flow cap is no longer the binding constraint.
        assertEquals(40, policy.maxFlowsAfterAgeTrim(currentFlows = 40, oldestRetainedAt = 8_000, now = now))
    }

    @Test
    fun `defaults match the PRD ring-buffer policy`() {
        val policy = StoragePolicy()
        assertEquals(5_000, policy.maxFlows)
        assertEquals(50L * 1024 * 1024, policy.maxStorageBytes)
        assertEquals(86_400_000L, policy.maxAgeMs)
    }
}
