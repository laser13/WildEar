package com.sound2inat.app.ui.home

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.data.Settings
import com.sound2inat.app.inference.InferenceQueue
import com.sound2inat.inat.TaxonPhotoRepository
import com.sound2inat.modelmanager.BirdNetV24
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.storage.DetectionDao
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftDetectionCount
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftObservationCount
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.InatObservationDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    private fun buildVm(
        drafts: List<DraftEntity> = emptyList(),
        modelReady: Boolean = false,
    ): HomeViewModel {
        val repo = mockk<DraftRepository>(relaxed = true)
        every { repo.observeAll() } returns flowOf(drafts)

        val modelManager = mockk<ModelManager>(relaxed = true)
        coEvery { modelManager.stateFor(BirdNetV24.descriptor) } returns
            if (modelReady) {
                ModelInstallState.Ready(java.io.File("/m"), java.io.File("/l"))
            } else {
                ModelInstallState.NotInstalled
            }

        val detectionDao = mockk<DetectionDao>(relaxed = true)
        every { detectionDao.observeCountsByDraft() } returns flowOf(emptyList<DraftDetectionCount>())
        every { detectionDao.observeForDraft(any()) } returns flowOf(emptyList())

        val inatObservationDao = mockk<InatObservationDao>(relaxed = true)
        every { inatObservationDao.observeCountsByDraft() } returns flowOf(emptyList<DraftObservationCount>())
        every { inatObservationDao.observeForDraft(any()) } returns flowOf(emptyList())

        val taxonPhotoRepository = mockk<TaxonPhotoRepository>(relaxed = true)
        every { taxonPhotoRepository.observe(any()) } returns flowOf(null)

        val settings = mockk<Settings>(relaxed = true)
        every { settings.allowDeleteUploaded } returns flowOf(false)

        val inferenceQueue = mockk<InferenceQueue>(relaxed = true)
        every { inferenceQueue.status } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())

        return HomeViewModel(
            repo = repo,
            detectionDao = detectionDao,
            inatObservationDao = inatObservationDao,
            modelManager = modelManager,
            taxonPhotoRepository = taxonPhotoRepository,
            settings = settings,
            inferenceQueue = inferenceQueue,
        )
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
        val vm = buildVm(drafts = rows, modelReady = true)
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
        val vm = buildVm(drafts = emptyList(), modelReady = false)
        vm.state.test {
            val s = awaitItem()
            assertThat(s.isModelReady).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeRecordingModels derives badges from sources keys`() = runTest {
        val repo = mockk<DraftRepository>(relaxed = true)
        every { repo.observeAll() } returns flowOf(emptyList())
        val modelManager = mockk<ModelManager>(relaxed = true)
        coEvery { modelManager.stateFor(BirdNetV24.descriptor) } returns ModelInstallState.NotInstalled
        val detectionDao = mockk<DetectionDao>(relaxed = true)
        every { detectionDao.observeCountsByDraft() } returns flowOf(emptyList<DraftDetectionCount>())
        every { detectionDao.observeForDraft("d1") } returns flowOf(
            listOf(
                DetectionEntity(
                    draftId = "d1",
                    taxonScientificName = "Parus major",
                    taxonCommonName = null,
                    maxConfidence = 0.9f,
                    detectedWindows = 1,
                    firstSeenMs = 0,
                    lastSeenMs = 1,
                    isSelectedByUser = false,
                    sources = "birdnet_v2_4=0.9:1:0:1;perch_v2=0.8:1:0:1",
                ),
            ),
        )
        val inatObservationDao = mockk<InatObservationDao>(relaxed = true)
        every { inatObservationDao.observeCountsByDraft() } returns flowOf(emptyList<DraftObservationCount>())
        val taxonPhotoRepository = mockk<TaxonPhotoRepository>(relaxed = true)
        every { taxonPhotoRepository.observe(any()) } returns flowOf(null)
        val settings = mockk<Settings>(relaxed = true)
        every { settings.allowDeleteUploaded } returns flowOf(false)
        val inferenceQueue = mockk<InferenceQueue>(relaxed = true)
        every { inferenceQueue.status } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())

        val vm = HomeViewModel(
            repo = repo,
            detectionDao = detectionDao,
            inatObservationDao = inatObservationDao,
            modelManager = modelManager,
            taxonPhotoRepository = taxonPhotoRepository,
            settings = settings,
            inferenceQueue = inferenceQueue,
        )

        vm.observeRecordingModels("d1").test {
            assertThat(awaitItem()).containsExactly(ModelBadge.BIRDNET, ModelBadge.PERCH)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
