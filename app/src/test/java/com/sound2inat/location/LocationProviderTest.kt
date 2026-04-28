package com.sound2inat.location

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocationProviderTest {
    private class Fake(
        private val fix: Fix?,
        private val delayMs: Long,
        private val lastKnown: Fix? = null,
    ) : LocationProvider {
        override suspend fun getCurrent(timeoutMs: Long): Fix? {
            return if (delayMs <= timeoutMs) {
                delay(delayMs)
                fix
            } else {
                lastKnown
            }
        }
    }

    @Test fun `returns fix when delivered before timeout`() = runTest {
        val f = Fix(34.7, 33.04, 5f, 1L)
        val p = Fake(f, delayMs = 1_000)
        val result = p.getCurrent(timeoutMs = 15_000)
        assertThat(result).isEqualTo(f)
    }

    @Test fun `falls back to last known when timeout`() = runTest {
        val last = Fix(0.0, 0.0, null, 0L)
        val p = Fake(null, delayMs = 20_000, lastKnown = last)
        val result = p.getCurrent(timeoutMs = 15_000)
        assertThat(result).isEqualTo(last)
    }

    @Test fun `returns null when no fix and no last known`() = runTest {
        val p = Fake(null, delayMs = 20_000, lastKnown = null)
        val result = p.getCurrent(timeoutMs = 15_000)
        assertThat(result).isNull()
    }
}
