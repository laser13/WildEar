package com.sound2inat.app.recording

import com.sound2inat.app.ui.recording.GpsStatus
import com.sound2inat.app.ui.recording.LiveCard
import com.sound2inat.app.ui.review.SceneTagsPersister
import com.sound2inat.inat.RegionFilter
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.DetectionAggregator
import com.sound2inat.inference.LiveInferenceEngine
import com.sound2inat.inference.LiveInferenceEngineFactory
import com.sound2inat.inference.LiveSceneTagsAnalyzer
import com.sound2inat.inference.ModelIds
import com.sound2inat.inference.PostRecordingProcessor
import com.sound2inat.inference.RegionalStatus
import com.sound2inat.location.Fix
import com.sound2inat.location.LocationProvider
import com.sound2inat.recorder.Recorder
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.WavFileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    /**
     * Long-lived application-scoped scope. Owned by Hilt at the SingletonComponent
     * level; outlives [com.sound2inat.app.recording.RecordingService] so a service
     * teardown does not orphan or kill in-flight engine cleanup.
     */
    private val applicationScope: CoroutineScope,
    private val recorder: Recorder,
    private val location: LocationProvider,
    private val files: WavFileStore,
    private val drafts: DraftRepository,
    private val engineFactory: LiveInferenceEngineFactory? = null,
    private val minConfidence: Flow<Float> = flowOf(DEFAULT_MIN_CONFIDENCE),
    private val regionFilter: RegionFilter? = null,
    private val regionFilterEnabled: Flow<Boolean> = flowOf(false),
    private val regionRadiusKm: Flow<Int> = flowOf(DEFAULT_REGION_RADIUS_KM),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val locationTimeoutMs: Long = LOCATION_TIMEOUT_MS,
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val softLimitMs: Long = SOFT_LIMIT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val processor: PostRecordingProcessor? = null,
    private val sceneTagsAnalyzer: LiveSceneTagsAnalyzer? = null,
) : RecordingController {

    private val scope: CoroutineScope = applicationScope
    private val startMutex = Mutex()

    /**
     * Tracks the in-flight tear-down launched by [cancel]. Subsequent [start]
     * calls join this job under [startMutex] before constructing a new engine,
     * which closes the cancel→start race that otherwise lets two BirdNET
     * interpreters run in parallel for a brief window. Cleared once the job
     * completes (see [cancel]).
     */
    @Volatile private var pendingStopJob: Job? = null

    private val _state = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
    override val state: StateFlow<RecordingSessionState> = _state.asStateFlow()

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

    /**
     * Per-session cache: scientific name → resolved RegionalStatus. Filled by
     * [annotateNewSpecies] as it queries iNaturalist; consulted on every live
     * cards update so already-resolved species don't lose their status when
     * new windows arrive. Cleared on stop/cancel.
     */
    private val regionalStatusCache = ConcurrentHashMap<String, RegionalStatus>()
    private val annotationInFlight = ConcurrentHashMap.newKeySet<String>()

    // Species added here are hidden from liveCards until their regional annotation
    // resolves. Populated when a new species appears and filter+GPS are active,
    // cleared by annotateNewSpecies (or when filter is disabled/GPS unavailable).
    private val pendingDisplay = ConcurrentHashMap.newKeySet<String>()

    override suspend fun start() {
        startMutex.withLock {
            if (_state.value is RecordingSessionState.Recording) return@withLock
            // If a previous cancel() is still tearing down its engine, wait for
            // it to fully complete before standing up a new one. Otherwise two
            // BirdNET interpreters can run in parallel for the duration of the
            // overlap.
            pendingStopJob?.join()
            pendingStopJob = null
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
                // When filter+GPS are active, hide new (unannotated) species until
                // their regional status is resolved. This prevents the brief
                // "Possible matches → Unlikely" flicker on first detection.
                if (regionFilter != null && fix != null) {
                    snap.map { it.taxonScientificName }.distinct()
                        .filter { !regionalStatusCache.containsKey(it) }
                        .forEach { pendingDisplay.add(it) }
                }
                val cards = snap.mapNotNull { det ->
                    if (det.taxonScientificName in pendingDisplay) {
                        null
                    } else {
                        det.toLiveCard(regionalStatusCache[det.taxonScientificName])
                    }
                }
                val last = cards.maxByOrNull { it.lastSeenMs }
                updateRecording { copy(liveCards = cards, lastDetection = last) }
                annotateNewSpecies(snap)
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
        processor?.process(File(result.audioPath))
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
        // Fire-and-forget YamNet analysis so the review screen's Auto button
        // gets data even for live-recorded drafts (the live path bypasses
        // InferenceQueue and would otherwise leave sceneTagsJson NULL).
        sceneTagsAnalyzer?.let { analyzer ->
            applicationScope.launch {
                val tags = analyzer.analyze(result.audioPath) ?: return@launch
                SceneTagsPersister.persistAndApplyAuto(drafts, id, tags)
            }
        }
        activeEngine = null
        activeAggregator = null
        regionalStatusCache.clear()
        annotationInFlight.clear()
        pendingDisplay.clear()
        _state.value = RecordingSessionState.Done(id)
    }

    override fun cancel() {
        recorder.cancel()
        cancelJobs()
        // Snapshot + clear references synchronously so a follow-up start() that
        // races with the launched tear-down can't observe the stale engine.
        val engine = activeEngine
        activeEngine = null
        activeAggregator = null
        regionalStatusCache.clear()
        annotationInFlight.clear()
        pendingDisplay.clear()
        // Flip to Idle synchronously so observers (UI, RecordingService) see
        // the session end immediately. The engine is torn down asynchronously
        // because stop() can be slow (BirdNET interpreter close, queue drain).
        // start() joins [pendingStopJob] under [startMutex] before creating a
        // new engine, which is what closes the cancel→start race.
        _state.value = RecordingSessionState.Idle
        if (engine == null) return
        val job = scope.launch { engine.stop() }
        pendingStopJob = job
        job.invokeOnCompletion {
            // Compare-and-clear so we don't stomp on a newer pendingStopJob set
            // by a subsequent cancel().
            if (pendingStopJob === job) pendingStopJob = null
        }
    }

    private fun cancelJobs() {
        tickJob?.cancel()
        tickJob = null
        rmsJob?.cancel()
        rmsJob = null
        locationJob?.cancel()
        locationJob = null
        feedJob?.cancel()
        feedJob = null
        predictionsJob?.cancel()
        predictionsJob = null
        backlogJob?.cancel()
        backlogJob = null
    }

    private fun updateRecording(block: RecordingSessionState.Recording.() -> RecordingSessionState.Recording) {
        _state.update { s -> (s as? RecordingSessionState.Recording)?.block() ?: s }
    }

    private fun AggregatedDetection.toLiveCard(status: RegionalStatus?) = LiveCard(
        scientificName = taxonScientificName,
        commonName = taxonCommonName,
        count = detectedWindows,
        peakConfidence = maxConfidence,
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
        regionalStatus = status,
    )

    /**
     * Kicks off iNaturalist regional checks for any species in [snap] that
     * aren't already cached or in flight. Each species is queried
     * independently — there are typically only a handful per session, so
     * batching/debouncing isn't worth the complexity. RegionFilter has its
     * own multi-level cache so repeated lookups (across sessions, same
     * place) don't hit the network.
     *
     * No-ops when GPS isn't ready or the filter dependency is missing.
     * When the filter setting is disabled, any species hidden in
     * [pendingDisplay] are immediately revealed with null status.
     */
    private fun annotateNewSpecies(snap: List<AggregatedDetection>) {
        val filter = regionFilter ?: return
        val fix = this.fix ?: return
        scope.launch {
            val enabled = runCatching { regionFilterEnabled.first() }.getOrDefault(false)
            if (!enabled) {
                // Filter disabled: un-hide species that were waiting for annotation.
                val unhidden = snap.map { it.taxonScientificName }.distinct()
                    .filter { pendingDisplay.remove(it) }
                if (unhidden.isNotEmpty()) refreshLiveCards()
                return@launch
            }
            val radius = runCatching { regionRadiusKm.first() }.getOrDefault(DEFAULT_REGION_RADIUS_KM)
            val pending = snap
                .map { it.taxonScientificName }
                .distinct()
                .filter { !regionalStatusCache.containsKey(it) && annotationInFlight.add(it) }
            if (pending.isEmpty()) return@launch
            try {
                val toAnnotate = snap.filter { it.taxonScientificName in pending }
                val annotated = filter.annotate(toAnnotate, fix.latitude, fix.longitude, radius)
                annotated.forEach { det ->
                    det.regionalStatus?.let { regionalStatusCache[det.taxonScientificName] = it }
                    pendingDisplay.remove(det.taxonScientificName)
                }
                refreshLiveCards()
            } finally {
                pending.forEach { annotationInFlight.remove(it) }
                // Safety: if annotation threw before updating pendingDisplay, un-hide
                // affected species so they're never permanently invisible.
                val stillHidden = pending.filter { pendingDisplay.remove(it) }
                if (stillHidden.isNotEmpty()) refreshLiveCards()
            }
        }
    }

    /**
     * Rebuilds [liveCards] from the current aggregator snapshot, respecting
     * [pendingDisplay] (hidden) and [regionalStatusCache] (known statuses).
     * Called after annotation resolves to reveal newly-annotated species in
     * the correct section without waiting for the next audio window.
     */
    private fun refreshLiveCards() {
        val snap = activeAggregator?.snapshot() ?: return
        val cards = snap.mapNotNull { det ->
            if (det.taxonScientificName in pendingDisplay) {
                null
            } else {
                det.toLiveCard(regionalStatusCache[det.taxonScientificName])
            }
        }
        updateRecording { copy(liveCards = cards, lastDetection = cards.maxByOrNull { it.lastSeenMs }) }
    }

    companion object {
        const val SOFT_LIMIT_MS = 5L * 60_000L
        const val TICK_INTERVAL_MS = 100L
        const val LOCATION_TIMEOUT_MS = 15_000L
        const val DEFAULT_MIN_CONFIDENCE = 0.25f
        const val DEFAULT_REGION_RADIUS_KM = 200
        const val LIVE_MODEL_ID = ModelIds.BIRDNET
        const val LIVE_MODEL_VERSION = "2.4"
    }
}
