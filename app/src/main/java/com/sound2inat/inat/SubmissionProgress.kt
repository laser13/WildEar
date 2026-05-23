package com.sound2inat.inat

/**
 * Progress event emitted by [INatSubmitter.submit] for each major step of
 * a multi-species upload. The ViewModel keeps the latest event and the
 * Review screen renders a compact per-species checklist plus a single
 * sub-status line ("audio 2/3", "tags", ...).
 *
 * [Species] events carry the 1-indexed `speciesIndex` so the UI doesn't
 * need to maintain its own counter; `totalSpecies` is the count of
 * selected species in this submission.
 */
sealed interface SubmissionProgress {

    /**
     * Per-species step inside the main loop.
     *
     * Also used by [PhotoSubmitter] for single-observation photo uploads with
     * fixed `speciesIndex = 1, totalSpecies = 1, taxonScientificName = displayName`
     * (the user-facing taxon name, or "Photo observation" when no taxon is set).
     * Future renames should consider this dual use.
     */
    data class Species(
        val speciesIndex: Int,
        val totalSpecies: Int,
        val taxonScientificName: String,
        val step: Step,
    ) : SubmissionProgress

    /** All species processed — running the cross-link pass. */
    data object CrossLinking : SubmissionProgress

    enum class Step {
        ResolvingTaxon,
        CreatingObservation,

        /** First sound clip; failure here rolls back the observation. */
        UploadingPrimaryAudio,

        /** Additional sound clips (best-effort). */
        UploadingExtraAudio,

        /** First photo upload; failure here rolls back the observation. */
        UploadingPrimaryPhoto,

        /** Additional photo uploads (best-effort). */
        UploadingExtraPhoto,
        UploadingSpectrogram,
        ApplyingTag,
        ApplyingAnnotations,
        AddingIdentification,
        UploadingHabitatPhotos,
        Persisting,
        DoneOk,
        DoneFailed,
    }
}
