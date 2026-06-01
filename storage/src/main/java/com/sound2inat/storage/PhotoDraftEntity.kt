package com.sound2inat.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-row upload state for photo observations, decoupled from the draft's
 * lifecycle [PhotoDraftStatus]. Null means "no upload in flight or
 * attempted"; [INCOMPLETE] means iNat created the observation and accepted
 * at least the first photo but a later step failed (tags / extra photos)
 * — the row is recoverable via the banner-driven delete-and-recreate
 * action. [COMPLETE] means every step finished. Unknown stored values fall
 * back to [INCOMPLETE] so recovery surfaces them instead of hiding them.
 */
enum class PhotoUploadStatus { INCOMPLETE, COMPLETE }

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
    val uploadStatus: PhotoUploadStatus? = null,
)
