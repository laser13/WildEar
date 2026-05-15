package com.sound2inat.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DraftStatus { PENDING_INFERENCE, PENDING_REVIEW, REVIEWED, UPLOADED }

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val id: String,
    val audioPath: String,
    val recordedAtUtcMs: Long,
    val durationMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyMeters: Float?,
    val status: DraftStatus,
    val modelId: String?,
    val modelVersion: String?,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    val inatLastError: String? = null,
    val displayRangeName: String? = null,
    val paletteName: String? = null,
    val spectrogramGainDb: Float? = null,
    val sceneTagsJson: String? = null,
)
