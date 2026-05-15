package com.sound2inat.inference

/**
 * Result of a single inference invocation. Either [Success] with the
 * aggregated species list, or [Failure] with a user-facing message.
 */
sealed interface InferenceOutcome {
    data class Success(
        val modelId: String,
        val modelVersion: String,
        val detections: List<AggregatedDetection>,
        /**
         * Raw per-window predictions retained for overlay rendering on the
         * Review screen. Not persisted — overlays disappear if the user closes
         * and reopens the screen until inference reruns. (See Task 15.)
         */
        val windows: List<WindowPrediction> = emptyList(),
        /**
         * Per-recording aggregated YamNet scene scores (element-wise max across
         * windows). Defaults to [SceneTags.EMPTY] when the gate was disabled
         * or never observed biological activity; the queue uses this to decide
         * whether to persist sceneTagsJson on the draft.
         */
        val sceneTags: SceneTags = SceneTags.EMPTY,
    ) : InferenceOutcome

    data class Failure(val message: String) : InferenceOutcome
}

/**
 * Runs inference for a single draft. Implementations are responsible for:
 * - resolving the on-disk model & labels,
 * - loading the model,
 * - slicing the WAV and reporting progress via [onProgress] in `[0,1]`,
 * - aggregating predictions into a species list.
 *
 * Decoupled from [BioacousticModel] / [InferenceRunner] so the VM is testable
 * without TFLite or a real WAV.
 */
fun interface InferenceJob {
    suspend fun run(
        audioPath: String,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        onProgress: (Float) -> Unit,
    ): InferenceOutcome
}

/**
 * Outcome of an on-demand Perch analysis pass over a saved WAV. The VM merges
 * [Success.detections] with the existing per-draft detections and persists the
 * union — Perch never *replaces* prior BirdNET output.
 */
sealed interface PerchAnalysisOutcome {
    /** Aggregated Perch detections, ready to be merged. */
    data class Success(val detections: List<AggregatedDetection>) : PerchAnalysisOutcome

    /** Perch model artifact is not installed (state != Ready) — UI shouldn't have offered the button. */
    data object NotInstalled : PerchAnalysisOutcome

    data class Failure(val message: String) : PerchAnalysisOutcome
}

/**
 * Seam abstracting the on-demand Perch pipeline from the VM so unit tests can
 * inject canned outcomes without loading a 407 MB TFLite. Production wiring
 * loads [com.sound2inat.inference.PerchTfliteModel] and runs
 * [com.sound2inat.inference.InferenceRunner] against the WAV at [audioPath].
 */
fun interface PerchAnalysisJob {
    suspend fun run(
        audioPath: String,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        onProgress: (Float) -> Unit,
    ): PerchAnalysisOutcome
}
