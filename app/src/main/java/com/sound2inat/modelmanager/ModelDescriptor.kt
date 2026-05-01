package com.sound2inat.modelmanager

data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val modelUrl: String,
    val labelsUrl: String,
    val modelSha256: String,
    val labelsSha256: String,
    val license: String,
    val sizeBytes: Long,
    /** Sample rate the model expects on its input tensor (Hz). */
    val sampleRateHz: Int,
    /** Window length the model consumes per inference call (milliseconds). */
    val windowMs: Long,
    /**
     * Format of the labels file. BirdNET ships `Genus species_Common name`
     * lines; Perch ships bare scientific names with a leading dataset-tag
     * row. Lets [com.sound2inat.inference.Labels] parse both without
     * branching at every call site.
     */
    val labelsFormat: LabelsFormat,
    /**
     * Hidden models do not appear in the Settings UI. They are downloaded
     * automatically by [com.sound2inat.modelmanager.ModelManager] after any
     * visible model is installed. Used for support models (e.g. YAMNet) that
     * the user never picks directly.
     */
    val hidden: Boolean = false,
)

enum class LabelsFormat {
    /** `Genus species_Common name` — BirdNET v2.4 style. */
    BirdNetUnderscore,

    /** Bare scientific names, one per line. First line may be a dataset tag. */
    PerchScientificName,
}

object BirdNetV24 {
    // Values frozen from docs/private/MODEL_SPIKE.md (2026-04-29).
    val descriptor = ModelDescriptor(
        id = "birdnet_v2_4",
        displayName = "BirdNET v2.4",
        version = "2.4",
        modelUrl = "https://github.com/woheller69/whoBIRD-TFlite/raw/master/" +
            "BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite",
        labelsUrl = "https://raw.githubusercontent.com/woheller69/whoBIRD/master/" +
            "app/src/main/assets/labels_en_uk.txt",
        modelSha256 = "55f3e4055b1a13bfa9a2452731d0d34f6a02d6b775a334362665892794165e4c",
        labelsSha256 = "af05ad18573f6ecdd14b1e457ba9265043ad8b60ec273816660125c82690e693",
        license = "CC BY-NC-SA 4.0 (weights); MIT (BirdNET-Analyzer code)",
        sizeBytes = 51_726_412L,
        sampleRateHz = 48_000,
        windowMs = 3_000L,
        labelsFormat = LabelsFormat.BirdNetUnderscore,
    )
}

/**
 * Google Perch v2 — Apache 2.0, ~14k species incl. frogs/insects/mammals.
 * Hosted on Hugging Face by `justinchuby/Perch-onnx` (TFLite ships in the
 * same repo even though the name says "onnx") with `labels.csv` mirrored
 * from the original `cgeorgiaw/Perch` SavedModel release.
 */
/**
 * Every model descriptor the app knows about, in the order shown in
 * Settings. Adding a new model = appending here + a `BioacousticModel`
 * implementation that handles the descriptor's [LabelsFormat] / sample
 * rate / window length.
 */
val KnownModels: List<ModelDescriptor> by lazy {
    listOf(BirdNetV24.descriptor, PerchV2.descriptor)
}

object PerchV2 {
    val descriptor = ModelDescriptor(
        id = "perch_v2",
        displayName = "Perch v2 (Google)",
        version = "2",
        modelUrl = "https://huggingface.co/justinchuby/Perch-onnx/resolve/main/perch_v2.tflite",
        labelsUrl = "https://huggingface.co/cgeorgiaw/Perch/resolve/main/assets/labels.csv",
        modelSha256 = "15a7497851afcb26cadfb7f537e799bd0e3e0c4f87bcb5211ca3ce88418e25d4",
        labelsSha256 = "e4d5c0397d8fb08bf90c6b13a34810af53504faad927e472fcc567793c9de057",
        license = "Apache 2.0",
        sizeBytes = 407_348_808L,
        sampleRateHz = 32_000,
        windowMs = 5_000L,
        labelsFormat = LabelsFormat.PerchScientificName,
    )
}

/**
 * BirdNET v2.4 location/time meta-model — Apache 2.0. A small TFLite that
 * takes `[lat, lon, weekMeta]` and emits one presence-prior per BirdNET
 * species (same class order as the audio model's labels). Used by
 * [com.sound2inat.inference.BirdNetMetaModel] to scale per-species confidence
 * down for species unlikely to be present at the recording location, which
 * is the official BirdNET way of doing "regional" filtering.
 *
 * Hidden so it doesn't appear in Settings — auto-installed alongside any
 * visible model (it pairs specifically with [BirdNetV24], but downloading
 * eagerly is cheap at ~6.7 MB and we don't yet have per-pair install gating).
 *
 * URL pinned to a commit hash in the same `woheller69/whoBIRD-TFlite` mirror
 * that hosts our main BirdNET weights, so the file can't shift under us.
 */
object BirdNetMetaV24 {
    val descriptor = ModelDescriptor(
        id = "birdnet_v2_4_meta",
        displayName = "BirdNET v2.4 location/time meta",
        version = "2.4",
        modelUrl = "https://raw.githubusercontent.com/woheller69/whoBIRD-TFlite/" +
            "5f7852062000f165e7079467e2f616df9c799215/" +
            "BirdNET_GLOBAL_6K_V2.4_MData_Model_FP16.tflite",
        // Reuse the audio-model's labels — meta-model output is index-aligned
        // with them, so we point at the same file rather than shipping a copy.
        labelsUrl = BirdNetV24.descriptor.labelsUrl,
        modelSha256 = "4813567791d2fe2f38fb9e195e61a6261141a6f3b134b3056b6b062d22ac88f5",
        labelsSha256 = BirdNetV24.descriptor.labelsSha256,
        license = "CC BY-NC-SA 4.0 (weights); MIT (BirdNET-Analyzer code)",
        sizeBytes = 7_071_440L,
        sampleRateHz = 0,        // unused — meta input is [lat, lon, weekMeta]
        windowMs = 0L,           // unused — single inference per recording
        labelsFormat = LabelsFormat.BirdNetUnderscore,
        hidden = true,
    )
}

/**
 * Google YAMNet — Apache 2.0, 521 AudioSet classes. Used as a biological gate
 * (see [com.sound2inat.inference.YamNetTfliteGate]); not a detection model.
 * Hidden so it doesn't appear in Settings — auto-installed alongside the
 * first visible model.
 *
 * Both URLs pinned to commit hashes for reproducibility. The model artifact
 * is the metadata-bundled YAMNet shipped with `tflite-support` test data;
 * the labels CSV is the canonical class map from the `tensorflow/models`
 * AudioSet folder. The original `tfhub-lite-models` GCS bucket lost its
 * public read permissions in 2026, so we use these GitHub mirrors.
 */
object YamNetV1 {
    val descriptor = ModelDescriptor(
        id = "yamnet_v1",
        displayName = "YAMNet v1",
        version = "1",
        modelUrl = "https://raw.githubusercontent.com/tensorflow/tflite-support/" +
            "f606e3f59240d71fd37d23316822ff0bd1d531ef/tensorflow_lite_support/cc/test/" +
            "testdata/task/audio/yamnet_audio_classifier_with_metadata.tflite",
        labelsUrl = "https://raw.githubusercontent.com/tensorflow/models/" +
            "dfffd623b6be8d1d9744b8e261fbac370d17c46d/research/audioset/yamnet/" +
            "yamnet_class_map.csv",
        modelSha256 = "10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de",
        labelsSha256 = "cdf24d193e196d9e95912a2667051ae203e92a2ba09449218ccb40ef787c6df2",
        license = "Apache 2.0",
        sizeBytes = 4_126_810L,
        sampleRateHz = 16_000,
        windowMs = 975L,
        labelsFormat = LabelsFormat.BirdNetUnderscore,  // unused — gate parses CSV directly
        hidden = true,
    )
}
