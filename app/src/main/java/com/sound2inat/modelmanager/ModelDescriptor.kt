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
    )
}
