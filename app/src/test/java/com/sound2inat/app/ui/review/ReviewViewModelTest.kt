package com.sound2inat.app.ui.review

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.inference.InferenceQueue
import com.sound2inat.app.inference.QueuedJob
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.inat.RegionFilter
import com.sound2inat.inat.RegionLookup
import com.sound2inat.inat.RegionalStatusRepository
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.InferenceJob
import com.sound2inat.inference.InferenceOutcome
import com.sound2inat.inference.InferenceUseCase
import com.sound2inat.inference.PerchAnalysisJob
import com.sound2inat.inference.PerchAnalysisOutcome
import com.sound2inat.inference.RegionalStatus
import com.sound2inat.inference.SourceStat
import com.sound2inat.inference.SourceStats
import com.sound2inat.inference.WindowPrediction
import com.sound2inat.storage.DetectionDao
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftPhotoDao
import com.sound2inat.storage.DraftPhotoEntity
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.WavFileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun preview(color: Int = 0xFF000000.toInt()) =
        ReviewSpectrogramPreview(width = 1, height = 1, argb = intArrayOf(color))

    @Test
    fun `pending inference draft kicks off inference and populates species`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d1"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_INFERENCE))
            }
            val detectionDao = FakeDetectionDao()
            val repo = repo(draftDao, detectionDao)
            val agg = AggregatedDetection(
                taxonScientificName = "Turdus merula",
                taxonCommonName = "Common Blackbird",
                maxConfidence = 0.81f,
                detectedWindows = 3,
                firstSeenMs = 0L,
                lastSeenMs = 3_000L,
            )
            val progressEmissions = mutableListOf<Float>()
            val inference = InferenceJob { _, _, _, _, onProgress ->
                onProgress(0.5f).also { progressEmissions += 0.5f }
                onProgress(1f).also { progressEmissions += 1f }
                InferenceOutcome.Success("birdnet_v2_4", "2.4", listOf(agg))
            }
            val queue = makeQueue(FakeInferenceUseCase(birdnetJob = inference), repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = inference,
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            // Inference job was driven through onProgress(0.5) → onProgress(1).
            assertThat(progressEmissions).containsExactly(0.5f, 1f).inOrder()
            // VM cleared inferenceProgress on completion and persisted species.
            assertThat(vm.state.value.inferenceProgress).isNull()
            assertThat(vm.state.value.species).hasSize(1)
            assertThat(vm.state.value.species.first().taxonScientificName).isEqualTo("Turdus merula")
            // VM auto-promotes to REVIEWED after a successful inference with
            // non-empty detections so Submit is enabled by checkbox alone.
            assertThat(vm.state.value.status).isEqualTo(DraftStatus.REVIEWED)
        }

    @Test
    fun `pending review draft does not trigger inference`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "d2"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val detectionDao = FakeDetectionDao().apply {
            insertAll(
                listOf(
                    DetectionEntity(
                        id = 1L,
                        draftId = draftId,
                        taxonScientificName = "Parus major",
                        taxonCommonName = "Great Tit",
                        maxConfidence = 0.9f,
                        detectedWindows = 2,
                        firstSeenMs = 0L,
                        lastSeenMs = 2_000L,
                        isSelectedByUser = false,
                    ),
                ),
            )
        }
        var inferenceCalls = 0
        val inference = InferenceJob { _, _, _, _, _ ->
            inferenceCalls++
            InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
        }
        val repo = repo(draftDao, detectionDao)
        val queue = makeQueue(FakeInferenceUseCase(birdnetJob = inference), repo)
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = FakeAudioPlayer(),
            inference = inference,
            queue = queue,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
        )
        assertThat(inferenceCalls).isEqualTo(0)
        assertThat(vm.state.value.species).hasSize(1)
        assertThat(vm.state.value.species.first().taxonScientificName).isEqualTo("Parus major")
        assertThat(vm.state.value.inferenceProgress).isNull()
    }

    @Test
    fun `toggle calls setSelection and reflects in observed state`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d3"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 42L,
                            draftId = draftId,
                            taxonScientificName = "Parus major",
                            taxonCommonName = null,
                            maxConfidence = 0.9f,
                            detectedWindows = 1,
                            firstSeenMs = 0L,
                            lastSeenMs = 1_000L,
                            isSelectedByUser = false,
                        ),
                    ),
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            assertThat(vm.state.value.species.first().isSelected).isFalse()
            vm.toggle(42L, selected = true)
            assertThat(detectionDao.selections[42L]).isTrue()
            assertThat(vm.state.value.species.first().isSelected).isTrue()
        }

    @Test
    fun `save marks draft as reviewed`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "d4"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val detectionDao = FakeDetectionDao()
        val repo = repo(draftDao, detectionDao)
        val queue = makeQueue(draftRepo = repo)
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = FakeAudioPlayer(),
            inference = noopInference(),
            queue = queue,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
        )
        var saved = false
        vm.save(onSaved = { saved = true })
        // Persisted via repo + callback fires after the IO write completes.
        assertThat(draftDao.byId(draftId)?.status).isEqualTo(DraftStatus.REVIEWED)
        assertThat(saved).isTrue()
    }

    @Test
    fun `delete removes draft via repo`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "d5"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val detectionDao = FakeDetectionDao()
        val repo = repo(draftDao, detectionDao)
        val queue = makeQueue(draftRepo = repo)
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = FakeAudioPlayer(),
            inference = noopInference(),
            queue = queue,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
        )
        var deleted = false
        vm.delete(onDeleted = { deleted = true })
        assertThat(draftDao.byId(draftId)).isNull()
        assertThat(deleted).isTrue()
    }

    @Test
    fun `seekTo forwards playback seeks to the audio player`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "d6"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val repo = repo(draftDao, FakeDetectionDao())
        val queue = makeQueue(draftRepo = repo)
        val player = FakeAudioPlayer()
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = player,
            inference = noopInference(),
            queue = queue,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
        )

        vm.seekTo(12_345L)

        assertThat(player.seekToMs).isEqualTo(12_345L)
        assertThat(player.position.value).isEqualTo(12_345L)
    }

    @Test
    fun `ensureVisuals populates spectrogramPreview and waveformPeaks`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d7"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val expectedPreview = preview()
            val expectedPeaks = floatArrayOf(-0.5f, 0.5f, -0.25f, 0.25f)
            var calls = 0
            val provider = VisualsProvider { _, _, _, _ ->
                calls++
                Visuals(spectrogramPreview = expectedPreview, waveformPeaks = expectedPeaks)
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                visuals = provider,
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            vm.ensureVisuals(tmp.root)
            assertThat(vm.spectrogramPreview.value).isEqualTo(expectedPreview)
            assertThat(vm.waveformPeaks.value).isEqualTo(expectedPeaks)
            // Subsequent calls do NOT re-invoke the provider.
            vm.ensureVisuals(tmp.root)
            assertThat(calls).isEqualTo(1)
        }

    @Test
    fun `inactive page with audio path does not start visuals until active page triggers`() =
        runTest(UnconfinedTestDispatcher()) {
            val activeDraftId = "active_page"
            val inactiveDraftId = "inactive_page"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(activeDraftId, status = DraftStatus.PENDING_REVIEW))
                insert(draftFor(inactiveDraftId, status = DraftStatus.PENDING_REVIEW))
            }
            val visualsCalls = AtomicInteger(0)
            val provider = VisualsProvider { _, _, _, _ ->
                visualsCalls.incrementAndGet()
                Visuals(spectrogramPreview = preview(), waveformPeaks = floatArrayOf(-1f, 1f))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(draftRepo = repo)
            val activeVm = ReviewViewModel(
                draftId = activeDraftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                visuals = provider,
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            val inactiveVm = ReviewViewModel(
                draftId = inactiveDraftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                visuals = provider,
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            advanceUntilIdle()

            assertThat(activeVm.state.value.audioPath).isNotNull()
            assertThat(inactiveVm.state.value.audioPath).isNotNull()
            assertThat(visualsCalls.get()).isEqualTo(0)

            activeVm.ensureVisuals(tmp.root)
            advanceUntilIdle()

            assertThat(visualsCalls.get()).isEqualTo(1)
            assertThat(inactiveVm.state.value.audioPath).isNotNull()
        }

    @Test
    fun `ensureVisuals exposes loading while the preview is rendering`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "visuals_loading"
            val audioFile = createSilentWav(tmp.newFile("$draftId.wav"), durationMs = 2_000)
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW).copy(audioPath = audioFile.absolutePath))
            }
            val gate = CompletableDeferred<Unit>()
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                visuals = VisualsProvider { _, _, _, _ ->
                    gate.await()
                    Visuals(spectrogramPreview = preview(), waveformPeaks = floatArrayOf(-1f, 1f))
                },
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )

            vm.ensureVisuals(tmp.root)
            runCurrent()

            assertThat(vm.state.value.visualsLoading).isTrue()

            gate.complete(Unit)
            advanceUntilIdle()

            assertThat(vm.state.value.visualsLoading).isFalse()
            assertThat(vm.spectrogramPreview.value).isEqualTo(preview())
        }

    @Test
    fun `visuals loading stays true when a pending render is canceled and restarted`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "visuals_cancel_restart"
            val audioFile = createSilentWav(tmp.newFile("$draftId.wav"), durationMs = 2_000)
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW).copy(audioPath = audioFile.absolutePath))
            }
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                visuals = VisualsProvider { _, _, _, _ ->
                    calls++
                    gate.await()
                    Visuals(spectrogramPreview = preview(calls), waveformPeaks = floatArrayOf(-1f, 1f))
                },
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )

            vm.ensureVisuals(tmp.root)
            runCurrent()
            assertThat(vm.state.value.visualsLoading).isTrue()

            vm.setSpectrogramGain(5f)
            runCurrent()
            assertThat(vm.state.value.visualsLoading).isTrue()

            gate.complete(Unit)
            advanceUntilIdle()

            assertThat(vm.state.value.visualsLoading).isFalse()
            assertThat(calls).isEqualTo(2)
        }

    @Test
    fun `setSpectrogramGain rerenders visuals`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "visual_gain"
            val audioFile = createSilentWav(tmp.newFile("$draftId.wav"), durationMs = 2_000)
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW).copy(audioPath = audioFile.absolutePath))
            }
            var visualsCalls = 0
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                visuals = VisualsProvider { _, _, _, _ ->
                    visualsCalls++
                    Visuals(
                        spectrogramPreview = preview(),
                        waveformPeaks = floatArrayOf(-1f, 1f),
                    )
                },
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )

            vm.ensureVisuals(tmp.root)
            advanceUntilIdle()

            vm.setSpectrogramGain(6f)
            advanceUntilIdle()

            assertThat(visualsCalls).isEqualTo(2)
            assertThat(vm.state.value.playback).isEqualTo(PlaybackState.Idle)
        }

    @Test
    fun `setSpectrogramPalette rerenders visuals`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "visual_palette"
            val audioFile = createSilentWav(tmp.newFile("$draftId.wav"), durationMs = 2_000)
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW).copy(audioPath = audioFile.absolutePath))
            }
            var visualsCalls = 0
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                visuals = VisualsProvider { _, _, _, _ ->
                    visualsCalls++
                    Visuals(
                        spectrogramPreview = preview(),
                        waveformPeaks = floatArrayOf(-1f, 1f),
                    )
                },
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )

            vm.ensureVisuals(tmp.root)
            advanceUntilIdle()

            vm.setSpectrogramPalette(SpectrogramPalette.MAGMA)
            advanceUntilIdle()

            assertThat(visualsCalls).isEqualTo(1)
            assertThat(vm.spectrogramDisplayPlane.value).isNotNull()
            assertThat(vm.spectrogramConfig.value.palette).isEqualTo(SpectrogramPalette.MAGMA)
            assertThat(vm.state.value.playback).isEqualTo(PlaybackState.Idle)
        }

    @Test
    fun `production visuals provider produces different previews for different palettes`() =
        runTest(UnconfinedTestDispatcher()) {
            val audioFile = createSilentWav(tmp.newFile("palette_diff.wav"), sampleRate = 48_000, durationMs = 5_000)
            val provider = ProductionVisualsProvider()

            val magenta = provider.build(
                audioPath = audioFile.absolutePath,
                draftId = "palette_diff",
                filesDir = tmp.root,
                config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.MAGMA),
            )
            val viridis = provider.build(
                audioPath = audioFile.absolutePath,
                draftId = "palette_diff_2",
                filesDir = tmp.root,
                config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.VIRIDIS),
            )

            assertThat(magenta.spectrogramPreview).isNotEqualTo(viridis.spectrogramPreview)
            assertThat(magenta.waveformPeaks).isNotEmpty()
        }

    /**
     * Spectrograms are now built per-clip inside [INatSubmitter] rather than
     * by the ViewModel, so this test just checks that submission still fires
     * with the species selection unchanged.
     */
    @Test
    fun `submitToINaturalist invokes the submission job with selected species`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d7_submit"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 1L,
                            draftId = draftId,
                            taxonScientificName = "Parus major",
                            taxonCommonName = null,
                            maxConfidence = 0.9f,
                            detectedWindows = 3,
                            firstSeenMs = 0L,
                            lastSeenMs = 3_000L,
                            isSelectedByUser = true,
                        ),
                    ),
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            var submitCalls = 0
            val submission = InatSubmissionJob { _, _, _, _, _, _ ->
                submitCalls++
                InatSubmissionOutcome.Success(emptyList())
            }
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                visuals = VisualsProvider { _, _, _, _ ->
                    Visuals(
                        displayPlane = ReviewSpectrogramDisplayPlane(
                            width = 1,
                            height = 1,
                            values = arrayOf(floatArrayOf(0.5f)),
                        ),
                        spectrogramPreview = preview(),
                        waveformPeaks = floatArrayOf(-1f, 1f),
                    )
                },
                submission = submission,
                tokenProvider = { "jwt" },
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )

            vm.ensureVisuals(tmp.root)
            advanceUntilIdle()
            vm.submitToINaturalist()
            advanceUntilIdle()

            assertThat(submitCalls).isEqualTo(1)
        }

    /**
     * Incremental submission: even when the draft has previously been
     * UPLOADED and one species already has a row in `inat_observations`,
     * `submitToINaturalist` must fire again whenever any selected species
     * still lacks a row. This is the ReviewVM half of the
     * "iNat submission fixes" plan — the gating is done off
     * `inatObservations` (the source of truth for "what's already on iNat"),
     * not the draft status flag, so a fresh re-analysis that adds a new
     * species can be submitted without resetting the draft.
     */
    @Test
    fun `submit re-runs when status is UPLOADED but a new species is selected`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d_incremental"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.UPLOADED))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 1L,
                            draftId = draftId,
                            taxonScientificName = "Parus major",
                            taxonCommonName = null,
                            maxConfidence = 0.9f,
                            detectedWindows = 3,
                            firstSeenMs = 0L,
                            lastSeenMs = 3_000L,
                            isSelectedByUser = true,
                        ),
                        DetectionEntity(
                            id = 2L,
                            draftId = draftId,
                            taxonScientificName = "Apus apus",
                            taxonCommonName = null,
                            maxConfidence = 0.7f,
                            detectedWindows = 2,
                            firstSeenMs = 1_000L,
                            lastSeenMs = 2_500L,
                            isSelectedByUser = true,
                        ),
                    ),
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            // Pre-existing inat observation row for Parus only; Apus is fresh
            // and must trigger the submitter despite the draft being UPLOADED.
            val obsFlow = MutableStateFlow(
                listOf(
                    InatObsEntry(
                        scientificName = "Parus major",
                        observationId = 900L,
                        url = "https://www.inaturalist.org/observations/900",
                    ),
                )
            )
            var submitCalls = 0
            val submission = InatSubmissionJob { _, _, _, _, _, _ ->
                submitCalls++
                InatSubmissionOutcome.Success(listOf("https://www.inaturalist.org/observations/901"))
            }
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                submission = submission,
                tokenProvider = { "jwt" },
                inatObservationsFlow = obsFlow,
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            advanceUntilIdle()

            // Sanity: state reflects the pre-existing observation + two selections.
            assertThat(vm.state.value.inatObservations.map { it.scientificName })
                .containsExactly("Parus major")
            assertThat(vm.state.value.species.filter { it.isSelected }.map { it.taxonScientificName })
                .containsExactly("Parus major", "Apus apus")
            assertThat(vm.state.value.status).isEqualTo(DraftStatus.UPLOADED)

            // Submit must NOT be gated by status == UPLOADED — there is one
            // pending selection (Apus apus) that still needs an iNat row.
            vm.submitToINaturalist()
            advanceUntilIdle()

            assertThat(submitCalls).isEqualTo(1)
            assertThat(vm.state.value.inatSubmission).isInstanceOf(InatSubmissionState.Done::class.java)
        }

    /**
     * Companion to the test above — when every selected species is already
     * persisted on iNat, `submitToINaturalist` must short-circuit so a stray
     * tap on the Submit button doesn't replay the upload.
     */
    @Test
    fun `submit is gated when every selected species is already uploaded`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d_no_pending"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.UPLOADED))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 1L,
                            draftId = draftId,
                            taxonScientificName = "Parus major",
                            taxonCommonName = null,
                            maxConfidence = 0.9f,
                            detectedWindows = 3,
                            firstSeenMs = 0L,
                            lastSeenMs = 3_000L,
                            isSelectedByUser = true,
                        ),
                    ),
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            val obsFlow = MutableStateFlow(
                listOf(
                    InatObsEntry(
                        scientificName = "Parus major",
                        observationId = 900L,
                        url = "https://www.inaturalist.org/observations/900",
                    ),
                ),
            )
            var submitCalls = 0
            val submission = InatSubmissionJob { _, _, _, _, _, _ ->
                submitCalls++
                InatSubmissionOutcome.Success(emptyList())
            }
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                submission = submission,
                tokenProvider = { "jwt" },
                inatObservationsFlow = obsFlow,
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            advanceUntilIdle()

            vm.submitToINaturalist()
            advanceUntilIdle()

            assertThat(submitCalls).isEqualTo(0)
        }

    @Test
    fun `reanalyze uses the original audio path`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "audio_source_original"
            val audioFile = createSilentWav(tmp.newFile("$draftId.wav"), durationMs = 2_000)
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW).copy(audioPath = audioFile.absolutePath))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            var capturedPath: String? = null
            val inference = InferenceJob { audioPath, _, _, _, _ ->
                capturedPath = audioPath
                InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
            }
            val queue = makeQueue(FakeInferenceUseCase(birdnetReanalysisJob = inference), repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = inference,
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )

            vm.reanalyze(runBirdnet = true, runPerch = false)
            advanceUntilIdle()

            assertThat(capturedPath).isEqualTo(audioFile.absolutePath)
        }

    @Test
    fun `inference failure surfaces error and clears progress`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d6"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_INFERENCE))
            }
            val failingJob = InferenceJob { _, _, _, _, _ ->
                InferenceOutcome.Failure("Model not installed")
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(FakeInferenceUseCase(birdnetJob = failingJob), repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = failingJob,
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            advanceUntilIdle()
            assertThat(vm.state.value.inferenceError).isEqualTo("Model not installed")
            assertThat(vm.state.value.inferenceProgress).isNull()
            assertThat(vm.state.value.status).isEqualTo(DraftStatus.PENDING_INFERENCE)
        }

    @Test
    fun `onWindowTapped seeks player and highlights matching species`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d8"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 88L,
                            draftId = draftId,
                            taxonScientificName = "Turdus merula",
                            taxonCommonName = "Common Blackbird",
                            maxConfidence = 0.81f,
                            detectedWindows = 1,
                            firstSeenMs = 0L,
                            lastSeenMs = 3_000L,
                            isSelectedByUser = false,
                        ),
                    ),
                )
            }
            val window = WindowPrediction(
                startMs = 1_500L,
                endMs = 4_500L,
                taxonScientificName = "Turdus merula",
                taxonCommonName = "Common Blackbird",
                confidence = 0.81f,
            )
            val player = FakeAudioPlayer()
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = player,
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            // Species are loaded from DB.
            val rowId = vm.state.value.species.first().detectionId
            assertThat(rowId).isEqualTo(88L)

            vm.onWindowTapped(window)

            assertThat(player.seekToMs).isEqualTo(1_500L)
            assertThat(vm.highlight.value).isEqualTo(rowId)
        }

    @Test
    fun `highlight clears after 800 ms`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "d9"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val detectionDao = FakeDetectionDao().apply {
            insertAll(
                listOf(
                    DetectionEntity(
                        id = 7L,
                        draftId = draftId,
                        taxonScientificName = "Parus major",
                        taxonCommonName = null,
                        maxConfidence = 0.9f,
                        detectedWindows = 1,
                        firstSeenMs = 0L,
                        lastSeenMs = 1_000L,
                        isSelectedByUser = false,
                    ),
                ),
            )
        }
        val repo = repo(draftDao, detectionDao)
        val queue = makeQueue(draftRepo = repo)
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = FakeAudioPlayer(),
            inference = noopInference(),
            queue = queue,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
        )
        vm.highlight(7L)
        assertThat(vm.highlight.value).isEqualTo(7L)
        // Just before the timer fires -> still highlighted.
        advanceTimeBy(799L)
        runCurrent()
        assertThat(vm.highlight.value).isEqualTo(7L)
        // After 800 ms -> auto-cleared.
        advanceTimeBy(2L)
        runCurrent()
        assertThat(vm.highlight.value).isNull()
    }

    @Test
    fun `re-highlighting cancels previous timer so new id stays for full duration`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d10"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            vm.highlight(1L)
            advanceTimeBy(500L)
            runCurrent()
            // Switch to a different id midway through the first timer.
            vm.highlight(2L)
            // Original timer would have fired by now; new one must still hold.
            advanceTimeBy(500L)
            runCurrent()
            assertThat(vm.highlight.value).isEqualTo(2L)
            advanceTimeBy(301L)
            runCurrent()
            assertThat(vm.highlight.value).isNull()
        }

    @Test
    fun `isPerchInstalled true when probe returns true`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "p1"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 1L,
                            draftId = draftId,
                            taxonScientificName = "Turdus merula",
                            taxonCommonName = "Common Blackbird",
                            maxConfidence = 0.81f,
                            detectedWindows = 3,
                            firstSeenMs = 0L,
                            lastSeenMs = 3_000L,
                            isSelectedByUser = false,
                            sources = SourceStats.encode(mapOf("birdnet_v2_4" to SourceStat(0.81f, 3, 0L, 3_000L))),
                        ),
                    ),
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                perchInstalledProbe = { true },
                externalScope = backgroundScope,
            )
            assertThat(vm.state.value.isPerchInstalled).isTrue()
        }

    @Test
    fun `isPerchInstalled stays true after a Perch run produced rows`() =
        runTest(UnconfinedTestDispatcher()) {
            // Regression for the old "canAnalyzeWithPerch" gate that flipped
            // off once the draft had any perch_v2 row. The model picker now
            // gates on installation only — re-running Perch is allowed and
            // merges into existing detections.
            val draftId = "p2"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 1L,
                            draftId = draftId,
                            taxonScientificName = "Rana temporaria",
                            taxonCommonName = "Common Frog",
                            maxConfidence = 0.7f,
                            detectedWindows = 2,
                            firstSeenMs = 1_000L,
                            lastSeenMs = 4_000L,
                            isSelectedByUser = false,
                            sources = SourceStats.encode(mapOf("perch_v2" to SourceStat(0.7f, 2, 1_000L, 4_000L))),
                        ),
                    ),
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                perchInstalledProbe = { true },
                externalScope = backgroundScope,
            )
            assertThat(vm.state.value.isPerchInstalled).isTrue()
        }

    @Test
    fun `reanalyze Perch-only merges new species with existing detections`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "p3"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 1L,
                            draftId = draftId,
                            taxonScientificName = "Turdus merula",
                            taxonCommonName = "Common Blackbird",
                            maxConfidence = 0.81f,
                            detectedWindows = 3,
                            firstSeenMs = 0L,
                            lastSeenMs = 3_000L,
                            isSelectedByUser = false,
                            sources = SourceStats.encode(mapOf("birdnet_v2_4" to SourceStat(0.81f, 3, 0L, 3_000L))),
                        ),
                    ),
                )
            }
            val perchAgg = AggregatedDetection(
                taxonScientificName = "Rana temporaria",
                taxonCommonName = "Common Frog",
                maxConfidence = 0.65f,
                detectedWindows = 2,
                firstSeenMs = 1_000L,
                lastSeenMs = 4_000L,
                confidenceBySource = mapOf("perch_v2" to 0.65f),
            )
            val perchJob = PerchAnalysisJob { _, _, _, _, onProgress ->
                onProgress(0.5f)
                onProgress(1f)
                PerchAnalysisOutcome.Success(listOf(perchAgg))
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(FakeInferenceUseCase(perchReanalysisJob = perchJob), repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                perchReanalysis = perchJob,
                perchInstalledProbe = { true },
                externalScope = backgroundScope,
            )
            assertThat(vm.state.value.isPerchInstalled).isTrue()

            vm.reanalyze(runBirdnet = false, runPerch = true)

            // Perch run finished → progress cleared, both species persisted.
            assertThat(vm.state.value.perchProgress).isNull()
            val names = vm.state.value.species.map { it.taxonScientificName }
            assertThat(names).containsExactly("Turdus merula", "Rana temporaria")
            // Installation flag stays true — re-running Perch is allowed.
            assertThat(vm.state.value.isPerchInstalled).isTrue()
            // The newly-attached row carries the perch source key.
            val frog = vm.state.value.species.first { it.taxonScientificName == "Rana temporaria" }
            assertThat(frog.confidenceBySource.keys).contains("perch_v2")
        }

    @Test
    fun `minWindows filters species from DB path`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "d11"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val detectionDao = FakeDetectionDao().apply {
            insertAll(
                listOf(
                    DetectionEntity(
                        id = 10L,
                        draftId = draftId,
                        taxonScientificName = "Cuculus canorus",
                        taxonCommonName = "Common Cuckoo",
                        maxConfidence = 0.7f,
                        detectedWindows = 1,
                        firstSeenMs = 0L,
                        lastSeenMs = 1_000L,
                        isSelectedByUser = false,
                    ),
                    DetectionEntity(
                        id = 11L,
                        draftId = draftId,
                        taxonScientificName = "Parus major",
                        taxonCommonName = "Great Tit",
                        maxConfidence = 0.9f,
                        detectedWindows = 3,
                        firstSeenMs = 0L,
                        lastSeenMs = 3_000L,
                        isSelectedByUser = false,
                    ),
                ),
            )
        }
        val repo = repo(draftDao, detectionDao)
        val queue = makeQueue(draftRepo = repo)
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = FakeAudioPlayer(),
            inference = noopInference(),
            queue = queue,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            minWindowsProvider = { 2 },
            externalScope = backgroundScope,
        )
        val names = vm.state.value.species.map { it.taxonScientificName }
        assertThat(names).containsExactly("Parus major")
        assertThat(names).doesNotContain("Cuculus canorus")
    }

    @Test
    fun `launchAnnotation reads from repo cache and skips network for cached species`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "ann1"
            // Draft with GPS so annotation is triggered.
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 1L,
                            draftId = draftId,
                            taxonScientificName = "Parus major",
                            taxonCommonName = "Great Tit",
                            maxConfidence = 0.9f,
                            detectedWindows = 1,
                            firstSeenMs = 0L,
                            lastSeenMs = 1_000L,
                            isSelectedByUser = false,
                        ),
                        DetectionEntity(
                            id = 2L,
                            draftId = draftId,
                            taxonScientificName = "Corvus cornix",
                            taxonCommonName = "Carrion Crow",
                            maxConfidence = 0.7f,
                            detectedWindows = 1,
                            firstSeenMs = 0L,
                            lastSeenMs = 1_000L,
                            isSelectedByUser = false,
                        ),
                    ),
                )
            }
            // draftFor() sets lat=40.0, lon=-3.0 — pre-populate statusRepo for Parus major at that bucket.
            val lookup = CountingLookup(placeIds = listOf(1L), inPlaces = true)
            val filter = RegionFilter(lookup)
            val statusRepo = RegionalStatusRepository.forTest(
                annotator = { _, _, _ -> RegionalStatus.CONFIRMED },
            )
            statusRepo.storeResult("Parus major", 40.0, -3.0, RegionalStatus.CONFIRMED)

            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                regionFilter = filter,
                regionalStatusRepository = statusRepo,
                externalScope = backgroundScope,
            )

            // Only Corvus cornix (the cache miss) should reach the network.
            // Parus major is served from the repo cache → no checkInPlaces call for it.
            // One placeIds call for the location + one checkInPlaces for Corvus cornix only.
            assertThat(lookup.checkCalls).isEqualTo(1)
        }

    @Test
    fun `reanalyze BirdNET-only calls inferenceReanalysis not inference`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "reanalyze_birdnet"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            var gatedCalls = 0
            var noGateCalls = 0
            val inference = InferenceJob { _, _, _, _, _ ->
                gatedCalls++
                InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
            }
            val inferenceReanalysis = InferenceJob { _, _, _, _, _ ->
                noGateCalls++
                InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(
                FakeInferenceUseCase(birdnetJob = inference, birdnetReanalysisJob = inferenceReanalysis),
                repo,
            )
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = inference,
                inferenceReanalysis = inferenceReanalysis,
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            vm.reanalyze(runBirdnet = true, runPerch = false)
            assertThat(noGateCalls).isEqualTo(1)
            assertThat(gatedCalls).isEqualTo(0)
        }

    @Test
    fun `reanalyze Perch-only calls perchReanalysis`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "reanalyze_perch"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            var perchReanalysisCalls = 0
            val perchReanalysis = PerchAnalysisJob { _, _, _, _, _ ->
                perchReanalysisCalls++
                PerchAnalysisOutcome.Success(emptyList())
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(FakeInferenceUseCase(perchReanalysisJob = perchReanalysis), repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                perchReanalysis = perchReanalysis,
                perchInstalledProbe = { true },
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            vm.reanalyze(runBirdnet = false, runPerch = true)
            assertThat(perchReanalysisCalls).isEqualTo(1)
            assertThat(vm.state.value.perchProgress).isNull()
            assertThat(vm.state.value.perchError).isNull()
        }

    @Test
    fun `reanalyze both runs BirdNET then Perch in order`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "reanalyze_both"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val order = mutableListOf<String>()
            val inferenceReanalysis = InferenceJob { _, _, _, _, _ ->
                order += "birdnet"
                InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
            }
            val perchReanalysis = PerchAnalysisJob { _, _, _, _, _ ->
                order += "perch"
                PerchAnalysisOutcome.Success(emptyList())
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(
                FakeInferenceUseCase(birdnetReanalysisJob = inferenceReanalysis, perchReanalysisJob = perchReanalysis),
                repo,
            )
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                inferenceReanalysis = inferenceReanalysis,
                perchReanalysis = perchReanalysis,
                perchInstalledProbe = { true },
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            vm.reanalyze(runBirdnet = true, runPerch = true)
            assertThat(order).containsExactly("birdnet", "perch").inOrder()
        }

    // ─── Audio Export Tests ─────────────────────────────────────────────────

    @Test
    fun `onShareFullRecording emits ShareAudioFile when audio exists`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "export_share1"
            val audioFile = createSilentWav(tmp.newFile("$draftId.wav"))
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, DraftStatus.PENDING_REVIEW).copy(audioPath = audioFile.absolutePath))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = makeQueue(draftRepo = repo),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )

            vm.onShareFullRecording()

            val effect = vm.state.value.exportEffect
            assertThat(effect).isInstanceOf(ReviewExportEffect.ShareAudioFile::class.java)
            assertThat((effect as ReviewExportEffect.ShareAudioFile).file.absolutePath)
                .isEqualTo(audioFile.absolutePath)
            assertThat(vm.state.value.exportingAction).isNull()
        }

    @Test
    fun `onShareFullRecording emits snackbar when audio file missing`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "export_share2"
            val missingPath = File(tmp.root, "does_not_exist.wav").absolutePath
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, DraftStatus.PENDING_REVIEW).copy(audioPath = missingPath))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = makeQueue(draftRepo = repo),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )

            vm.onShareFullRecording()

            val effect = vm.state.value.exportEffect
            assertThat(effect).isInstanceOf(ReviewExportEffect.ShowSnackbar::class.java)
            assertThat((effect as ReviewExportEffect.ShowSnackbar).message)
                .isEqualTo("Audio file is missing")
            assertThat(vm.state.value.exportingAction).isNull()
        }

    @Test
    fun `onSaveFullRecording calls audioSaver and emits success snackbar`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "export_save1"
            val audioFile = createSilentWav(tmp.newFile("$draftId.wav"))
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, DraftStatus.PENDING_REVIEW).copy(audioPath = audioFile.absolutePath))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            var savedFile: File? = null
            var savedName: String? = null
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = makeQueue(draftRepo = repo),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
                audioSaver = AudioSaver { f, n ->
                    savedFile = f
                    savedName = n
                    Uri.EMPTY
                },
            )

            vm.onSaveFullRecording()

            assertThat(savedFile?.absolutePath).isEqualTo(audioFile.absolutePath)
            assertThat(savedName).startsWith("wildear_original_")
            assertThat(savedName).endsWith(".wav")
            val effect = vm.state.value.exportEffect
            assertThat(effect).isInstanceOf(ReviewExportEffect.ShowSnackbar::class.java)
            assertThat((effect as ReviewExportEffect.ShowSnackbar).message)
                .isEqualTo("Audio saved to Downloads")
            assertThat(vm.state.value.exportingAction).isNull()
        }

    @Test
    fun `onSaveFullRecording emits not-supported snackbar on UnsupportedOperationException`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "export_save2"
            val audioFile = createSilentWav(tmp.newFile("$draftId.wav"))
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, DraftStatus.PENDING_REVIEW).copy(audioPath = audioFile.absolutePath))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = makeQueue(draftRepo = repo),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
                audioSaver = AudioSaver { _, _ -> throw UnsupportedOperationException("API 28") },
            )

            vm.onSaveFullRecording()

            val effect = vm.state.value.exportEffect
            assertThat(effect).isInstanceOf(ReviewExportEffect.ShowSnackbar::class.java)
            assertThat((effect as ReviewExportEffect.ShowSnackbar).message)
                .isEqualTo("Saving to Downloads is not supported on this Android version")
        }

    @Test
    fun `onSaveFullRecording emits missing-file snackbar when file absent`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "export_save3"
            val missingPath = File(tmp.root, "gone.wav").absolutePath
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, DraftStatus.PENDING_REVIEW).copy(audioPath = missingPath))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = makeQueue(draftRepo = repo),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
                audioSaver = AudioSaver { _, _ -> Uri.EMPTY },
            )

            vm.onSaveFullRecording()

            val effect = vm.state.value.exportEffect
            assertThat(effect).isInstanceOf(ReviewExportEffect.ShowSnackbar::class.java)
            assertThat((effect as ReviewExportEffect.ShowSnackbar).message)
                .isEqualTo("Audio file is missing")
        }

    @Test
    fun `consumeExportEffect clears exportEffect`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "export_consume1"
            val missingPath = File(tmp.root, "gone.wav").absolutePath
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, DraftStatus.PENDING_REVIEW).copy(audioPath = missingPath))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = makeQueue(draftRepo = repo),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            vm.onShareFullRecording()
            assertThat(vm.state.value.exportEffect).isNotNull()

            vm.consumeExportEffect()

            assertThat(vm.state.value.exportEffect).isNull()
        }

    @Test
    fun `onShareSpeciesClip emits ShareAudioFile for valid WAV`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "export_clip_share1"
            val audioFile = createSilentWav(tmp.newFile("$draftId.wav"))
            val draftDao = FakeDraftDao().apply {
                insert(
                    draftFor(draftId, DraftStatus.PENDING_REVIEW).copy(
                        audioPath = audioFile.absolutePath,
                        durationMs = 1_000L,
                    )
                )
            }
            val clipDir = tmp.newFolder("export_clips_$draftId")
            val repo = repo(draftDao, FakeDetectionDao())
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = makeQueue(draftRepo = repo),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
                exportClipsDir = clipDir,
            )
            val row = SpeciesRow(
                detectionId = 1L,
                taxonScientificName = "Turdus merula",
                taxonCommonName = "Common Blackbird",
                maxConfidence = 0.8f,
                detectedWindows = 1,
                firstSeenMs = 0L,
                lastSeenMs = 500L,
                isSelected = true,
            )

            vm.onShareSpeciesClip(row)

            val effect = vm.state.value.exportEffect
            assertThat(effect).isInstanceOf(ReviewExportEffect.ShareAudioFile::class.java)
            val clipFile = (effect as ReviewExportEffect.ShareAudioFile).file
            assertThat(clipFile.exists()).isTrue()
            assertThat(clipFile.length()).isGreaterThan(0L)
            assertThat(vm.state.value.exportingAction).isNull()
        }

    @Test
    fun `onSaveSpeciesClip emits error snackbar when source WAV missing`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "export_clip_save_err"
            val missingPath = File(tmp.root, "no_audio.wav").absolutePath
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, DraftStatus.PENDING_REVIEW).copy(audioPath = missingPath))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = makeQueue(draftRepo = repo),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
                exportClipsDir = tmp.newFolder("clips_err"),
                audioSaver = AudioSaver { _, _ -> Uri.EMPTY },
            )
            val row = SpeciesRow(
                detectionId = 2L,
                taxonScientificName = "Parus major",
                taxonCommonName = null,
                maxConfidence = 0.9f,
                detectedWindows = 1,
                firstSeenMs = 0L,
                lastSeenMs = 3_000L,
                isSelected = true,
            )

            vm.onSaveSpeciesClip(row)

            val effect = vm.state.value.exportEffect
            assertThat(effect).isInstanceOf(ReviewExportEffect.ShowSnackbar::class.java)
            assertThat((effect as ReviewExportEffect.ShowSnackbar).message)
                .isEqualTo("Audio file is missing")
            assertThat(vm.state.value.exportingAction).isNull()
        }

    /** Creates a valid mono 16-bit PCM WAV file containing [durationMs] ms of silence. */
    private fun createSilentWav(dest: File, sampleRate: Int = 16_000, durationMs: Long = 1_000): File {
        val samples = ((sampleRate.toLong() * durationMs) / 1000L).toInt()
        val writer = com.sound2inat.recorder.WavWriter(dest, sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        writer.writeShorts(ShortArray(samples), 0, samples)
        writer.close()
        return dest
    }

    /** Writes a long mono 16-bit PCM WAV without allocating the whole sample buffer at once. */
    private fun createLargeSilentWavStreaming(
        dest: File,
        sampleRate: Int = 48_000,
        durationMs: Long = 4 * 60_000L,
    ): File {
        val totalSamples = ((sampleRate.toLong() * durationMs) / 1000L).toInt()
        val writer = com.sound2inat.recorder.WavWriter(dest, sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        val chunk = ShortArray(8_192)
        var written = 0
        while (written < totalSamples) {
            val take = minOf(chunk.size, totalSamples - written)
            writer.writeShorts(chunk, 0, take)
            written += take
        }
        writer.close()
        return dest
    }

    /** Creates a valid mono 16-bit PCM WAV file filled with a constant sample value. */
    private fun createConstantWav(
        dest: File,
        sampleRate: Int = 16_000,
        durationMs: Long = 1_000,
        sampleValue: Int,
    ): File {
        val samples = ((sampleRate.toLong() * durationMs) / 1000L).toInt()
        val writer = com.sound2inat.recorder.WavWriter(dest, sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        val shorts = ShortArray(samples) { sampleValue.toShort() }
        writer.writeShorts(shorts, 0, shorts.size)
        writer.close()
        return dest
    }

    private fun draftFor(id: String, status: DraftStatus): DraftEntity = DraftEntity(
        id = id,
        audioPath = "/tmp/$id.wav",
        recordedAtUtcMs = 1_000_000L,
        durationMs = 5_000L,
        latitude = 40.0,
        longitude = -3.0,
        locationAccuracyMeters = 5f,
        status = status,
        modelId = null,
        modelVersion = null,
        createdAtUtcMs = 0L,
        updatedAtUtcMs = 0L,
    )

    private fun repo(drafts: DraftDao, detections: DetectionDao): DraftRepository {
        val files = WavFileStore(tmp.root)
        return DraftRepository(
            drafts = drafts,
            detections = detections,
            files = files,
            nowMs = { 0L },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun noopInference(): InferenceJob = InferenceJob { _, _, _, _, _ ->
        InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
    }

    private fun TestScope.makeQueue(
        useCase: InferenceUseCase = FakeInferenceUseCase(),
        draftRepo: DraftRepository,
    ): InferenceQueue = InferenceQueue(
        scope = backgroundScope,
        inferenceUseCase = useCase,
        repo = draftRepo,
    )

    @Test
    fun `queue status Queued flows into queuePosition on ReviewUiState`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "queue1"
            val otherId = "queue_blocker"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
                insert(draftFor(otherId, status = DraftStatus.PENDING_REVIEW))
            }
            val repo = repo(draftDao, FakeDetectionDao())

            val latch = CompletableDeferred<Unit>()
            val blockingJob = InferenceJob { _, _, _, _, _ ->
                latch.await()
                InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
            }
            val queue = makeQueue(
                FakeInferenceUseCase(birdnetReanalysisJob = blockingJob),
                repo,
            )

            // Occupy the worker with a different draft.
            queue.enqueue(QueuedJob(otherId, "/tmp/other.wav", null, null, 0L, true, false, true))
            runCurrent()

            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )

            // Enqueue our draftId — it goes pending (worker is busy with otherId).
            vm.reanalyze(runBirdnet = true, runPerch = false)
            runCurrent()

            assertThat(vm.state.value.queuePosition).isNotNull()
            assertThat(vm.state.value.inferenceProgress).isNull()

            latch.complete(Unit)
            advanceUntilIdle()

            assertThat(vm.state.value.queuePosition).isNull()
            assertThat(vm.state.value.inferenceProgress).isNull()
        }

    @Test
    fun `loadObservationDetail sets Loaded state on successful fetch`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "obs1"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 99L,
                            draftId = draftId,
                            taxonScientificName = "Parus major",
                            taxonCommonName = null,
                            maxConfidence = 0.9f,
                            detectedWindows = 1,
                            firstSeenMs = 0L,
                            lastSeenMs = 1_000L,
                            isSelectedByUser = false,
                        )
                    )
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            val expectedDetail = com.sound2inat.inat.ObservationDetail(
                qualityGrade = "research",
                agreeingIdCount = 3,
                commentsCount = 1,
                comments = listOf(com.sound2inat.inat.ObservationComment("alice", "Nice!")),
            )
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
                observationFetcher = { expectedDetail },
            )
            vm.loadObservationDetail(detectionId = 99L, observationId = 12345L)
            val row = vm.state.value.species.firstOrNull { it.detectionId == 99L }
            assertThat(row).isNotNull()
            val detailState = row!!.observationDetailState
            assertThat(detailState).isInstanceOf(ObservationDetailLoadState.Loaded::class.java)
            assertThat((detailState as ObservationDetailLoadState.Loaded).detail.qualityGrade)
                .isEqualTo("research")
            assertThat(detailState.detail.agreeingIdCount).isEqualTo(3)
        }

    @Test
    fun `loadObservationDetail sets Error when fetcher throws`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "obs2"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 77L,
                            draftId = draftId,
                            taxonScientificName = "Apus apus",
                            taxonCommonName = null,
                            maxConfidence = 0.8f,
                            detectedWindows = 1,
                            firstSeenMs = 0L,
                            lastSeenMs = 1_000L,
                            isSelectedByUser = false,
                        )
                    )
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
                observationFetcher = { throw com.sound2inat.inat.INatException(500, "Server error") },
            )
            vm.loadObservationDetail(detectionId = 77L, observationId = 99999L)
            val row = vm.state.value.species.firstOrNull { it.detectionId == 77L }
            assertThat(row).isNotNull()
            assertThat(row!!.observationDetailState)
                .isInstanceOf(ObservationDetailLoadState.Error::class.java)
        }

    @Test
    fun `loadObservationDetail returns cached result on second call`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "obs3"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 55L,
                            draftId = draftId,
                            taxonScientificName = "Turdus merula",
                            taxonCommonName = null,
                            maxConfidence = 0.85f,
                            detectedWindows = 1,
                            firstSeenMs = 0L,
                            lastSeenMs = 1_000L,
                            isSelectedByUser = false,
                        )
                    )
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            var fetchCount = 0
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
                observationFetcher = {
                    fetchCount++
                    com.sound2inat.inat.ObservationDetail("research", 1, 0, emptyList())
                },
            )
            vm.loadObservationDetail(55L, 111L)
            vm.collapseObservationDetail(55L)
            vm.loadObservationDetail(55L, 111L)
            assertThat(fetchCount).isEqualTo(1)
            val row = vm.state.value.species.first { it.detectionId == 55L }
            assertThat(row.observationDetailState).isInstanceOf(ObservationDetailLoadState.Loaded::class.java)
        }

    @Test
    fun `mergeBySpecies propagates per-source windows and time bounds`() =
        runTest(UnconfinedTestDispatcher()) {
            val existing = listOf(
                AggregatedDetection(
                    taxonScientificName = "Parus major",
                    taxonCommonName = null,
                    maxConfidence = 0.85f,
                    detectedWindows = 2,
                    firstSeenMs = 0L,
                    lastSeenMs = 6_000L,
                    confidenceBySource = mapOf("birdnet_v2_4" to 0.85f),
                    windowsBySource = mapOf("birdnet_v2_4" to 2),
                    firstSeenBySource = mapOf("birdnet_v2_4" to 0L),
                    lastSeenBySource = mapOf("birdnet_v2_4" to 6_000L),
                ),
            )
            val incoming = listOf(
                AggregatedDetection(
                    taxonScientificName = "Parus major",
                    taxonCommonName = null,
                    maxConfidence = 0.62f,
                    detectedWindows = 1,
                    firstSeenMs = 3_000L,
                    lastSeenMs = 8_000L,
                    confidenceBySource = mapOf("perch_v2" to 0.62f),
                    windowsBySource = mapOf("perch_v2" to 1),
                    firstSeenBySource = mapOf("perch_v2" to 3_000L),
                    lastSeenBySource = mapOf("perch_v2" to 8_000L),
                ),
            )
            val result = repo(FakeDraftDao(), FakeDetectionDao()).mergeBySpecies(existing, incoming).first()

            assertThat(result.windowsBySource).containsExactly("birdnet_v2_4", 2, "perch_v2", 1)
            assertThat(result.firstSeenBySource).containsExactly("birdnet_v2_4", 0L, "perch_v2", 3_000L)
            assertThat(result.lastSeenBySource).containsExactly("birdnet_v2_4", 6_000L, "perch_v2", 8_000L)
        }

    @Test
    fun `habitat photos from DAO appear in state`() = runTest(UnconfinedTestDispatcher()) {
        val draftId = "photo-test"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val detectionDao = FakeDetectionDao()
        val repo = repo(draftDao, detectionDao)
        val queue = makeQueue(draftRepo = repo)
        val photoDao = FakeDraftPhotoDao(
            mutableListOf(
                DraftPhotoEntity(id = "p1", draftId = draftId, photoPath = "/a.jpg", takenAtMs = 1L),
            ),
        )
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = FakeAudioPlayer(),
            inference = noopInference(),
            queue = queue,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            habitatPhotosFlow = photoDao.photosForDraft(draftId),
            externalScope = backgroundScope,
        )
        assertThat(vm.state.value.habitatPhotos).hasSize(1)
        assertThat(vm.state.value.habitatPhotos[0].id).isEqualTo("p1")
    }

    @Test
    fun `collapseObservationDetail resets state to NotLoaded`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "obs4"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 33L,
                            draftId = draftId,
                            taxonScientificName = "Hirundo rustica",
                            taxonCommonName = null,
                            maxConfidence = 0.75f,
                            detectedWindows = 1,
                            firstSeenMs = 0L,
                            lastSeenMs = 1_000L,
                            isSelectedByUser = false,
                        )
                    )
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
                observationFetcher = {
                    com.sound2inat.inat.ObservationDetail("needs_id", 0, 0, emptyList())
                },
            )
            vm.loadObservationDetail(33L, 222L)
            val rowAfterLoad = vm.state.value.species.first { it.detectionId == 33L }
            assertThat(rowAfterLoad.observationDetailState)
                .isInstanceOf(ObservationDetailLoadState.Loaded::class.java)

            vm.collapseObservationDetail(33L)
            val rowAfterCollapse = vm.state.value.species.first { it.detectionId == 33L }
            assertThat(rowAfterCollapse.observationDetailState)
                .isEqualTo(ObservationDetailLoadState.NotLoaded)
        }

    @Test
    fun `submit threads onProgress events and clears progress when done`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d_progress"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 1L,
                            draftId = draftId,
                            taxonScientificName = "Parus major",
                            taxonCommonName = null,
                            maxConfidence = 0.9f,
                            detectedWindows = 3,
                            firstSeenMs = 0L,
                            lastSeenMs = 3_000L,
                            isSelectedByUser = true,
                        ),
                        DetectionEntity(
                            id = 2L,
                            draftId = draftId,
                            taxonScientificName = "Turdus merula",
                            taxonCommonName = null,
                            maxConfidence = 0.7f,
                            detectedWindows = 2,
                            firstSeenMs = 1_000L,
                            lastSeenMs = 2_000L,
                            isSelectedByUser = true,
                        ),
                    ),
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            val submission = InatSubmissionJob { _, _, _, _, _, onProgress ->
                onProgress(
                    com.sound2inat.inat.SubmissionProgress.Species(
                        speciesIndex = 1,
                        totalSpecies = 2,
                        taxonScientificName = "Parus major",
                        step = com.sound2inat.inat.SubmissionProgress.Step.CreatingObservation,
                    )
                )
                onProgress(
                    com.sound2inat.inat.SubmissionProgress.Species(
                        speciesIndex = 2,
                        totalSpecies = 2,
                        taxonScientificName = "Turdus merula",
                        step = com.sound2inat.inat.SubmissionProgress.Step.DoneOk,
                    )
                )
                InatSubmissionOutcome.Success(
                    listOf("https://www.inaturalist.org/observations/1", "https://www.inaturalist.org/observations/2")
                )
            }
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                submission = submission,
                tokenProvider = { "jwt" },
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            advanceUntilIdle()

            vm.submitToINaturalist()
            advanceUntilIdle()

            assertThat(vm.state.value.submissionProgress).isNull()
            assertThat(vm.state.value.inatSubmission)
                .isInstanceOf(InatSubmissionState.Done::class.java)
        }

    /**
     * Regression: the per-species progress checklist must reflect the
     * selection captured at the moment submission started, not a later
     * mutation of [SpeciesRow.isSelected]. We freeze [ReviewUiState.pendingSubmissionSpecies]
     * inside the VM and clear it on terminal success/failure.
     */
    @Test
    fun `submitToINaturalist freezes pendingSubmissionSpecies at start and clears at end`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d_pending_snapshot"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val detectionDao = FakeDetectionDao().apply {
                insertAll(
                    listOf(
                        DetectionEntity(
                            id = 1L,
                            draftId = draftId,
                            taxonScientificName = "Parus major",
                            taxonCommonName = null,
                            maxConfidence = 0.9f,
                            detectedWindows = 3,
                            firstSeenMs = 0L,
                            lastSeenMs = 3_000L,
                            isSelectedByUser = true,
                        ),
                        DetectionEntity(
                            id = 2L,
                            draftId = draftId,
                            taxonScientificName = "Turdus merula",
                            taxonCommonName = null,
                            maxConfidence = 0.7f,
                            detectedWindows = 2,
                            firstSeenMs = 1_000L,
                            lastSeenMs = 2_000L,
                            isSelectedByUser = true,
                        ),
                    ),
                )
            }
            val repo = repo(draftDao, detectionDao)
            val queue = makeQueue(draftRepo = repo)
            val gate = CompletableDeferred<Unit>()
            val submission = InatSubmissionJob { _, _, _, _, _, _ ->
                gate.await()
                InatSubmissionOutcome.Success(
                    listOf("https://www.inaturalist.org/observations/1")
                )
            }
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                submission = submission,
                tokenProvider = { "jwt" },
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            advanceUntilIdle()

            vm.submitToINaturalist()
            advanceUntilIdle()

            // Mid-flight: snapshot reflects the two species selected at the
            // moment submitToINaturalist() ran. Toggling selection now must
            // not corrupt the displayed checklist.
            assertThat(vm.state.value.inatSubmission)
                .isEqualTo(InatSubmissionState.InProgress)
            assertThat(vm.state.value.pendingSubmissionSpecies)
                .containsExactly("Parus major", "Turdus merula").inOrder()

            vm.toggle(detectionId = 2L, selected = false)
            advanceUntilIdle()
            assertThat(vm.state.value.pendingSubmissionSpecies)
                .containsExactly("Parus major", "Turdus merula").inOrder()

            // Let the submission complete.
            gate.complete(Unit)
            advanceUntilIdle()

            assertThat(vm.state.value.inatSubmission)
                .isInstanceOf(InatSubmissionState.Done::class.java)
            assertThat(vm.state.value.pendingSubmissionSpecies).isNull()
        }

    @Test
    fun `retryIncomplete invokes deleter then clears retry flag`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d_retry_ok"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(draftRepo = repo)
            val calls = mutableListOf<Pair<Long, Long>>()
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                deleteAndForgetIncomplete = { rowId, obsId -> calls += rowId to obsId },
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            advanceUntilIdle()

            vm.retryIncomplete(rowId = 7L, observationId = 42L)
            advanceUntilIdle()

            assertThat(calls).containsExactly(7L to 42L)
            assertThat(vm.state.value.retryingIncomplete).isEmpty()
            assertThat(vm.state.value.retryIncompleteError).isNull()
        }

    @Test
    fun `retryIncomplete surfaces error when deleter throws`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d_retry_fail"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(draftRepo = repo)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                deleteAndForgetIncomplete = { _, _ -> throw IllegalStateException("boom") },
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            advanceUntilIdle()

            vm.retryIncomplete(rowId = 1L, observationId = 99L)
            advanceUntilIdle()

            assertThat(vm.state.value.retryingIncomplete).isEmpty()
            assertThat(vm.state.value.retryIncompleteError).isEqualTo("boom")
        }

    @Test
    fun `retryIncomplete is idempotent while in flight`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d_retry_idem"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val repo = repo(draftDao, FakeDetectionDao())
            val queue = makeQueue(draftRepo = repo)
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = noopInference(),
                deleteAndForgetIncomplete = { _, _ ->
                    calls.incrementAndGet()
                    gate.await()
                },
                queue = queue,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            advanceUntilIdle()

            vm.retryIncomplete(rowId = 5L, observationId = 50L)
            vm.retryIncomplete(rowId = 5L, observationId = 50L)

            gate.complete(Unit)
            advanceUntilIdle()

            assertThat(calls.get()).isEqualTo(1)
            assertThat(vm.state.value.retryingIncomplete).isEmpty()
        }
}

private class CountingLookup(
    private val placeIds: List<Long> = emptyList(),
    private val inPlaces: Boolean = false,
) : RegionLookup {
    var checkCalls = 0

    override suspend fun getPlaceIds(lat: Double, lon: Double): List<Long> = placeIds
    override suspend fun checkInPlaces(scientificName: String, placeIds: List<Long>): Boolean {
        checkCalls++
        return inPlaces
    }
    override suspend fun checkNear(
        scientificName: String,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): Boolean = false
}

private class FakeAudioPlayer : AudioPlayer {
    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position
    private val _playing = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _playing
    private val _duration = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _duration
    private val _err = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _err

    var startedPath: String? = null
    var paused = false
    var released = false
    var seekToMs: Long? = null

    override fun start(path: String) {
        startedPath = path
        _playing.value = true
    }

    override fun pause() {
        paused = true
        _playing.value = false
    }

    override fun seekTo(ms: Long) {
        seekToMs = ms
        _position.value = ms
    }

    override fun release() { released = true }
}

private class FakeDraftDao : DraftDao {
    private val rows = mutableMapOf<String, DraftEntity>()
    private val emitter = MutableStateFlow<List<DraftEntity>>(emptyList())

    override fun insert(d: DraftEntity) {
        rows[d.id] = d
        emitter.value = rows.values.toList()
    }

    override fun update(d: DraftEntity) {
        rows[d.id] = d
        emitter.value = rows.values.toList()
    }

    override fun delete(d: DraftEntity) {
        rows.remove(d.id)
        emitter.value = rows.values.toList()
    }

    override fun getById(id: String): DraftEntity? = rows[id]

    override fun observeAll(): Flow<List<DraftEntity>> = emitter

    override fun deleteById(id: String): Int =
        if (rows.remove(id) != null) {
            emitter.value = rows.values.toList()
            1
        } else {
            0
        }

    override fun updateStatusConditional(
        id: String,
        newStatus: DraftStatus,
        expectedStatus: DraftStatus,
    ): Int {
        val current = rows[id] ?: return 0
        if (current.status != expectedStatus) return 0
        rows[id] = current.copy(status = newStatus)
        emitter.value = rows.values.toList()
        return 1
    }

    override fun updatePalette(id: String, name: String?, ts: Long): Int {
        val current = rows[id] ?: return 0
        rows[id] = current.copy(paletteName = name, updatedAtUtcMs = ts)
        emitter.value = rows.values.toList()
        return 1
    }

    override fun updateSpectrogramGain(id: String, gain: Float?, ts: Long): Int {
        val current = rows[id] ?: return 0
        rows[id] = current.copy(spectrogramGainDb = gain, updatedAtUtcMs = ts)
        emitter.value = rows.values.toList()
        return 1
    }

    fun byId(id: String): DraftEntity? = rows[id]
}

private class FakeDraftPhotoDao(private val rows: MutableList<DraftPhotoEntity> = mutableListOf()) : DraftPhotoDao {
    private val emitter = MutableStateFlow(rows.toList())
    override fun insert(photo: DraftPhotoEntity) {
        rows += photo
        emitter.value = rows.toList()
    }
    override fun deleteById(id: String): Int {
        val removed = rows.removeAll { it.id == id }
        emitter.value = rows.toList()
        return if (removed) 1 else 0
    }
    override fun deleteByDraftId(draftId: String): Int {
        val before = rows.size
        rows.removeAll { it.draftId == draftId }
        emitter.value = rows.toList()
        return before - rows.size
    }
    override fun photosForDraft(draftId: String): kotlinx.coroutines.flow.Flow<List<DraftPhotoEntity>> =
        emitter.map { all -> all.filter { it.draftId == draftId } }
    override fun listForDraft(draftId: String): List<DraftPhotoEntity> = rows.filter { it.draftId == draftId }
}

private class FakeDetectionDao : DetectionDao {
    private val rows = mutableListOf<DetectionEntity>()
    private val emitter = MutableStateFlow<List<DetectionEntity>>(emptyList())
    val selections = mutableMapOf<Long, Boolean>()

    override fun insertAll(items: List<DetectionEntity>) {
        rows += items
        emitter.value = rows.toList()
    }

    override fun observeForDraft(draftId: String): Flow<List<DetectionEntity>> =
        emitter.map { all -> all.filter { it.draftId == draftId } }

    override fun listForDraft(draftId: String): List<DetectionEntity> =
        rows.filter { it.draftId == draftId }

    override fun setSelected(id: Long, selected: Boolean): Int {
        selections[id] = selected
        val idx = rows.indexOfFirst { it.id == id }
        if (idx < 0) return 0
        rows[idx] = rows[idx].copy(isSelectedByUser = selected)
        emitter.value = rows.toList()
        return 1
    }

    override fun deleteForDraft(draftId: String): Int {
        val before = rows.size
        rows.removeAll { it.draftId == draftId }
        emitter.value = rows.toList()
        return before - rows.size
    }
    override fun observeCountsByDraft(): kotlinx.coroutines.flow.Flow<List<com.sound2inat.storage.DraftDetectionCount>> =
        kotlinx.coroutines.flow.flowOf(emptyList())
}

private class FakeInferenceUseCase(
    birdnetJob: InferenceJob = InferenceJob { _, _, _, _, _ ->
        InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
    },
    birdnetReanalysisJob: InferenceJob = birdnetJob,
    perchJob: PerchAnalysisJob = PerchAnalysisJob { _, _, _, _, _ -> PerchAnalysisOutcome.NotInstalled },
    perchReanalysisJob: PerchAnalysisJob = perchJob,
) : InferenceUseCase {
    override val inference: InferenceJob = birdnetJob
    override val inferenceReanalysis: InferenceJob = birdnetReanalysisJob
    override val perchAnalysis: PerchAnalysisJob = perchJob
    override val perchReanalysis: PerchAnalysisJob = perchReanalysisJob
}
