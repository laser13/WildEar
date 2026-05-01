package com.sound2inat.app.ui.recording

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.permissions.Permission
import com.sound2inat.app.permissions.PermissionStatus
import com.sound2inat.app.permissions.PermissionsController
import com.sound2inat.inference.BioacousticModel
import com.sound2inat.inference.LiveInferenceEngine
import com.sound2inat.inference.LiveInferenceEngineFactory
import com.sound2inat.inference.WindowPrediction
import com.sound2inat.location.Fix
import com.sound2inat.location.LocationProvider
import com.sound2inat.recorder.Recorder
import com.sound2inat.recorder.RecordingResult
import com.sound2inat.storage.DetectionDao
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.WavFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class FakePerms(
    private val grants: Map<Permission, PermissionStatus>,
) : PermissionsController {
    private val _statuses = MutableStateFlow(grants)
    override val statuses: StateFlow<Map<Permission, PermissionStatus>> = _statuses
    override suspend fun request(permissions: Set<Permission>): Map<Permission, PermissionStatus> = grants
    override fun openAppSettings() = Unit
}

private class FakeRecorder : Recorder {
    private val _rms = MutableStateFlow(0f)
    override val rmsLevel: StateFlow<Float> = _rms
    private val _rmsHistory = MutableStateFlow(FloatArray(0))
    override val rmsHistory: StateFlow<FloatArray> = _rmsHistory
    override val audioBlocks: SharedFlow<FloatArray> = MutableSharedFlow()
    override val sampleRate: Int = 48_000
    var startCalled = false
    var stopCalled = false
    var cancelled = false
    var lastTarget: File? = null

    override suspend fun start(target: File) {
        startCalled = true
        lastTarget = target
        target.createNewFile()
    }

    override suspend fun stop(): RecordingResult {
        stopCalled = true
        val t = lastTarget!!
        return RecordingResult(t.absolutePath, durationMs = 1234L, sampleRate = 48_000, channels = 1)
    }

    override fun cancel() {
        cancelled = true
        lastTarget?.delete()
    }
}

private class FakeLocation(private val out: Fix?) : LocationProvider {
    override suspend fun getCurrent(timeoutMs: Long): Fix? = out
}

/**
 * Test double for [LiveInferenceEngine]. Subclasses the production class (it
 * is `open` so the live wiring can be tested without an interface) and
 * overrides the externally-observable surface. `feed` is a no-op — tests
 * push predictions directly via [emit] to control timing.
 */
private class FakeLiveEngine : LiveInferenceEngine(
    model = StubBioModel,
    yamNetGate = null,
    spectralSubtractor = null,
    sampleRateHz = 48_000,
) {
    private val emitter = MutableSharedFlow<WindowPrediction>(extraBufferCapacity = 64)
    private val _backlog = MutableStateFlow(0)
    override val predictions: SharedFlow<WindowPrediction> = emitter.asSharedFlow()
    override val backlog: StateFlow<Int> = _backlog.asStateFlow()
    var startCalled = false
    var stopCalled = false

    override fun start(scope: CoroutineScope) { startCalled = true }
    override fun feed(block: FloatArray) = Unit
    override suspend fun stop() { stopCalled = true }

    fun emit(p: WindowPrediction) { emitter.tryEmit(p) }
}

private object StubBioModel : BioacousticModel {
    override val modelId: String = "fake"
    override val modelVersion: String = "0"
    override val expectedSampleRateHz: Int = 48_000
    override val windowMs: Long = 3_000L
    override suspend fun load(modelFile: File, labelsFile: File) = Unit
    @Suppress("LongParameterList")
    override suspend fun predict(
        pcmFloat32: FloatArray,
        sampleRateHz: Int,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<WindowPrediction> = emptyList()
    override fun close() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var files: WavFileStore
    private lateinit var drafts: DraftRepository
    private lateinit var draftDao: FakeDraftDao
    private lateinit var detectionDao: FakeDetectionDao

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        files = WavFileStore(tmp.root)
        draftDao = FakeDraftDao()
        detectionDao = FakeDetectionDao()
        drafts = DraftRepository(draftDao, detectionDao, files, nowMs = { 0L })
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun grantedPerms() = FakePerms(
        mapOf(
            Permission.RECORD_AUDIO to PermissionStatus.GRANTED,
            Permission.ACCESS_FINE_LOCATION to PermissionStatus.GRANTED,
        ),
    )

    private fun build(
        recorder: Recorder = FakeRecorder(),
        engineFactory: LiveInferenceEngineFactory? = null,
    ): RecordingViewModel = RecordingViewModel(
        perms = grantedPerms(),
        recorder = recorder,
        location = FakeLocation(Fix(34.7, 33.04, 5f, 1L)),
        files = files,
        drafts = drafts,
        engineFactory = engineFactory,
        nowMs = { 0L },
        ioDispatcher = dispatcher,
    )

    @Test
    fun `start without RECORD_AUDIO surfaces Error`() = runTest {
        val vm = RecordingViewModel(
            perms = FakePerms(mapOf(Permission.RECORD_AUDIO to PermissionStatus.DENIED)),
            recorder = FakeRecorder(),
            location = FakeLocation(null),
            files = files,
            drafts = drafts,
            ioDispatcher = dispatcher,
        )
        vm.start()
        val s = vm.state.value as RecordingUiState.Error
        assertThat(s.message).contains("Microphone")
    }

    @Test
    fun `happy path produces Done with draft ID and persists draft`() = runTest {
        val rec = FakeRecorder()
        val vm = build(recorder = rec)
        vm.start()
        vm.stop()
        runCurrent()
        val done = vm.state.value as RecordingUiState.Done
        assertThat(done.draftId).isNotEmpty()
        assertThat(rec.startCalled).isTrue()
        assertThat(rec.stopCalled).isTrue()
        assertThat(draftDao.inserted).hasSize(1)
        val saved = draftDao.inserted.first()
        assertThat(saved.id).isEqualTo(done.draftId)
        assertThat(saved.latitude).isEqualTo(34.7)
    }

    @Test
    fun `cancel returns to Idle and deletes file`() = runTest {
        val rec = FakeRecorder()
        val vm = build(recorder = rec)
        vm.start()
        vm.cancel()
        runCurrent()
        assertThat(vm.state.value).isEqualTo(RecordingUiState.Idle)
        assertThat(rec.cancelled).isTrue()
    }

    @Test
    fun `live cards accumulate from engine predictions`() = runTest(dispatcher) {
        val engine = FakeLiveEngine()
        val vm = build(engineFactory = LiveInferenceEngineFactory { engine })
        vm.start()
        runCurrent()
        engine.emit(
            WindowPrediction(
                startMs = 0L,
                endMs = 3000L,
                taxonScientificName = "Turdus merula",
                taxonCommonName = "Blackbird",
                confidence = 0.8f,
                source = "birdnet_v2_4",
            ),
        )
        runCurrent()
        val st = vm.state.value as RecordingUiState.Recording
        assertThat(st.liveCards).hasSize(1)
        assertThat(st.liveCards[0].scientificName).isEqualTo("Turdus merula")
        assertThat(st.liveCards[0].count).isEqualTo(1)
        // Release viewModelScope collectors so runTest doesn't hang on leaked jobs.
        vm.cancel()
        runCurrent()
    }

    @Test
    fun `stop creates draft in PENDING_REVIEW with live detections`() = runTest(dispatcher) {
        val engine = FakeLiveEngine()
        val vm = build(engineFactory = LiveInferenceEngineFactory { engine })
        vm.start()
        runCurrent()
        engine.emit(
            WindowPrediction(
                startMs = 0L,
                endMs = 3000L,
                taxonScientificName = "Turdus merula",
                taxonCommonName = null,
                confidence = 0.8f,
                source = "birdnet_v2_4",
            ),
        )
        runCurrent()
        vm.stop()
        runCurrent()
        assertThat(draftDao.inserted).hasSize(1)
        val saved = draftDao.inserted.first()
        assertThat(saved.status).isEqualTo(DraftStatus.PENDING_REVIEW)
        assertThat(saved.modelId).isEqualTo("birdnet_v2_4")
        assertThat(detectionDao.inserted).hasSize(1)
        assertThat(detectionDao.inserted[0].taxonScientificName).isEqualTo("Turdus merula")
        assertThat(engine.stopCalled).isTrue()
    }

    @Test
    fun `stop without engine factory falls back to PENDING_INFERENCE`() = runTest(dispatcher) {
        val vm = build(engineFactory = null)
        vm.start()
        runCurrent()
        vm.stop()
        runCurrent()
        assertThat(draftDao.inserted).hasSize(1)
        val saved = draftDao.inserted.first()
        assertThat(saved.status).isEqualTo(DraftStatus.PENDING_INFERENCE)
        assertThat(saved.modelId).isNull()
    }

    @Test
    fun `stop with engine but no detections falls back to PENDING_INFERENCE`() =
        runTest(dispatcher) {
            val engine = FakeLiveEngine()
            val vm = build(engineFactory = LiveInferenceEngineFactory { engine })
            vm.start()
            runCurrent()
            // No predictions emitted — final detections list is empty.
            vm.stop()
            runCurrent()
            val saved = draftDao.inserted.first()
            assertThat(saved.status).isEqualTo(DraftStatus.PENDING_INFERENCE)
            assertThat(detectionDao.inserted).isEmpty()
        }
}

// In-memory DAOs for the test (no Room here — it's a VM unit test, not storage test).
private class FakeDraftDao : DraftDao {
    val inserted = mutableListOf<DraftEntity>()

    override fun insert(d: DraftEntity) { inserted += d }
    override fun update(d: DraftEntity) {
        val i = inserted.indexOfFirst { it.id == d.id }
        if (i >= 0) inserted[i] = d
    }
    override fun delete(d: DraftEntity) = Unit
    override fun getById(id: String): DraftEntity? = inserted.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<DraftEntity>> = flowOf(inserted.toList())
    override fun deleteById(id: String): Int = if (inserted.removeAll { it.id == id }) 1 else 0
}

private class FakeDetectionDao : DetectionDao {
    val inserted = mutableListOf<DetectionEntity>()
    override fun insertAll(items: List<DetectionEntity>) { inserted += items }
    override fun observeForDraft(draftId: String): Flow<List<DetectionEntity>> =
        flowOf(inserted.filter { it.draftId == draftId })
    override fun listForDraft(draftId: String): List<DetectionEntity> =
        inserted.filter { it.draftId == draftId }
    override fun setSelected(id: Long, selected: Boolean): Int = 0
    override fun deleteForDraft(draftId: String): Int {
        val before = inserted.size
        inserted.removeAll { it.draftId == draftId }
        return before - inserted.size
    }
}
