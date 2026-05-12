package com.sound2inat.app.ui.photos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.sound2inat.app.permissions.Permission
import com.sound2inat.app.permissions.PermissionStatus
import com.sound2inat.app.permissions.PermissionsController
import com.sound2inat.location.LocationProvider
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoObservationFileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class PhotoCaptureGateUiState(
    val canBindCamera: Boolean = false,
    val showCameraPermissionDenied: Boolean = false,
    val draftId: String? = null,
    val photoCount: Int = 0,
    val doneEnabled: Boolean = false,
    val lastThumbnailPath: String? = null,
    val error: String? = null,
)

data class PreparedPhotoCapture(
    val photoId: String,
    val file: File,
)

@HiltViewModel
class PhotoCaptureViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repo: PhotoDraftRepository,
    private val fileStore: PhotoObservationFileStore,
    private val locationProvider: LocationProvider,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    @Inject constructor(
        savedStateHandle: SavedStateHandle,
        repo: PhotoDraftRepository,
        fileStore: PhotoObservationFileStore,
        locationProvider: LocationProvider,
    ) : this(
        savedStateHandle = savedStateHandle,
        repo = repo,
        fileStore = fileStore,
        locationProvider = locationProvider,
        nowMs = { System.currentTimeMillis() },
    )

    private val _state = MutableStateFlow(PhotoCaptureGateUiState())
    val state: StateFlow<PhotoCaptureGateUiState> = _state.asStateFlow()
    private var initialized = false

    suspend fun initWithPermissions(permissions: PermissionsController) {
        if (initialized) return
        initialized = true
        if (permissions.statuses.value[Permission.CAMERA] == PermissionStatus.GRANTED) {
            ensureDraft()
            return
        }

        val result = permissions.request(setOf(Permission.CAMERA))
        if (result[Permission.CAMERA] == PermissionStatus.GRANTED) {
            ensureDraft()
        } else {
            _state.value = PhotoCaptureGateUiState(showCameraPermissionDenied = true)
        }
    }

    fun openAppSettings(permissions: PermissionsController) {
        permissions.openAppSettings()
    }

    fun prepareOutputFile(): PreparedPhotoCapture {
        val draftId = checkNotNull(_state.value.draftId) { "photo draft is not ready yet" }
        val photoId = UUID.randomUUID().toString()
        return PreparedPhotoCapture(
            photoId = photoId,
            file = fileStore.newPhotoFile(draftId, photoId),
        )
    }

    suspend fun onPhotoSaved(
        photoId: String,
        file: File,
        width: Int?,
        height: Int?,
    ) {
        val draftId = checkNotNull(_state.value.draftId) { "photo draft is not ready yet" }
        repo.addImage(
            draftId = draftId,
            imageFile = file,
            takenAtUtcMs = nowMs(),
            width = width,
            height = height,
        )
        val newCount = _state.value.photoCount + 1
        _state.value = _state.value.copy(
            photoCount = newCount,
            doneEnabled = newCount > 0,
            lastThumbnailPath = file.absolutePath,
            error = null,
        )
    }

    fun onPhotoCaptureFailed(photoId: String, file: File, message: String) {
        file.delete()
        _state.value = _state.value.copy(error = message)
    }

    suspend fun discardIfEmpty() {
        val draftId = _state.value.draftId ?: return
        if (_state.value.photoCount == 0) {
            repo.deleteDraft(draftId)
        }
    }

    private suspend fun ensureDraft() {
        val existingDraftId = savedStateHandle.get<String>("draftId")
        if (!existingDraftId.isNullOrBlank()) {
            val existing = repo.observeWithImages(existingDraftId).first()
            val photoCount = existing?.images?.size ?: 0
            _state.value = PhotoCaptureGateUiState(
                canBindCamera = true,
                draftId = existingDraftId,
                photoCount = photoCount,
                doneEnabled = photoCount > 0,
                lastThumbnailPath = existing?.images?.lastOrNull()?.photoPath,
            )
            return
        }

        val fix = runCatching { locationProvider.getCurrent(timeoutMs = 3_000) }.getOrNull()
        val draftId = repo.createDraft(
            observedAtUtcMs = nowMs(),
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            accuracyMeters = fix?.accuracyMeters,
        )
        _state.value = PhotoCaptureGateUiState(canBindCamera = true, draftId = draftId)
    }
}
