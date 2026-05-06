package com.sound2inat.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "detections",
    foreignKeys = [
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("draftId")],
)
data class DetectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val draftId: String,
    val taxonScientificName: String,
    val taxonCommonName: String?,
    val maxConfidence: Float,
    val detectedWindows: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val isSelectedByUser: Boolean,
    /**
     * Per-source max confidence serialised as `"src1=0.85;src2=0.62"`. `null`
     * for rows written before v4 (legacy single-model path).
     */
    val sources: String? = null,
    /**
     * Window time ranges serialised as `"startMs:endMs,startMs:endMs,…"`.
     * Empty string for rows written before v5 (pre-fragment-range tracking).
     */
    val fragmentRanges: String = "",
    /** Average confidence across all contributing windows. 0 for pre-v5 rows. */
    val aggregatedConfidence: Float = 0f,
)
