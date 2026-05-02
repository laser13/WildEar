package com.sound2inat.app.ui.radar

import com.google.common.truth.Truth.assertThat
import com.sound2inat.location.Fix
import com.sound2inat.location.LocationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadarViewModelTest {

    @Test fun `filter change triggers single fetch after debounce`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeRepo = FakeRepo()
            val radius = MutableStateFlow(5)
            val period = MutableStateFlow(7)
            val taxa = MutableStateFlow<Set<String>>(emptySet())
            val vm = newVm(fakeRepo, radius, period, taxa)

            radius.value = 25
            advanceTimeBy(150)
            radius.value = 100
            advanceTimeBy(400)

            // Three filter emissions in <300ms collapse to one fetch (the last).
            assertThat(fakeRepo.calls.map { it.radiusKm }).containsExactly(100).inOrder()
        }

    @Test fun `pullRefresh forces cache bypass`() = runTest(UnconfinedTestDispatcher()) {
        val fakeRepo = FakeRepo()
        val vm = newVm(fakeRepo)
        advanceTimeBy(400)
        assertThat(fakeRepo.calls).hasSize(1)
        assertThat(fakeRepo.lastForce).isFalse()

        vm.pullRefresh()
        advanceTimeBy(50)
        assertThat(fakeRepo.calls).hasSize(2)
        assertThat(fakeRepo.lastForce).isTrue()
    }

    @Test fun `permission denied falls back to lastKnown`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeRepo = FakeRepo()
            val location = object : LocationProvider {
                override suspend fun getCurrent(timeoutMs: Long): Fix? = null
            }
            val vm = newVm(
                fakeRepo,
                location = location,
                lastKnownLat = 50.0,
                lastKnownLon = 10.0,
            )
            advanceTimeBy(400)
            assertThat(fakeRepo.calls).hasSize(1)
            assertThat(fakeRepo.calls[0].latGrid).isEqualTo(5000)
            assertThat(fakeRepo.calls[0].lonGrid).isEqualTo(1000)
        }

    @Test fun `no location at all sets NoLocation state`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeRepo = FakeRepo()
            val location = object : LocationProvider {
                override suspend fun getCurrent(timeoutMs: Long): Fix? = null
            }
            val vm = newVm(
                fakeRepo,
                location = location,
                lastKnownLat = null,
                lastKnownLon = null,
            )
            advanceTimeBy(400)
            assertThat(fakeRepo.calls).isEmpty()
            assertThat(vm.state.value.locationStatus).isEqualTo(LocationStatus.NoLocation)
        }

    @Test fun `repo failure surfaces error in state`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeRepo = FakeRepo(throwing = "boom")
            val vm = newVm(fakeRepo)
            advanceTimeBy(400)
            assertThat(vm.state.value.error).isEqualTo("boom")
            assertThat(vm.state.value.loading).isFalse()
        }

    @Test fun `same FilterKey emitted twice does not double-fetch`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeRepo = FakeRepo()
            val radius = MutableStateFlow(5)
            val vm = newVm(fakeRepo, radius)
            advanceTimeBy(400)
            radius.value = 5 // identical
            advanceTimeBy(400)
            assertThat(fakeRepo.calls).hasSize(1)
        }

    private fun TestScope.newVm(
        repo: FakeRepo,
        radius: MutableStateFlow<Int> = MutableStateFlow(5),
        period: MutableStateFlow<Int> = MutableStateFlow(7),
        taxa: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        location: LocationProvider = object : LocationProvider {
            override suspend fun getCurrent(timeoutMs: Long) =
                Fix(50.5, 10.1, 5f, 0L)
        },
        lastKnownLat: Double? = null,
        lastKnownLon: Double? = null,
    ): RadarViewModel = RadarViewModel(
        repoFetch = repo::fetch,
        radarRadiusKm = radius,
        radarPeriodDays = period,
        radarTaxa = taxa,
        getLastKnown = { lastKnownLat to lastKnownLon },
        getLocation = { location.getCurrent() },
        userId = { null },
        externalScope = backgroundScope,
    )

    private class FakeRepo(private val throwing: String? = null) {
        val calls = mutableListOf<FilterKey>()
        var lastForce: Boolean = false
        suspend fun fetch(key: FilterKey, force: Boolean): Result<CachedResult> {
            calls += key
            lastForce = force
            return if (throwing != null) Result.failure(RuntimeException(throwing))
            else Result.success(CachedResult(emptyList(), emptyList(), 0L))
        }
    }
}
