package com.sound2inat.app.ui.photos

import androidx.lifecycle.ViewModel
import com.sound2inat.app.permissions.Permission
import com.sound2inat.app.permissions.PermissionStatus
import com.sound2inat.app.permissions.PermissionsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PhotoCaptureGateUiState(
    val canBindCamera: Boolean = false,
    val showCameraPermissionDenied: Boolean = false,
)

class PhotoCaptureViewModel : ViewModel() {
    private val _state = MutableStateFlow(PhotoCaptureGateUiState())
    val state: StateFlow<PhotoCaptureGateUiState> = _state.asStateFlow()

    suspend fun initWithPermissions(permissions: PermissionsController) {
        if (permissions.statuses.value[Permission.CAMERA] == PermissionStatus.GRANTED) {
            _state.value = PhotoCaptureGateUiState(canBindCamera = true)
            return
        }

        val result = permissions.request(setOf(Permission.CAMERA))
        _state.value = if (result[Permission.CAMERA] == PermissionStatus.GRANTED) {
            PhotoCaptureGateUiState(canBindCamera = true)
        } else {
            PhotoCaptureGateUiState(showCameraPermissionDenied = true)
        }
    }

    fun openAppSettings(permissions: PermissionsController) {
        permissions.openAppSettings()
    }
}
