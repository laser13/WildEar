package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.radar.FilterKey
import com.sound2inat.app.ui.radar.MapPin
import com.sound2inat.app.ui.radar.SpeciesAggregate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class INatObservationsRepositoryTest {

    private val key = FilterKey(
        latGrid = 5050,
        lonGrid = 1010,
        radiusKm = 5,
        periodDays = 7,
        taxa = emptySet(),
        excludeUserId = null,
    )

    @Test fun `fetch hits both endpoints in parallel`() = runTest(UnconfinedTestDispatcher()) {
        var countsStartMs = 0L
        var countsEndMs = 0L
        var pinsStartMs = 0L
        var pinsEndMs = 0L
        val repo = INatObservationsRepository(
            countsLoader = {
                countsStartMs = System.nanoTime()
                delay(100)
                countsEndMs = System.nanoTime()
                emptyList()
            },
            pinsLoader = {
                pinsStartMs = System.nanoTime()
                delay(100)
                pinsEndMs = System.nanoTime()
                emptyList()
            },
            clock = { 0L },
        )
        repo.fetch(key)
        // If the calls were sequential, one would start strictly after the
        // other ended. Parallel ⇒ overlap.
        assertThat(pinsStartMs).isLessThan(countsEndMs)
        assertThat(countsStartMs).isLessThan(pinsEndMs)
    }

    @Test fun `fetch caches result for 15 minutes`() = runTest(UnconfinedTestDispatcher()) {
        var countsCalls = 0
        var pinsCalls = 0
        var now = 0L
        val repo = INatObservationsRepository(
            countsLoader = {
                countsCalls++
                emptyList()
            },
            pinsLoader = {
                pinsCalls++
                emptyList()
            },
            clock = { now },
        )
        repo.fetch(key)
        assertThat(countsCalls).isEqualTo(1)
        assertThat(pinsCalls).isEqualTo(1)

        // Advance clock by 14 min — still fresh.
        now = 14L * 60 * 1000
        repo.fetch(key)
        assertThat(countsCalls).isEqualTo(1)
        assertThat(pinsCalls).isEqualTo(1)

        // Advance to 16 min — expired.
        now = 16L * 60 * 1000
        repo.fetch(key)
        assertThat(countsCalls).isEqualTo(2)
        assertThat(pinsCalls).isEqualTo(2)
    }

    @Test fun `fetch with forceRefresh bypasses cache`() = runTest(UnconfinedTestDispatcher()) {
        var countsCalls = 0
        val repo = INatObservationsRepository(
            countsLoader = {
                countsCalls++
                emptyList()
            },
            pinsLoader = { emptyList() },
            clock = { 0L },
        )
        repo.fetch(key)
        repo.fetch(key, forceRefresh = true)
        assertThat(countsCalls).isEqualTo(2)
    }

    @Test fun `fetch returns failure if loader throws`() = runTest(UnconfinedTestDispatcher()) {
        val repo = INatObservationsRepository(
            countsLoader = { error("boom") },
            pinsLoader = { emptyList() },
            clock = { 0L },
        )
        val result = repo.fetch(key)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("boom")
    }

    @Test fun `attachNearestFrom joins by taxon and picks nearest pin`() {
        val species = listOf(
            sample(taxonId = 9083, count = 5),
            sample(taxonId = 1234, count = 1),
        )
        val pins = listOf(
            pin(taxonId = 9083, lat = 50.501, lon = 10.101, id = 11),
            pin(taxonId = 9083, lat = 50.510, lon = 10.110, id = 12),
            pin(taxonId = 1234, lat = 50.520, lon = 10.120, id = 13),
        )
        val joined = species.attachNearestFrom(userLat = 50.500, userLon = 10.100, pins = pins)
        // Goldcrest (9083) — nearest is pin 11.
        val gold = joined.first { it.taxonId == 9083L }
        assertThat(gold.nearestObservationUrl).contains("11")
        assertThat(gold.nearestObservationKm).isLessThan(0.5f)
        // Frog (1234) — only one pin.
        val frog = joined.first { it.taxonId == 1234L }
        assertThat(frog.nearestObservationUrl).contains("13")
    }

    @Test fun `attachNearestFrom keeps fallback URL when no pin matches`() {
        val species = listOf(sample(taxonId = 9083, count = 5))
        val joined = species.attachNearestFrom(userLat = 50.5, userLon = 10.1, pins = emptyList())
        val it = joined.first()
        assertThat(it.nearestObservationKm).isEqualTo(-1f)
        assertThat(it.nearestObservationUrl).isEqualTo("https://www.inaturalist.org/taxa/9083")
    }

    private fun sample(taxonId: Long, count: Int) = SpeciesAggregate(
        taxonId = taxonId,
        scientificName = "Sci",
        commonName = null,
        iconicTaxon = "Aves",
        photoUrl = null,
        observationCount = count,
        nearestObservationKm = -1f,
        nearestObservationUrl = "https://www.inaturalist.org/taxa/$taxonId",
    )

    private fun pin(taxonId: Long, lat: Double, lon: Double, id: Long) = MapPin(
        observationId = id,
        taxonId = taxonId,
        scientificName = "Sci",
        lat = lat,
        lon = lon,
        obsUrl = "https://www.inaturalist.org/observations/$id",
    )
}
