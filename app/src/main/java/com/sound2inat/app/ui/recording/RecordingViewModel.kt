package com.sound2inat.app.ui.recording

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.permissions.Permission
import com.sound2inat.app.permissions.PermissionStatus
import com.sound2inat.app.permissions.PermissionsController
import com.sound2inat.app.recording.RecordingController
import com.sound2inat.app.recording.RecordingServiceLauncher
import com.sound2inat.app.recording.RecordingSessionState
import com.sound2inat.storage.DraftPhotoDao
import com.sound2inat.storage.DraftPhotoEntity
import com.sound2inat.storage.PhotoFileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
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
    private val photoDao: DraftPhotoDao? = null,
    private val photoStore: PhotoFileStore? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val hasCamera: Boolean = photoStore != null

    private val permissionError = MutableStateFlow<String?>(null)
    private val _photoCount = MutableStateFlow(0)

    // Photos buffered during recording; flushed to DB once Draft row exists (on Done).
    private val pendingPhotos = mutableListOf<DraftPhotoEntity>()

    // True once this VM instance has observed at least one Recording state, meaning
    // the controller is running a session we started. Without this guard, a stale
    // Done(prevId) left in the singleton controller would fire onDone immediately
    // on the next navigation to RecordingScreen, sending the user to the wrong draft.
    private var hasSeenRecording = false

    val state: StateFlow<RecordingUiState> = combine(
        controller.state,
        permissionError,
        _photoCount,
    ) { session, error, photoCount ->
        if (session is RecordingSessionState.Recording) hasSeenRecording = true
        val uiState = error?.let { RecordingUiState.Error(it) } ?: session.toUiState(photoCount)
        if (!hasSeenRecording && uiState is RecordingUiState.Done) RecordingUiState.Idle else uiState
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordingUiState.Idle)

    init {
        viewModelScope.launch {
            controller.state.collect { s ->
                when (s) {
                    is RecordingSessionState.Done -> flushPendingPhotos()
                    is RecordingSessionState.Idle -> discardPendingPhotos()
                    else -> Unit
                }
            }
        }
    }

    val rmsHistory: StateFlow<FloatArray> = controller.rmsHistory
    val audioBlocks: SharedFlow<FloatArray> = controller.audioBlocks
    val sampleRateHz: Int get() = controller.sampleRateHz

    fun start() {
        hasSeenRecording = false  // reset so the Done guard works for this session
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

    data class PreparedCapture(val uri: Uri, val filePath: String)

    fun preparePhotoCapture(draftId: String, photoId: String): PreparedCapture {
        checkNotNull(photoStore) { "Camera is not available — photoStore is null" }
        val file = photoStore.newPhotoFile(draftId, photoId)
        val uri = FileProvider.getUriForFile(
            appContext,
            "com.sound2inat.app.fileprovider",
            file,
        )
        return PreparedCapture(uri = uri, filePath = file.absolutePath)
    }

    fun onPhotoTaken(draftId: String, photoId: String, photoPath: String) {
        pendingPhotos.add(
            DraftPhotoEntity(
                id = photoId,
                draftId = draftId,
                photoPath = photoPath,
                takenAtMs = System.currentTimeMillis(),
            ),
        )
        _photoCount.update { it + 1 }
    }

    fun onPhotoCancelled(draftId: String, photoId: String) {
        photoStore?.newPhotoFile(draftId, photoId)?.delete()
    }

    private suspend fun flushPendingPhotos() {
        if (pendingPhotos.isEmpty()) return
        val toInsert = pendingPhotos.toList()
        pendingPhotos.clear()
        withContext(ioDispatcher) { toInsert.forEach { photoDao?.insert(it) } }
    }

    private fun discardPendingPhotos() {
        val toDiscard = pendingPhotos.toList()
        pendingPhotos.clear()
        toDiscard.forEach { photoStore?.newPhotoFile(it.draftId, it.id)?.delete() }
    }

    private fun RecordingSessionState.toUiState(photoCount: Int = 0): RecordingUiState = when (this) {
        RecordingSessionState.Idle -> RecordingUiState.Idle
        is RecordingSessionState.Recording -> RecordingUiState.Recording(
            draftId = draftId,
            elapsedMs = elapsedMs,
            rms = rms,
            gps = gps,
            warningSoftLimit = warningSoftLimit,
            liveCards = liveCards,
            backlogWindows = backlogWindows,
            habitatPhotoCount = photoCount,
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
    private val photoDao: DraftPhotoDao,
    private val photoStore: PhotoFileStore,
) : ViewModel() {
    val factory = { perms: PermissionsController ->
        RecordingViewModel(
            perms = perms,
            controller = controller,
            launcher = launcher,
            appContext = appContext,
            photoDao = photoDao,
            photoStore = photoStore,
            ioDispatcher = Dispatchers.IO,
        )
    }
}
