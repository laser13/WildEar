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

/**
 * Unit tests for the staleness guard introduced in [FusedLocationProvider].
 *
 * [FusedLocationProvider] wraps Google Play Services and cannot be
 * instantiated in a JVM test without Robolectric + shadow Play Services.
 * Instead we extract the staleness decision into a testable function and
 * verify it directly. The production code uses the same `nowMs` injection
 * seam, so these tests cover the exact branch.
 *
 * Note: the end-to-end path (live fix timeout → lastLocation → staleness
 * gate) is covered by an instrumented / integration test if needed; the
 * staleness formula itself is fully verified here.
 */
class FusedLocationStalenessGuardTest {

    @Test fun `fix sampled 4 minutes ago is fresh`() {
        val now = 1_000_000L
        val fix = Fix(0.0, 0.0, null, timestampMs = now - 4 * 60_000L)
        assertThat(FusedLocationProvider.isFresh(fix, now)).isTrue()
    }

    @Test fun `fix exactly at 5 minutes boundary is fresh`() {
        val now = 1_000_000L
        val fix = Fix(0.0, 0.0, null, timestampMs = now - 5 * 60_000L)
        assertThat(FusedLocationProvider.isFresh(fix, now)).isTrue()
    }

    @Test fun `fix older than 5 minutes is stale`() {
        val now = 1_000_000L
        val fix = Fix(0.0, 0.0, null, timestampMs = now - 6 * 60_000L)
        assertThat(FusedLocationProvider.isFresh(fix, now)).isFalse()
    }

    @Test fun `fix from 3 days ago is stale`() {
        val now = System.currentTimeMillis()
        val fix = Fix(0.0, 0.0, null, timestampMs = now - 3 * 24 * 60 * 60_000L)
        assertThat(FusedLocationProvider.isFresh(fix, now)).isFalse()
    }
}
