package com.sound2inat.app.ui.review

import com.sound2inat.inference.RegionalStatus
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
    /** `modelId → maxConfidence` per source. Empty for legacy single-model rows. */
    val confidenceBySource: Map<String, Float> = emptyMap(),
    /** iNaturalist default_photo.medium_url; null until fetched or if unavailable. */
    val taxonPhotoUrl: String? = null,
    /** Regional presence status from iNaturalist; null until annotation runs. */
    val regionalStatus: RegionalStatus? = null,
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
    /**
     * Persisted observations attached to this draft from prior submissions.
     * Each entry is `(scientificName, observationUrl)`.
     */
    val inatObservations: List<Pair<String, String>> = emptyList(),
    val inatSubmission: InatSubmissionState = InatSubmissionState.Idle,
    /** When true, the Review screen plays/displays the denoised version of the audio. */
    val denoisePreviewEnabled: Boolean = false,
    /** True while the denoise preview WAV/PNG are being computed in the background. */
    val denoisingInProgress: Boolean = false,
    /**
     * True when the Perch model artifact is installed (Ready). Drives whether
     * the "Perch" option in the pull-to-refresh model picker is enabled —
     * results always merge into existing detections, so we no longer block
     * a second Perch run.
     */
    val isPerchInstalled: Boolean = false,
    /**
     * Progress fraction of the on-demand Perch run, or null when not running.
     * Mutually exclusive with [inferenceProgress] in practice — UI only shows
     * one progress block at a time.
     */
    val perchProgress: Float? = null,
    /** Last failure message from an [analyzeWithPerch] run, cleared on next start. */
    val perchError: String? = null,
)

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Playing(val positionMs: Long) : PlaybackState
    data class Paused(val positionMs: Long) : PlaybackState
    data class Error(val message: String) : PlaybackState
}

sealed interface InatSubmissionState {
    data object Idle : InatSubmissionState
    data object InProgress : InatSubmissionState
    data class Done(val urls: List<String>) : InatSubmissionState
    data class Failed(val message: String) : InatSubmissionState
}
