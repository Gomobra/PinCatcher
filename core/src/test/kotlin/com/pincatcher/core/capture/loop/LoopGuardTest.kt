package com.pincatcher.core.capture.loop

import com.pincatcher.core.capture.net.Ipv4
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Layer 2 of the loop guard.
 *
 * The test that matters most here is [every captured packet has the tun as its
 * source]: if that ever inverts, this class starts dropping the device's entire
 * traffic while claiming it is preventing a loop.
 */
class LoopGuardTest {

    private val tun = Ipv4.parse("10.111.222.1")

    @Test
    fun `every captured packet has the tun as its source`() {
        // This is the premise the whole class rests on. If Android ever gave apps
        // a different source address behind a tun, classify() would be backwards.
        assertEquals(LoopGuard.Origin.Captured, LoopGuard.classify(tun, tun))
    }

    @Test
    fun `our own escaped traffic is foreign`() {
        // A protect()ed socket bypasses the tun, so a packet that reaches the tun
        // from the physical network address is our traffic coming back.
        assertEquals(LoopGuard.Origin.Foreign, LoopGuard.classify(Ipv4.parse("192.168.1.50"), tun))
        assertEquals(LoopGuard.Origin.Foreign, LoopGuard.classify(Ipv4.parse("127.0.0.1"), tun))
        assertEquals(LoopGuard.Origin.Foreign, LoopGuard.classify(Ipv4.parse("8.8.8.8"), tun))
    }

    @Test
    fun `the broadcast address is foreign`() {
        // 255.255.255.255 can never be the source of an app behind the tun.
        assertEquals(LoopGuard.Origin.Foreign, LoopGuard.classify(Ipv4.parse("255.255.255.255"), tun))
    }

    @Test
    fun `one stray packet does not stop the capture`() {
        // A single mis-protected socket, or one spoofed packet, must not end a
        // capture that is otherwise working.
        val detector = LoopDetector(suspectThreshold = LoopGuard.STOP_AFTER_REPEATS)
        repeat(LoopGuard.STOP_AFTER_REPEATS - 1) {
            assertEquals(
                LoopVerdict.Healthy,
                detector.observe(FOREIGN, isOwnAddress = true, now = 0),
                "the capture must survive a handful of stray packets",
            )
        }
    }

    @Test
    fun `the same source coming back repeatedly stops the capture`() {
        val detector = LoopDetector(suspectThreshold = LoopGuard.STOP_AFTER_REPEATS)
        var verdict = LoopVerdict.Healthy
        repeat(LoopGuard.STOP_AFTER_REPEATS) {
            verdict = detector.observe(FOREIGN, isOwnAddress = true, now = 0)
        }
        assertEquals(LoopVerdict.LoopSuspected, verdict, "the threshold repeat is the signal")
        assertEquals(LoopGuard.STOP_AFTER_REPEATS, detector.worstCount())
    }

    @Test
    fun `one address insisting is different from many addresses once each`() {
        // An escaped socket repeats one address. A burst of unrelated spoofed
        // packets spread across sources must not look the same, which is why the
        // detector is keyed per address.
        val detector = LoopDetector(suspectThreshold = LoopGuard.STOP_AFTER_REPEATS)
        repeat(40) { i ->
            assertEquals(
                LoopVerdict.Healthy,
                detector.observe("10.0.0.$i", isOwnAddress = true, now = 0),
                "spread across sources this is not a loop",
            )
        }
    }

    private companion object {
        const val FOREIGN = "192.168.1.50"
    }
}