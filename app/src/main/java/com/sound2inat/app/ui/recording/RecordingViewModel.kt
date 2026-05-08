package com.sound2inat.app.ui.recording

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.permissions.Permission
import com.sound2inat.app.permissions.PermissionStatus
import com.sound2inat.app.permissions.PermissionsController
import com.sound2inat.app.recording.RecordingController
import com.sound2inat.app.recording.RecordingServiceLauncher
import com.sound2inat.app.recording.RecordingSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

class RecordingViewModel(
    private val perms: PermissionsController,
    private val controller: RecordingController,
    private val launcher: RecordingServiceLauncher,
    private val appContext: Context,
) : ViewModel() {

    private val permissionError = MutableStateFlow<String?>(null)

    // True once this VM instance has observed at least one Recording state, meaning
    // the controller is running a session we started. Without this guard, a stale
    // Done(prevId) left in the singleton controller would fire onDone immediately
    // on the next navigation to RecordingScreen, sending the user to the wrong draft.
    private var hasSeenRecording = false

    val state: StateFlow<RecordingUiState> = combine(
        controller.state,
        permissionError,
    ) { session, error ->
        if (session is RecordingSessionState.Recording) hasSeenRecording = true
        val uiState = error?.let { RecordingUiState.Error(it) } ?: session.toUiState()
        if (!hasSeenRecording && uiState is RecordingUiState.Done) RecordingUiState.Idle else uiState
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordingUiState.Idle)

    val rmsHistory: StateFlow<FloatArray> = controller.rmsHistory
    val audioBlocks: SharedFlow<FloatArray> = controller.audioBlocks
    val sampleRateHz: Int get() = controller.sampleRateHz

    fun start() {
        viewModelScope.launch {
            val granted = perms.request(RECORDING_PERMISSIONS)
            if (granted[Permission.RECORD_AUDIO] != PermissionStatus.GRANTED) {
                permissionError.value = "Microphone permission required."
                return@launch
            }
            permissionError.value = null
            launcher.start(appContext)
        }
    }

    fun stop() {
        launcher.stop(appContext)
    }

    fun cancel() {
        launcher.cancel(appContext)
    }

    private fun RecordingSessionState.toUiState(): RecordingUiState = when (this) {
        RecordingSessionState.Idle -> RecordingUiState.Idle
        is RecordingSessionState.Recording -> RecordingUiState.Recording(
            elapsedMs = elapsedMs,
            rms = rms,
            gps = gps,
            warningSoftLimit = warningSoftLimit,
            liveCards = liveCards,
            backlogWindows = backlogWindows,
        )
        is RecordingSessionState.Done -> RecordingUiState.Done(draftId)
        is RecordingSessionState.Error -> RecordingUiState.Error(message)
    }

    private companion object {
        val RECORDING_PERMISSIONS = setOf(
            Permission.RECORD_AUDIO,
            Permission.ACCESS_FINE_LOCATION,
            Permission.POST_NOTIFICATIONS,
        )
    }
}

@HiltViewModel
class RecordingViewModelHilt @Inject constructor(
    private val controller: RecordingController,
    private val launcher: RecordingServiceLauncher,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    val factory = { perms: PermissionsController ->
        RecordingViewModel(
            perms = perms,
            controller = controller,
            launcher = launcher,
            appContext = appContext,
        )
    }
}
