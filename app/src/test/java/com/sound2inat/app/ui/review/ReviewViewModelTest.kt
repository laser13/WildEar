package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.SourceConfidences
import com.sound2inat.inference.WindowPrediction
import com.sound2inat.storage.DetectionDao
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.WavFileStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

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
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo,
                player = FakeAudioPlayer(),
                inference = inference,
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
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo(draftDao, detectionDao),
            player = FakeAudioPlayer(),
            inference = inference,
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
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo(draftDao, detectionDao),
                player = FakeAudioPlayer(),
                inference = noopInference(),
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
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo(draftDao, detectionDao),
            player = FakeAudioPlayer(),
            inference = noopInference(),
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
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo(draftDao, detectionDao),
            player = FakeAudioPlayer(),
            inference = noopInference(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
        )
        var deleted = false
        vm.delete(onDeleted = { deleted = true })
        assertThat(draftDao.byId(draftId)).isNull()
        assertThat(deleted).isTrue()
    }

    @Test
    fun `ensureVisuals populates spectrogramFile and waveformPeaks`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d7"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
            }
            val expectedPng = tmp.newFile("expected.png")
            val expectedPeaks = floatArrayOf(-0.5f, 0.5f, -0.25f, 0.25f)
            var calls = 0
            val provider = VisualsProvider { _, _, _ ->
                calls++
                Visuals(spectrogramFile = expectedPng, waveformPeaks = expectedPeaks)
            }
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo(draftDao, FakeDetectionDao()),
                player = FakeAudioPlayer(),
                inference = noopInference(),
                visuals = provider,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            vm.ensureVisuals(tmp.root)
            assertThat(vm.spectrogramFile.value).isEqualTo(expectedPng)
            assertThat(vm.waveformPeaks.value).isEqualTo(expectedPeaks)
            // Subsequent calls do NOT re-invoke the provider.
            vm.ensureVisuals(tmp.root)
            assertThat(calls).isEqualTo(1)
        }

    @Test
    fun `inference failure surfaces error and clears progress`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d6"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_INFERENCE))
            }
            val inference = InferenceJob { _, _, _, _, _ ->
                InferenceOutcome.Failure("Model not installed")
            }
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo(draftDao, FakeDetectionDao()),
                player = FakeAudioPlayer(),
                inference = inference,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            assertThat(vm.state.value.inferenceError).isEqualTo("Model not installed")
            assertThat(vm.state.value.inferenceProgress).isNull()
            assertThat(vm.state.value.status).isEqualTo(DraftStatus.PENDING_INFERENCE)
        }

    @Test
    fun `onWindowTapped seeks player and highlights matching species`() =
        runTest(UnconfinedTestDispatcher()) {
            val draftId = "d8"
            val draftDao = FakeDraftDao().apply {
                insert(draftFor(draftId, status = DraftStatus.PENDING_INFERENCE))
            }
            val agg = AggregatedDetection(
                taxonScientificName = "Turdus merula",
                taxonCommonName = "Common Blackbird",
                maxConfidence = 0.81f,
                detectedWindows = 1,
                firstSeenMs = 0L,
                lastSeenMs = 3_000L,
            )
            val window = WindowPrediction(
                startMs = 1_500L,
                endMs = 4_500L,
                taxonScientificName = "Turdus merula",
                taxonCommonName = "Common Blackbird",
                confidence = 0.81f,
            )
            val inference = InferenceJob { _, _, _, _, _ ->
                InferenceOutcome.Success(
                    "birdnet_v2_4",
                    "2.4",
                    listOf(agg),
                    windows = listOf(window),
                )
            }
            val player = FakeAudioPlayer()
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo(draftDao, FakeDetectionDao()),
                player = player,
                inference = inference,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )
            // Inference completed -> windows surfaced + species persisted.
            assertThat(vm.windowPreds.value).containsExactly(window)
            val rowId = vm.state.value.species.first().detectionId

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
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo(draftDao, detectionDao),
            player = FakeAudioPlayer(),
            inference = noopInference(),
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
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo(draftDao, FakeDetectionDao()),
                player = FakeAudioPlayer(),
                inference = noopInference(),
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
                            sources = SourceConfidences.encode(mapOf("birdnet_v2_4" to 0.81f)),
                        ),
                    ),
                )
            }
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo(draftDao, detectionDao),
                player = FakeAudioPlayer(),
                inference = noopInference(),
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
                            sources = SourceConfidences.encode(mapOf("perch_v2" to 0.7f)),
                        ),
                    ),
                )
            }
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo(draftDao, detectionDao),
                player = FakeAudioPlayer(),
                inference = noopInference(),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                perchInstalledProbe = { true },
                externalScope = backgroundScope,
            )
            assertThat(vm.state.value.isPerchInstalled).isTrue()
        }

    @Test
    fun `analyzeWithPerch merges new species with existing detections`() =
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
                            sources = SourceConfidences.encode(mapOf("birdnet_v2_4" to 0.81f)),
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
            val vm = ReviewViewModel(
                draftId = draftId,
                repo = repo(draftDao, detectionDao),
                player = FakeAudioPlayer(),
                inference = noopInference(),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                perchAnalysis = perchJob,
                perchInstalledProbe = { true },
                externalScope = backgroundScope,
            )
            assertThat(vm.state.value.isPerchInstalled).isTrue()

            vm.analyzeWithPerch()

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
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo(draftDao, detectionDao),
            player = FakeAudioPlayer(),
            inference = noopInference(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            minWindowsProvider = { 2 },
            externalScope = backgroundScope,
        )
        val names = vm.state.value.species.map { it.taxonScientificName }
        assertThat(names).containsExactly("Parus major")
        assertThat(names).doesNotContain("Cuculus canorus")
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
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun noopInference(): InferenceJob = InferenceJob { _, _, _, _, _ ->
        InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
    }
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

    fun byId(id: String): DraftEntity? = rows[id]
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
}
