package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.AggregatedDetection
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
            assertThat(vm.state.value.status).isEqualTo(DraftStatus.PENDING_REVIEW)
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
        return DraftRepository(drafts, detections, files) { 0L }
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

    override fun start(path: String) {
        startedPath = path
        _playing.value = true
    }

    override fun pause() {
        paused = true
        _playing.value = false
    }

    override fun seekTo(ms: Long) { _position.value = ms }
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
