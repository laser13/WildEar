package com.sound2inat.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photo_drafts")
data class PhotoDraftEntity(
    @PrimaryKey val id: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    val observedAtUtcMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyMeters: Float?,
    val status: PhotoDraftStatus,
    val taxonScientificName: String?,
    val taxonCommonName: String?,
    val taxonInatId: Long?,
    val description: String?,
    val inatObservationId: Long?,
    val inatObservationUuid: String?,
    val inatObservationUrl: String?,
    val inatLastError: String?,
)
