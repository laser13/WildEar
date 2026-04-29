package com.sound2inat.app.ui.review

import com.sound2inat.storage.DraftStatus

data class SpeciesRow(
    val detectionId: Long,
    val taxonScientificName: String,
    val taxonCommonName: String?,
    val maxConfidence: Float,
    val detectedWindows: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val isSelected: Boolean,
)

data class ReviewUiState(
    val draftId: String,
    val status: DraftStatus = DraftStatus.PENDING_INFERENCE,
    val recordedAtUtcMs: Long = 0L,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val durationMs: Long = 0L,
    val audioPath: String? = null,
    val inferenceProgress: Float? = null,
    val inferenceError: String? = null,
    val species: List<SpeciesRow> = emptyList(),
    val playback: PlaybackState = PlaybackState.Idle,
)

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Playing(val positionMs: Long) : PlaybackState
    data class Paused(val positionMs: Long) : PlaybackState
    data class Error(val message: String) : PlaybackState
}
