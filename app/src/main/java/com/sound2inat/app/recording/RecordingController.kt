package com.sound2inat.app.recording

import com.sound2inat.app.ui.recording.GpsStatus
import com.sound2inat.app.ui.recording.LiveCard
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.DetectionAggregator
import com.sound2inat.inference.LiveInferenceEngine
import com.sound2inat.inference.LiveInferenceEngineFactory
import com.sound2inat.location.Fix
import com.sound2inat.location.LocationProvider
import com.sound2inat.recorder.Recorder
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.WavFileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

// --------------- State ---------------

sealed interface RecordingSessionState {
    data object Idle : RecordingSessionState
    data class Recording(
        val draftId: String,
        val recordingStartMs: Long,
        val elapsedMs: Long,
        val rms: Float,
        val gps: GpsStatus,
        val warningSoftLimit: Boolean,
        val backlogWindows: Int,
        val liveCards: List<LiveCard>,
        val lastDetection: LiveCard?,
    ) : RecordingSessionState
    data class Done(val draftId: String) : RecordingSessionState
    data class Error(val message: String) : RecordingSessionState
}

// --------------- Interface ---------------

interface RecordingController {
    val state: StateFlow<RecordingSessionState>
    val rmsHistory: StateFlow<FloatArray>
    val audioBlocks: SharedFlow<FloatArray>
    val sampleRateHz: Int
    suspend fun start()
    suspend fun stop()
    fun cancel()
}

// --------------- Implementation ---------------

@Suppress("LongParameterList", "TooManyFunctions")
class DefaultRecordingController(
    private val recorder: Recorder,
    private val location: LocationProvider,
    private val files: WavFileStore,
    private val drafts: DraftRepository,
    private val engineFactory: LiveInferenceEngineFactory? = null,
    private val minConfidence: Flow<Float> = flowOf(DEFAULT_MIN_CONFIDENCE),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val locationTimeoutMs: Long = LOCATION_TIMEOUT_MS,
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val softLimitMs: Long = SOFT_LIMIT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RecordingController {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val startMutex = Mutex()

    private val _state = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
    override val state: StateFlow<RecordingSessionState> = _state

    override val rmsHistory: StateFlow<FloatArray> = recorder.rmsHistory
    override val audioBlocks: SharedFlow<FloatArray> = recorder.audioBlocks
    override val sampleRateHz: Int get() = recorder.sampleRate

    private var draftId: String? = null
    private var recordingStartMs: Long = 0L
    @Volatile private var fix: Fix? = null
    private var tickJob: Job? = null
    private var rmsJob: Job? = null
    private var locationJob: Job? = null
    private var feedJob: Job? = null
    private var predictionsJob: Job? = null
    private var backlogJob: Job? = null

    private var activeEngine: LiveInferenceEngine? = null
    private var activeAggregator: DetectionAggregator? = null

    override suspend fun start() {
        startMutex.withLock {
            if (_state.value is RecordingSessionState.Recording) return@withLock
            val id = UUID.randomUUID().toString().also { draftId = it }
            val target = files.newRecordingFile(id)
            recordingStartMs = nowMs()
            recorder.start(target)
            _state.value = RecordingSessionState.Recording(
                draftId = id,
                recordingStartMs = recordingStartMs,
                elapsedMs = 0L,
                rms = 0f,
                gps = GpsStatus.Acquiring,
                warningSoftLimit = false,
                backlogWindows = 0,
                liveCards = emptyList(),
                lastDetection = null,
            )

            locationJob = scope.launch { fix = location.getCurrent(locationTimeoutMs) }
            tickJob = scope.launch { tickLoop() }
            rmsJob = scope.launch {
                recorder.rmsLevel.collect { rms ->
                    updateRecording { copy(rms = rms) }
                }
            }
            startLiveInference()
        }
    }

    private suspend fun startLiveInference() {
        val factory = engineFactory ?: return
        val threshold = runCatching { minConfidence.first() }.getOrDefault(DEFAULT_MIN_CONFIDENCE)
        val aggregator = DetectionAggregator(minConfidence = threshold)
        val engine = factory.create(recorder.sampleRate) ?: return
        activeEngine = engine
        activeAggregator = aggregator
        engine.start(scope)
        feedJob = scope.launch {
            recorder.audioBlocks.collect { engine.feed(it) }
        }
        predictionsJob = scope.launch {
            engine.predictions.collect { pred ->
                val snap = aggregator.addWindow(pred)
                val cards = snap.map { it.toLiveCard() }
                val last = cards.maxByOrNull { it.lastSeenMs }
                updateRecording { copy(liveCards = cards, lastDetection = last) }
            }
        }
        backlogJob = scope.launch {
            engine.backlog.collect { depth ->
                updateRecording { copy(backlogWindows = depth) }
            }
        }
    }

    private suspend fun tickLoop() {
        while (true) {
            delay(tickIntervalMs)
            val cur = _state.value as? RecordingSessionState.Recording ?: return
            val elapsed = nowMs() - recordingStartMs
            val gps = fix?.let { GpsStatus.Fix(it.latitude, it.longitude, it.accuracyMeters) }
                ?: if (elapsed >= locationTimeoutMs) GpsStatus.NoFix else GpsStatus.Acquiring
            val soft = elapsed >= softLimitMs
            _state.value = cur.copy(elapsedMs = elapsed, gps = gps, warningSoftLimit = soft)
        }
    }

    override suspend fun stop() {
        val id = draftId ?: return
        val engine = activeEngine
        val finalDetections: List<AggregatedDetection> = if (engine != null) {
            engine.stop()
            activeAggregator?.snapshot().orEmpty()
        } else {
            emptyList()
        }
        val result = recorder.stop()
        cancelJobs()
        withContext(ioDispatcher) {
            if (engine != null && finalDetections.isNotEmpty()) {
                drafts.createWithDetections(
                    id = id,
                    audioPath = result.audioPath,
                    recordedAtUtcMs = recordingStartMs,
                    durationMs = result.durationMs,
                    latitude = fix?.latitude,
                    longitude = fix?.longitude,
                    accuracyMeters = fix?.accuracyMeters,
                    modelId = LIVE_MODEL_ID,
                    modelVersion = LIVE_MODEL_VERSION,
                    detections = finalDetections,
                )
            } else {
                drafts.create(
                    id = id,
                    audioPath = result.audioPath,
                    recordedAtUtcMs = recordingStartMs,
                    durationMs = result.durationMs,
                    latitude = fix?.latitude,
                    longitude = fix?.longitude,
                    accuracyMeters = fix?.accuracyMeters,
                )
            }
        }
        activeEngine = null
        activeAggregator = null
        _state.value = RecordingSessionState.Done(id)
    }

    override fun cancel() {
        recorder.cancel()
        cancelJobs()
        val engine = activeEngine
        if (engine != null) {
            scope.launch { engine.stop() }
            activeEngine = null
            activeAggregator = null
        }
        _state.value = RecordingSessionState.Idle
    }

    private fun cancelJobs() {
        tickJob?.cancel(); tickJob = null
        rmsJob?.cancel(); rmsJob = null
        locationJob?.cancel(); locationJob = null
        feedJob?.cancel(); feedJob = null
        predictionsJob?.cancel(); predictionsJob = null
        backlogJob?.cancel(); backlogJob = null
    }

    private fun updateRecording(block: RecordingSessionState.Recording.() -> RecordingSessionState.Recording) {
        _state.update { s -> (s as? RecordingSessionState.Recording)?.block() ?: s }
    }

    private fun AggregatedDetection.toLiveCard() = LiveCard(
        scientificName = taxonScientificName,
        commonName = taxonCommonName,
        count = detectedWindows,
        peakConfidence = maxConfidence,
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
    )

    companion object {
        const val SOFT_LIMIT_MS = 5L * 60_000L
        const val TICK_INTERVAL_MS = 100L
        const val LOCATION_TIMEOUT_MS = 15_000L
        const val DEFAULT_MIN_CONFIDENCE = 0.25f
        const val LIVE_MODEL_ID = "birdnet_v2_4"
        const val LIVE_MODEL_VERSION = "2.4"
    }
}
