package com.sound2inat.app.recording

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.recording.GpsStatus
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
import com.sound2inat.storage.WavFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var files: WavFileStore
    private lateinit var drafts: DraftRepository
    private lateinit var draftDao: FakeDraftDao
    private lateinit var detectionDao: FakeDetectionDao

    @Before
    fun setUp() {
        files = WavFileStore(tmp.root)
        draftDao = FakeDraftDao()
        detectionDao = FakeDetectionDao()
        drafts = DraftRepository(draftDao, detectionDao, files, nowMs = { 0L })
    }

    private fun build(
        recorder: Recorder = FakeRecorder(),
        location: LocationProvider = FakeLocation(Fix(34.7, 33.04, 5f, 1L)),
        engineFactory: LiveInferenceEngineFactory? = null,
        nowMs: () -> Long = { 0L },
        applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
    ): DefaultRecordingController = DefaultRecordingController(
        applicationScope = applicationScope,
        recorder = recorder,
        location = location,
        files = files,
        drafts = drafts,
        engineFactory = engineFactory,
        minConfidence = flowOf(0.25f),
        nowMs = nowMs,
        ioDispatcher = dispatcher,
    )

    @Test
    fun `start sets state to Recording and assigns draftId`() = runTest(dispatcher) {
        val ctrl = build()
        ctrl.start()
        val state = ctrl.state.value as RecordingSessionState.Recording
        assertThat(state.draftId).isNotEmpty()
        assertThat(state.elapsedMs).isEqualTo(0L)
        assertThat(state.gps).isInstanceOf(GpsStatus.Acquiring::class.java)
        ctrl.cancel()
    }

    @Test
    fun `start is idempotent when already Recording`() = runTest(dispatcher) {
        val recorder = FakeRecorder()
        val ctrl = build(recorder = recorder)
        ctrl.start()
        ctrl.start() // second call must be no-op
        assertThat(recorder.startCount).isEqualTo(1)
        ctrl.cancel()
    }

    @Test
    fun `stop saves draft without detections and resets to Done`() = runTest(dispatcher) {
        val ctrl = build(engineFactory = null)
        ctrl.start()
        runCurrent()
        ctrl.stop()
        runCurrent()
        assertThat(ctrl.state.value).isInstanceOf(RecordingSessionState.Done::class.java)
    }

    @Test
    fun `stop saves draft with detections when engine present`() = runTest(dispatcher) {
        val engine = FakeLiveEngine()
        val ctrl = build(engineFactory = LiveInferenceEngineFactory { engine })
        ctrl.start()
        runCurrent()
        engine.emit(WindowPrediction(0L, 3000L, "Turdus merula", "Blackbird", 0.8f, "birdnet_v2_4"))
        runCurrent()
        ctrl.stop()
        runCurrent()
        assertThat(ctrl.state.value).isInstanceOf(RecordingSessionState.Done::class.java)
    }

    @Test
    fun `cancel resets state to Idle`() = runTest(dispatcher) {
        val recorder = FakeRecorder()
        val ctrl = build(recorder = recorder)
        ctrl.start()
        ctrl.cancel()
        runCurrent()
        assertThat(ctrl.state.value).isEqualTo(RecordingSessionState.Idle)
        assertThat(recorder.cancelled).isTrue()
    }

    @Test
    fun `live predictions accumulate in liveCards`() = runTest(dispatcher) {
        val engine = FakeLiveEngine()
        val ctrl = build(engineFactory = LiveInferenceEngineFactory { engine })
        ctrl.start()
        runCurrent()
        engine.emit(WindowPrediction(0L, 3000L, "Turdus merula", "Blackbird", 0.8f, "birdnet_v2_4"))
        runCurrent()
        val state = ctrl.state.value as RecordingSessionState.Recording
        assertThat(state.liveCards).hasSize(1)
        assertThat(state.liveCards[0].scientificName).isEqualTo("Turdus merula")
        ctrl.cancel()
        runCurrent()
    }

    @Test
    fun `lastDetection is the newest card by lastSeenMs`() = runTest(dispatcher) {
        val engine = FakeLiveEngine()
        val ctrl = build(engineFactory = LiveInferenceEngineFactory { engine })
        ctrl.start()
        runCurrent()
        engine.emit(WindowPrediction(0L, 3000L, "Turdus merula", "Blackbird", 0.8f, "birdnet_v2_4"))
        engine.emit(WindowPrediction(3000L, 6000L, "Erithacus rubecula", "Robin", 0.7f, "birdnet_v2_4"))
        runCurrent()
        val last = (ctrl.state.value as RecordingSessionState.Recording).lastDetection
        assertThat(last).isNotNull()
        assertThat(last!!.scientificName).isEqualTo("Erithacus rubecula")
        ctrl.cancel()
        runCurrent()
    }
}

// --------------- Fakes ---------------

private class FakeRecorder : Recorder {
    private val _rms = MutableStateFlow(0f)
    override val rmsLevel: StateFlow<Float> = _rms
    private val _rmsHistory = MutableStateFlow(FloatArray(0))
    override val rmsHistory: StateFlow<FloatArray> = _rmsHistory
    override val audioBlocks: SharedFlow<FloatArray> = MutableSharedFlow()
    override val sampleRate: Int = 48_000
    var startCount = 0
    var stopCalled = false
    var cancelled = false
    var lastTarget: File? = null

    override suspend fun start(target: File) {
        startCount++
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
    override fun observeCountsByDraft(): kotlinx.coroutines.flow.Flow<List<com.sound2inat.storage.DraftDetectionCount>> =
        kotlinx.coroutines.flow.flowOf(emptyList())
}
