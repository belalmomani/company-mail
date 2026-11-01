package com.shegrowsdev.mail.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdlePolicyTest {
    @Test fun delaysGrowAndAreCapped() {
        val delays = (0..12).map(IdlePolicy::nextDelay)
        assertEquals(5_000L, delays[0])
        assertEquals(10_000L, delays[1])
        assertEquals(20_000L, delays[2])
        assertEquals(40_000L, delays[3])
        assertTrue(delays.drop(4).all { it == 60_000L })
        assertEquals(delays.sorted(), delays) // never shrinks
    }

    @Test fun negativeOrHugeAttemptsStayInRange() {
        assertEquals(5_000L, IdlePolicy.nextDelay(-3))
        assertEquals(60_000L, IdlePolicy.nextDelay(10_000))
    }
}
