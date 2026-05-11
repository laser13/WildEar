package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.RegionalStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RegionalStatusRepositoryTest {

    private fun det(name: String) = AggregatedDetection(name, null, 0.8f, 3, 0L, 1000L)

    private class FakeFilter(
        private val result: RegionalStatus = RegionalStatus.CONFIRMED,
    ) : RegionalStatusRepository.Annotator {
        var calls = 0

        override suspend fun annotate(name: String, lat: Double, lon: Double): RegionalStatus {
            calls++
            return result
        }
    }

    // ─── cache hit ────────────────────────────────────────────────────────────

    @Test fun `second call with same coords returns cached value without re-fetching`() = runTest {
        val fake = FakeFilter()
        val repo = RegionalStatusRepository.forTest(annotator = fake)
        repo.get("Parus major", 51.5, 0.1)
        repo.get("Parus major", 51.5, 0.1)
        assertThat(fake.calls).isEqualTo(1)
    }

    @Test fun `cached value matches fetched value`() = runTest {
        val fake = FakeFilter(RegionalStatus.NOT_CONFIRMED)
        val repo = RegionalStatusRepository.forTest(annotator = fake)
        val first = repo.get("Parus major", 51.5, 0.1)
        val second = repo.get("Parus major", 51.5, 0.1)
        assertThat(first).isEqualTo(RegionalStatus.NOT_CONFIRMED)
        assertThat(second).isEqualTo(RegionalStatus.NOT_CONFIRMED)
    }

    // ─── TTL eviction ─────────────────────────────────────────────────────────

    @Test fun `entry is refetched after TTL_MS has elapsed`() = runTest {
        var fakeNow = 0L
        val fake = FakeFilter()
        val repo = RegionalStatusRepository.forTest(annotator = fake, nowMs = { fakeNow })

        repo.get("Parus major", 51.5, 0.1)
        assertThat(fake.calls).isEqualTo(1)

        // Advance past TTL
        fakeNow = RegionalStatusRepository.TTL_MS + 1L
        repo.get("Parus major", 51.5, 0.1)
        assertThat(fake.calls).isEqualTo(2)
    }

    @Test fun `entry is NOT refetched just before TTL_MS has elapsed`() = runTest {
        var fakeNow = 0L
        val fake = FakeFilter()
        val repo = RegionalStatusRepository.forTest(annotator = fake, nowMs = { fakeNow })

        repo.get("Parus major", 51.5, 0.1)
        fakeNow = RegionalStatusRepository.TTL_MS - 1L
        repo.get("Parus major", 51.5, 0.1)
        assertThat(fake.calls).isEqualTo(1)
    }

    // ─── geo-bucketing ────────────────────────────────────────────────────────

    @Test fun `different lat-lon buckets produce independent cache entries`() = runTest {
        val fake = FakeFilter()
        val repo = RegionalStatusRepository.forTest(annotator = fake)

        // ~1 000 km apart — definitely different buckets
        repo.get("Parus major", 51.5, 0.1) // London area
        repo.get("Parus major", 42.0, 12.5) // Rome area
        assertThat(fake.calls).isEqualTo(2)
    }

    @Test fun `coords in the same ~10 km bucket share a cache entry`() = runTest {
        val fake = FakeFilter()
        val repo = RegionalStatusRepository.forTest(annotator = fake)

        // Both round to the same 0.1° bucket
        repo.get("Parus major", 51.5001, 0.1001)
        repo.get("Parus major", 51.5009, 0.1009)
        assertThat(fake.calls).isEqualTo(1)
    }

    @Test fun `different species in the same location are fetched independently`() = runTest {
        val fake = FakeFilter()
        val repo = RegionalStatusRepository.forTest(annotator = fake)
        repo.get("Parus major", 51.5, 0.1)
        repo.get("Corvus cornix", 51.5, 0.1)
        assertThat(fake.calls).isEqualTo(2)
    }

    // ─── getCached ────────────────────────────────────────────────────────────

    @Test fun `getCached returns null when nothing is stored`() = runTest {
        val repo = RegionalStatusRepository.forTest(annotator = FakeFilter())
        assertThat(repo.getCached("Parus major", 51.5, 0.1)).isNull()
    }

    @Test fun `getCached returns status after storeResult without calling the annotator`() = runTest {
        val fake = FakeFilter()
        val repo = RegionalStatusRepository.forTest(annotator = fake)
        repo.storeResult("Parus major", 51.5, 0.1, RegionalStatus.CONFIRMED)
        val result = repo.getCached("Parus major", 51.5, 0.1)
        assertThat(result).isEqualTo(RegionalStatus.CONFIRMED)
        assertThat(fake.calls).isEqualTo(0)
    }

    @Test fun `getCached returns status after get() and does not re-invoke the annotator`() = runTest {
        val fake = FakeFilter(RegionalStatus.NOT_CONFIRMED)
        val repo = RegionalStatusRepository.forTest(annotator = fake)
        repo.get("Parus major", 51.5, 0.1)
        val cached = repo.getCached("Parus major", 51.5, 0.1)
        assertThat(cached).isEqualTo(RegionalStatus.NOT_CONFIRMED)
        assertThat(fake.calls).isEqualTo(1) // only the initial get(), not getCached()
    }

    @Test fun `getCached returns null after TTL expires`() = runTest {
        var fakeNow = 0L
        val repo = RegionalStatusRepository.forTest(
            annotator = FakeFilter(),
            nowMs = { fakeNow },
        )
        repo.storeResult("Parus major", 51.5, 0.1, RegionalStatus.CONFIRMED)
        fakeNow = RegionalStatusRepository.TTL_MS + 1L
        assertThat(repo.getCached("Parus major", 51.5, 0.1)).isNull()
    }
}
