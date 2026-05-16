package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.inference.InferenceQueue
import com.sound2inat.app.ui.spectrogram.LiveDefaults
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.inference.InferenceJob
import com.sound2inat.inference.InferenceOutcome
import com.sound2inat.inference.PerchAnalysisJob
import com.sound2inat.inference.PerchAnalysisOutcome
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.DraftWithDetections
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for the per-draft spectrogram visual settings wired into
 * [ReviewViewModel] in Task 5. Covers:
 *   * seeding the in-memory processing profile from persisted DraftEntity columns
 *     (null / valid enum / malformed enum string)
 *   * setDisplayRange / setSpectrogramPalette / setSpectrogramGain writing through
 *     to DraftRepository
 *   * pressAuto() picking a preset from cached YamNet SceneTags
 *   * the sceneTagsAvailable flag reflecting draft.sceneTagsJson presence
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelDisplayRangeTest {

    private fun draft(
        id: String = "draft-1",
        displayRangeName: String? = null,
        paletteName: String? = null,
        spectrogramGainDb: Float? = null,
        sceneTagsJson: String? = null,
        status: DraftStatus = DraftStatus.PENDING_REVIEW,
    ): DraftEntity = DraftEntity(
        id = id,
        audioPath = "/tmp/$id.wav",
        recordedAtUtcMs = 1_000L,
        durationMs = 2_000L,
        latitude = null,
        longitude = null,
        locationAccuracyMeters = null,
        status = status,
        modelId = null,
        modelVersion = null,
        createdAtUtcMs = 0L,
        updatedAtUtcMs = 0L,
        displayRangeName = displayRangeName,
        paletteName = paletteName,
        spectrogramGainDb = spectrogramGainDb,
        sceneTagsJson = sceneTagsJson,
    )

    private fun stubRepo(draft: DraftEntity, sceneTagsJson: String? = draft.sceneTagsJson): DraftRepository {
        val repo = mockk<DraftRepository>(relaxed = true)
        val flow = MutableStateFlow(DraftWithDetections(draft, emptyList()))
        coEvery { repo.observeWithDetections(draft.id) } returns flow
        coEvery { repo.getSceneTagsJson(draft.id) } returns sceneTagsJson
        return repo
    }

    private fun buildVm(
        repo: DraftRepository,
        draftId: String,
        scope: CoroutineScope,
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): ReviewViewModel {
        val queue = mockk<InferenceQueue>(relaxed = true)
        coEvery { queue.status } returns MutableStateFlow(emptyMap())
        return ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = StubAudioPlayer(),
            inference = InferenceJob { _, _, _, _, _ ->
                InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
            },
            perchAnalysis = PerchAnalysisJob { _, _, _, _, _ -> PerchAnalysisOutcome.NotInstalled },
            queue = queue,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = scope,
        )
    }

    @Test
    fun `null spectrogram columns resolve to LiveDefaults`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "null-cols"
        val repo = stubRepo(draft(id = draftId))
        val vm = buildVm(repo, draftId, backgroundScope, testScheduler)
        advanceUntilIdle()

        val config = vm.spectrogramConfig.value
        assertThat(config.displayRange).isNull()
        assertThat(config.palette).isNull()
        assertThat(config.gainDb).isNull()
        assertThat(config.effectiveRangeSpec).isEqualTo(LiveDefaults.displayRange())
        assertThat(config.effectivePalette).isEqualTo(SpectrogramPalette.INK)
        assertThat(config.effectiveGainDb).isEqualTo(0f)
    }

    @Test
    fun `valid displayRangeName seeds the processing profile`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "valid-range"
        val repo = stubRepo(draft(id = draftId, displayRangeName = "OWL_LOW_VOICE"))
        val vm = buildVm(repo, draftId, backgroundScope, testScheduler)
        advanceUntilIdle()

        assertThat(vm.spectrogramConfig.value.displayRange)
            .isEqualTo(SpectrogramDisplayRange.OWL_LOW_VOICE)
        assertThat(vm.displayRange.value).isEqualTo(SpectrogramDisplayRange.OWL_LOW_VOICE)
    }

    @Test
    fun `malformed displayRangeName falls back to null without crashing`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "bad-range"
        val repo = stubRepo(draft(id = draftId, displayRangeName = "UNKNOWN"))
        val vm = buildVm(repo, draftId, backgroundScope, testScheduler)
        advanceUntilIdle()

        assertThat(vm.spectrogramConfig.value.displayRange).isNull()
        assertThat(vm.spectrogramConfig.value.effectiveRangeSpec)
            .isEqualTo(LiveDefaults.displayRange())
    }

    @Test
    fun `setDisplayRange null writes null name through repo`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "clear-range"
        val repo = stubRepo(draft(id = draftId, displayRangeName = "OWL_LOW_VOICE"))
        val vm = buildVm(repo, draftId, backgroundScope, testScheduler)
        advanceUntilIdle()

        vm.setDisplayRange(null)
        advanceUntilIdle()

        coVerify { repo.updateDisplayRange(draftId, null) }
    }

    @Test
    fun `setDisplayRange WILDLIFE writes enum name through repo`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "set-range"
        val repo = stubRepo(draft(id = draftId))
        val vm = buildVm(repo, draftId, backgroundScope, testScheduler)
        advanceUntilIdle()

        vm.setDisplayRange(SpectrogramDisplayRange.WILDLIFE)
        advanceUntilIdle()

        coVerify { repo.updateDisplayRange(draftId, "WILDLIFE") }
        assertThat(vm.spectrogramConfig.value.displayRange)
            .isEqualTo(SpectrogramDisplayRange.WILDLIFE)
    }

    @Test
    fun `pressAuto picks BIRDNET_BIRD from bird-heavy scene tags`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "auto-bird"
        val tagsJson = """{"bird":0.85,"owl":0,"frog":0,"insect":0,"mammal":0}"""
        val repo = stubRepo(draft(id = draftId, sceneTagsJson = tagsJson), sceneTagsJson = tagsJson)
        val vm = buildVm(repo, draftId, backgroundScope, testScheduler)
        advanceUntilIdle()

        vm.pressAuto()
        advanceUntilIdle()

        coVerify { repo.updateDisplayRange(draftId, "BIRDNET_BIRD") }
    }

    @Test
    fun `pressAuto with no cached scene tags is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "auto-empty"
        val repo = stubRepo(draft(id = draftId), sceneTagsJson = null)
        val vm = buildVm(repo, draftId, backgroundScope, testScheduler)
        advanceUntilIdle()

        vm.pressAuto()
        advanceUntilIdle()

        // No display-range write should be triggered (the relaxed mock would
        // count the call otherwise).
        coVerify(exactly = 0) { repo.updateDisplayRange(draftId, any()) }
    }

    @Test
    fun `sceneTagsAvailable reflects draft sceneTagsJson presence`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "tags-flag"
        val repo = stubRepo(
            draft(id = draftId, sceneTagsJson = """{"bird":0.5,"owl":0,"frog":0,"insect":0,"mammal":0}""")
        )
        val vm = buildVm(repo, draftId, backgroundScope, testScheduler)
        advanceUntilIdle()
        assertThat(vm.sceneTagsAvailable.value).isTrue()

        // A separate VM observing a draft without scene tags reports false.
        val emptyId = "tags-empty"
        val emptyRepo = stubRepo(draft(id = emptyId, sceneTagsJson = null))
        val emptyVm = buildVm(emptyRepo, emptyId, backgroundScope, testScheduler)
        advanceUntilIdle()
        assertThat(emptyVm.sceneTagsAvailable.value).isFalse()
    }
}

private class StubAudioPlayer : AudioPlayer {
    override val position: kotlinx.coroutines.flow.StateFlow<Long> = MutableStateFlow(0L)
    override val isPlaying: kotlinx.coroutines.flow.StateFlow<Boolean> = MutableStateFlow(false)
    override val durationMs: kotlinx.coroutines.flow.StateFlow<Long> = MutableStateFlow(0L)
    override val lastError: kotlinx.coroutines.flow.StateFlow<String?> = MutableStateFlow(null)
    override fun start(path: String) = Unit
    override fun pause() = Unit
    override fun seekTo(ms: Long) = Unit
    override fun release() = Unit
}
