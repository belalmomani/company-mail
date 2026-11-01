package com.shegrowsdev.mail.notify

/** Reconnect timing for the instant-notification connection (pure, so it can be tested). */
object IdlePolicy {
    const val FIRST_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 60_000L

    /** Wait before reconnect attempt number [attempt] (0 = first retry): 5 s, 10 s, 20 s, 40 s, then 60 s. */
    fun nextDelay(attempt: Int): Long {
        if (attempt <= 0) return FIRST_DELAY_MS
        val shift = minOf(attempt, 10)
        return minOf(FIRST_DELAY_MS shl shift, MAX_DELAY_MS)
    }

    /** A connection that stayed up this long counts as healthy: the next failure starts again at the short delay. */
    const val HEALTHY_AFTER_MS = 60_000L
}
