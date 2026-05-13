package com.sound2inat.storage

data class PhotoDraftSummary(
    val id: String,
    val observedAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    val status: PhotoDraftStatus,
    val taxonScientificName: String?,
    val taxonCommonName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyMeters: Float?,
    val firstPhotoPath: String?,
    val photoCount: Int,
    val inatObservationUrl: String?,
    val inatLastError: String?,
)
