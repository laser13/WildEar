package com.sound2inat.app.ui.recording

import com.sound2inat.inference.RegionalStatus

sealed interface GpsStatus {
    data object Acquiring : GpsStatus
    data class Fix(val latitude: Double, val longitude: Double, val accuracyMeters: Float?) : GpsStatus
    data object NoFix : GpsStatus
}

/**
 * UI projection of an [com.sound2inat.inference.AggregatedDetection] as shown
 * on the live recording screen. Mirrors the Merlin Bird ID per-species card:
 * common name + scientific name + window count + peak confidence.
 *
 * [regionalStatus] is filled in asynchronously by the controller after the
 * iNaturalist regional check resolves. Null while the check is pending
 * (or when the regional filter is disabled / GPS unavailable).
 */
data class LiveCard(
    val scientificName: String,
    val commonName: String?,
    val count: Int,
    val peakConfidence: Float,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val regionalStatus: RegionalStatus? = null,
)

sealed interface RecordingUiState {
    data object Idle : RecordingUiState
    data class Recording(
        val draftId: String,
        val elapsedMs: Long,
        val rms: Float,
        val gps: GpsStatus,
        val warningSoftLimit: Boolean,
        val liveCards: List<LiveCard> = emptyList(),
        val backlogWindows: Int = 0,
        val habitatPhotoCount: Int = 0,
    ) : RecordingUiState
    data class Done(val draftId: String) : RecordingUiState
    data class Error(val message: String) : RecordingUiState
}
