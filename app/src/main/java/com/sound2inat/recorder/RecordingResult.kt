package com.sound2inat.recorder

data class RecordingResult(
    val audioPath: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
)
