package com.sound2inat.app.ui.recording

sealed interface GpsStatus {
    data object Acquiring : GpsStatus
    data class Fix(val latitude: Double, val longitude: Double, val accuracyMeters: Float?) : GpsStatus
    data object NoFix : GpsStatus
}

sealed interface RecordingUiState {
    data object Idle : RecordingUiState
    data class Recording(
        val elapsedMs: Long,
        val rms: Float,
        val gps: GpsStatus,
        val warningSoftLimit: Boolean,
    ) : RecordingUiState
    data class Done(val draftId: String) : RecordingUiState
    data class Error(val message: String) : RecordingUiState
}
