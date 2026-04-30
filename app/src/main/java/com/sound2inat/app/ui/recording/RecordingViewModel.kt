package com.sound2inat.app.ui.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.permissions.Permission
import com.sound2inat.app.permissions.PermissionStatus
import com.sound2inat.app.permissions.PermissionsController
import com.sound2inat.location.Fix
import com.sound2inat.location.LocationProvider
import com.sound2inat.recorder.Recorder
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.WavFileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@Suppress("LongParameterList")
class RecordingViewModel(
    private val perms: PermissionsController,
    private val recorder: Recorder,
    private val location: LocationProvider,
    private val files: WavFileStore,
    private val drafts: DraftRepository,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val locationTimeoutMs: Long = LOCATION_TIMEOUT_MS,
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val softLimitMs: Long = SOFT_LIMIT_MS,
    private val hardLimitMs: Long = HARD_LIMIT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
    val state: StateFlow<RecordingUiState> = _state

    /** Forwarded so the live waveform composable can collect it directly. */
    val rmsHistory: StateFlow<FloatArray> = recorder.rmsHistory

    private var draftId: String? = null
    private var recordingStartMs: Long = 0L
    private var fix: Fix? = null
    private var tickJob: Job? = null
    private var rmsJob: Job? = null
    private var locationJob: Job? = null

    fun start() {
        viewModelScope.launch {
            val granted = perms.request(setOf(Permission.RECORD_AUDIO, Permission.ACCESS_FINE_LOCATION))
            if (granted[Permission.RECORD_AUDIO] != PermissionStatus.GRANTED) {
                _state.value = RecordingUiState.Error("Microphone permission required.")
                return@launch
            }
            val id = UUID.randomUUID().toString().also { draftId = it }
            val target = files.newRecordingFile(id)
            recordingStartMs = nowMs()
            recorder.start(target)
            _state.value = RecordingUiState.Recording(0L, 0f, GpsStatus.Acquiring, false)

            locationJob = viewModelScope.launch { fix = location.getCurrent(locationTimeoutMs) }
            tickJob = viewModelScope.launch { tickLoop() }
            rmsJob = viewModelScope.launch {
                recorder.rmsLevel.collect { rms ->
                    val cur = _state.value as? RecordingUiState.Recording ?: return@collect
                    _state.value = cur.copy(rms = rms)
                }
            }
        }
    }

    private suspend fun tickLoop() {
        while (true) {
            delay(tickIntervalMs)
            val cur = _state.value as? RecordingUiState.Recording ?: return
            val elapsed = nowMs() - recordingStartMs
            val gps = fix?.let { GpsStatus.Fix(it.latitude, it.longitude, it.accuracyMeters) }
                ?: if (elapsed >= locationTimeoutMs) GpsStatus.NoFix else GpsStatus.Acquiring
            val soft = elapsed >= softLimitMs
            if (elapsed >= hardLimitMs) {
                stopInternal()
                return
            }
            _state.value = cur.copy(elapsedMs = elapsed, gps = gps, warningSoftLimit = soft)
        }
    }

    fun stop() {
        viewModelScope.launch { stopInternal() }
    }

    private suspend fun stopInternal() {
        val id = draftId ?: return
        val result = recorder.stop()
        cancelJobs()
        withContext(ioDispatcher) {
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
        _state.value = RecordingUiState.Done(id)
    }

    fun cancel() {
        recorder.cancel()
        cancelJobs()
        _state.value = RecordingUiState.Idle
    }

    private fun cancelJobs() {
        tickJob?.cancel()
        tickJob = null
        rmsJob?.cancel()
        rmsJob = null
        locationJob?.cancel()
        locationJob = null
    }

    companion object {
        const val SOFT_LIMIT_MS = 5L * 60_000L
        const val HARD_LIMIT_MS = 10L * 60_000L
        const val TICK_INTERVAL_MS = 100L
        const val LOCATION_TIMEOUT_MS = 15_000L
    }
}

@HiltViewModel
class RecordingViewModelHilt @Inject constructor(
    private val recorder: Recorder,
    private val location: LocationProvider,
    private val files: WavFileStore,
    private val drafts: DraftRepository,
) : ViewModel() {
    // PermissionsController depends on the host ComponentActivity, so it's not Hilt-singleton.
    // The screen creates the delegate after collecting LocalPermissionsController.
    val factory = { perms: PermissionsController ->
        RecordingViewModel(perms, recorder, location, files, drafts)
    }
}
