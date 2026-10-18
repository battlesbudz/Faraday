package com.battlesbudz.jarvis.v2.ai.storage

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RetryPolicyTest {
    private fun deterministic() = RetryPolicy(jitter = { it })

    @Test fun delaysGrowExponentiallyFromBase() {
        val policy = deterministic()
        assertEquals(2_000L, policy.delayForAttempt(1))
        assertEquals(4_000L, policy.delayForAttempt(2))
        assertEquals(8_000L, policy.delayForAttempt(3))
        assertEquals(16_000L, policy.delayForAttempt(4))
    }

    @Test fun delaysAreCappedAtMax() {
        val policy = deterministic()
        assertEquals(300_000L, policy.delayForAttempt(20))
        assertEquals(300_000L, policy.delayForAttempt(100))
        // Custom cap is honored too.
        val small = RetryPolicy(baseDelayMs = 1_000L, maxDelayMs = 5_000L, jitter = { it })
        assertEquals(5_000L, small.delayForAttempt(10))
    }

    @Test fun jitterStaysWithinBounds() {
        val policy = RetryPolicy()
        repeat(200) {
            val delay = policy.delayForAttempt(3)
            assertTrue("jittered delay $delay out of bounds", delay in 0L..300_000L)
        }
    }

    @Test fun attemptMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { deterministic().delayForAttempt(0) }
    }

    @Test fun networkErrorsAreRetryable() {
        val policy = deterministic()
        assertTrue(policy.isRetryable(SocketTimeoutException("timed out")))
        assertTrue(policy.isRetryable(UnknownHostException("no route")))
        assertTrue(policy.isRetryable(ConnectException("refused")))
        assertTrue(policy.isRetryable(EOFException("truncated")))
        assertTrue(policy.isRetryable(IOException("broken pipe")))
    }

    @Test fun permanentErrorsAndCancellationAreNotRetryable() {
        val policy = deterministic()
        assertFalse(policy.isRetryable(IllegalStateException("bad range")))
        assertFalse(policy.isRetryable(IllegalArgumentException("bad url")))
        assertFalse(policy.isRetryable(CancellationException("user cancelled")))
    }
}
