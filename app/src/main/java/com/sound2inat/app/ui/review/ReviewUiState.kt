package com.sound2inat.app.ui.review

import com.sound2inat.inat.ObservationDetail
import com.sound2inat.inference.FragmentRange
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
    val observationDetailState: ObservationDetailLoadState = ObservationDetailLoadState.NotLoaded,
    /** Per-window detection time ranges, sorted by startMs. Empty for legacy rows. */
    val fragmentRanges: List<FragmentRange> = emptyList(),
)

data class InatObsEntry(
    val scientificName: String,
    val observationId: Long,
    val url: String,
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
    /** Persisted observations attached to this draft from prior submissions. */
    val inatObservations: List<InatObsEntry> = emptyList(),
    val inatSubmission: InatSubmissionState = InatSubmissionState.Idle,
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
    /** Whether playback uses the denoised audio file instead of the raw recording. */
    val denoisePlayback: Boolean = false,
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

sealed interface ObservationDetailLoadState {
    data object NotLoaded : ObservationDetailLoadState
    data object Loading : ObservationDetailLoadState
    data class Loaded(val detail: ObservationDetail) : ObservationDetailLoadState
    data class Error(val message: String) : ObservationDetailLoadState
}
