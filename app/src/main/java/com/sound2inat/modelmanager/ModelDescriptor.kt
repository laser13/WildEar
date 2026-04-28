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
)

object BirdNetV24 {
    val descriptor = ModelDescriptor(
        id = "birdnet_v2_4",
        displayName = "BirdNET v2.4",
        version = "2.4",
        // TODO[Task 7]: replace from MODEL_SPIKE.md (Task 2)
        modelUrl = "https://github.com/kahst/BirdNET-Analyzer/raw/main/" +
            "checkpoints/V2.4/BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite",
        labelsUrl = "https://github.com/kahst/BirdNET-Analyzer/raw/main/" +
            "checkpoints/V2.4/BirdNET_GLOBAL_6K_V2.4_Labels.txt",
        // TODO[Task 7]: replace from MODEL_SPIKE.md (Task 2)
        modelSha256 = "PLACEHOLDER_FROM_MODEL_SPIKE_MD",
        labelsSha256 = "PLACEHOLDER_FROM_MODEL_SPIKE_MD",
        license = "CC BY-NC-SA 4.0 (weights); MIT (BirdNET-Analyzer code)",
        sizeBytes = 0L,
    )
}
