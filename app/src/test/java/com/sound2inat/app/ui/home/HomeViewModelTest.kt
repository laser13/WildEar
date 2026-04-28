package com.sound2inat.app.ui.home

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `maps drafts and reflects model readiness`() = runTest {
        val rows = listOf(
            DraftEntity(
                id = "d2", audioPath = "/tmp/b.wav", recordedAtUtcMs = 200L,
                durationMs = 1000L, latitude = null, longitude = null,
                locationAccuracyMeters = null, status = DraftStatus.PENDING_REVIEW,
                modelId = null, modelVersion = null,
                createdAtUtcMs = 0L, updatedAtUtcMs = 0L,
            ),
            DraftEntity(
                id = "d1", audioPath = "/tmp/a.wav", recordedAtUtcMs = 100L,
                durationMs = 1000L, latitude = null, longitude = null,
                locationAccuracyMeters = null, status = DraftStatus.PENDING_INFERENCE,
                modelId = null, modelVersion = null,
                createdAtUtcMs = 0L, updatedAtUtcMs = 0L,
            ),
        )
        val vm = HomeViewModel(
            observeDrafts = { flowOf(rows) },
            topLabelFor = { null },
            isModelReady = { true },
        )
        vm.state.test {
            // First emission can be the empty default; await the populated one.
            var s = awaitItem()
            if (s.drafts.isEmpty()) s = awaitItem()
            assertThat(s.isModelReady).isTrue()
            assertThat(s.drafts.map { it.id }).containsExactly("d2", "d1").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `model not ready when isModelReady returns false`() = runTest {
        val vm = HomeViewModel(
            observeDrafts = { flowOf(emptyList()) },
            topLabelFor = { null },
            isModelReady = { false },
        )
        vm.state.test {
            val s = awaitItem()
            assertThat(s.isModelReady).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
