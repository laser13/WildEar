package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.RegionalStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RegionFilterTest {

    private val det1 = AggregatedDetection("Parus major", "Great Tit", 0.8f, 3, 0L, 1000L)
    private val det2 = AggregatedDetection("Corvus cornix", "Carrion Crow", 0.6f, 2, 0L, 2000L)

    @Test fun `annotate returns CONFIRMED when places found and species present`() = runTest {
        val filter = RegionFilter(FakeLookup(placeIds = listOf(7257L), inPlaces = true))
        val result = filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(result).hasSize(1)
        assertThat(result[0].regionalStatus).isEqualTo(RegionalStatus.CONFIRMED)
    }

    @Test fun `annotate returns NOT_CONFIRMED when places found and species absent`() = runTest {
        val filter = RegionFilter(FakeLookup(placeIds = listOf(7257L), inPlaces = false))
        val result = filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(result[0].regionalStatus).isEqualTo(RegionalStatus.NOT_CONFIRMED)
    }

    @Test fun `annotate checks all places — CONFIRMED if species found in any`() = runTest {
        val filter = RegionFilter(FakeLookup(placeIds = listOf(96773L, 10289L), inPlaces = true))
        val result = filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(result[0].regionalStatus).isEqualTo(RegionalStatus.CONFIRMED)
    }

    @Test fun `annotate falls back to radius check when no places found`() = runTest {
        val filter = RegionFilter(FakeLookup(placeIds = emptyList(), nearbyResult = true))
        val result = filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(result[0].regionalStatus).isEqualTo(RegionalStatus.CONFIRMED)
    }

    @Test fun `annotate returns UNVERIFIED when lookup throws`() = runTest {
        val filter = RegionFilter(FakeLookup(throws = true))
        val result = filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(result[0].regionalStatus).isEqualTo(RegionalStatus.UNVERIFIED)
    }

    @Test fun `annotate annotates all detections — nothing is removed`() = runTest {
        val filter = RegionFilter(FakeLookup(placeIds = listOf(7257L), inPlaces = false))
        val result = filter.annotate(listOf(det1, det2), 34.9, 33.1, 50)
        assertThat(result).hasSize(2)
    }

    @Test fun `annotate caches place lookup — getPlaceIds called once per location`() = runTest {
        val lookup = FakeLookup(placeIds = listOf(7257L), inPlaces = true)
        val filter = RegionFilter(lookup)
        filter.annotate(listOf(det1, det2), 34.9, 33.1, 50)
        assertThat(lookup.placeIdCalls).isEqualTo(1)
    }

    @Test fun `annotate caches status lookup — checkInPlaces called once per species`() = runTest {
        val lookup = FakeLookup(placeIds = listOf(7257L), inPlaces = true)
        val filter = RegionFilter(lookup)
        filter.annotate(listOf(det1), 34.9, 33.1, 50)
        filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(lookup.checkCalls).isEqualTo(1)
    }

    @Test fun `annotate caches status — checkNear called once per species on fallback path`() = runTest {
        val lookup = FakeLookup(placeIds = emptyList(), nearbyResult = true)
        val filter = RegionFilter(lookup)
        filter.annotate(listOf(det1), 34.9, 33.1, 50)
        filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(lookup.checkNearCalls).isEqualTo(1)
    }

    private class FakeLookup(
        private val placeIds: List<Long> = emptyList(),
        private val inPlaces: Boolean = false,
        private val nearbyResult: Boolean = false,
        private val throws: Boolean = false,
    ) : RegionLookup {
        var placeIdCalls = 0
        var checkCalls = 0
        var checkNearCalls = 0

        @Suppress("TooGenericExceptionThrown")
        override suspend fun getPlaceIds(lat: Double, lon: Double): List<Long> {
            placeIdCalls++
            if (throws) throw RuntimeException("network error")
            return placeIds
        }

        @Suppress("TooGenericExceptionThrown")
        override suspend fun checkInPlaces(scientificName: String, placeIds: List<Long>): Boolean {
            checkCalls++
            if (throws) throw RuntimeException("network error")
            return inPlaces
        }

        @Suppress("TooGenericExceptionThrown")
        override suspend fun checkNear(
            scientificName: String,
            lat: Double,
            lon: Double,
            radiusKm: Int,
        ): Boolean {
            checkNearCalls++
            if (throws) throw RuntimeException("network error")
            return nearbyResult
        }
    }
}
