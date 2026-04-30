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
