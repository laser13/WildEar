package com.sound2inat.app.ui.review

import com.sound2inat.inat.ObservationDetail
import com.sound2inat.inference.FragmentRange
import com.sound2inat.inference.RegionalStatus
import com.sound2inat.storage.DraftPhotoEntity
import com.sound2inat.storage.DraftStatus
import java.io.File

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
    /** Whether the habitat photo strip should be included for this species in the iNat submission. */
    val includeHabitatPhoto: Boolean = true,
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
    val processingProfile: ReviewProcessingProfile = ReviewProcessingProfile.Default,
    val visualsLoading: Boolean = false,
    val visualsError: String? = null,
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
    /** Last failure message from a Perch reanalysis run, cleared on next start. */
    val perchError: String? = null,
    /** Habitat photos attached to this draft, ordered by capture time. */
    val habitatPhotos: List<DraftPhotoEntity> = emptyList(),
    /** Position in the inference queue (0 = next up). Null when not queued. */
    val queuePosition: Int? = null,
    /** Estimated wait time in ms before this job starts. Null when not queued. */
    val estimatedWaitMs: Long? = null,
    /** Last error message from a queued inference job. Null when no error. */
    val queueError: String? = null,
    /** Which export action is currently running; null when idle. Drives button spinner state. */
    val exportingAction: ExportingAction? = null,
    /**
     * One-shot export side-effect. Composable must call `vm.consumeExportEffect()`
     * BEFORE executing the side effect (before startActivity or showSnackbar).
     */
    val exportEffect: ReviewExportEffect? = null,
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
    data object NeedsInteractiveLogin : InatSubmissionState
    data class Done(val urls: List<String>) : InatSubmissionState
    data class Failed(val message: String) : InatSubmissionState
}

sealed interface ObservationDetailLoadState {
    data object NotLoaded : ObservationDetailLoadState
    data object Loading : ObservationDetailLoadState
    data class Loaded(val detail: ObservationDetail) : ObservationDetailLoadState
    data class Error(val message: String) : ObservationDetailLoadState
}

sealed interface ExportingAction {
    data object FullRecordingShare : ExportingAction
    data object FullRecordingSave : ExportingAction
    data class SpeciesClipShare(val detectionId: Long) : ExportingAction
    data class SpeciesClipSave(val detectionId: Long) : ExportingAction
}

sealed interface ReviewExportEffect {
    data class ShareAudioFile(val file: File, val shareText: String? = null) : ReviewExportEffect
    data class ShowSnackbar(val message: String) : ReviewExportEffect
}
