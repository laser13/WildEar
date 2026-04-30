package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.AggregatedDetection
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RegionFilterTest {

    private fun det(name: String) = AggregatedDetection(
        taxonScientificName = name,
        taxonCommonName = null,
        maxConfidence = 0.9f,
        detectedWindows = 3,
        firstSeenMs = 0L,
        lastSeenMs = 3_000L,
    )

    @Test
    fun `filter keeps species with observations near location`() = runTest {
        val filter = RegionFilter { name, _, _, _ -> name == "Parus major" }
        val result = filter.filter(
            detections = listOf(det("Parus major"), det("Gnorimopsar chopi")),
            lat = 55.75,
            lon = 37.62,
            radiusKm = 200,
        )
        assertThat(result.map { it.taxonScientificName }).containsExactly("Parus major")
    }

    @Test
    fun `filter passes all species when lookup returns true for all`() = runTest {
        val filter = RegionFilter { _, _, _, _ -> true }
        val result = filter.filter(
            detections = listOf(det("Parus major"), det("Sylvia atricapilla")),
            lat = 55.75,
            lon = 37.62,
            radiusKm = 200,
        )
        assertThat(result).hasSize(2)
    }

    @Test
    fun `filter returns empty list when no species found nearby`() = runTest {
        val filter = RegionFilter { _, _, _, _ -> false }
        val result = filter.filter(
            detections = listOf(det("Gnorimopsar chopi")),
            lat = 55.75,
            lon = 37.62,
            radiusKm = 200,
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `filter caches results — lookup called only once per species per region`() = runTest {
        var callCount = 0
        val filter = RegionFilter { _, _, _, _ -> callCount++; true }
        val detections = listOf(det("Parus major"), det("Parus major"))
        filter.filter(detections, 55.75, 37.62, 200)
        assertThat(callCount).isEqualTo(1)
    }
}
