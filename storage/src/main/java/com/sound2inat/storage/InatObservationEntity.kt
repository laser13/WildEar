package com.sound2inat.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence state of an iNaturalist observation row, decoupled from the
 * draft-level [DraftStatus].
 *
 * * [INCOMPLETE] — the observation was created on iNat (we own a valid
 *   `observationId` + URL), but one or more per-species side effects
 *   (extra sound clips, spectrogram photos, tags, annotations, identification,
 *   habitat photos, cross-link description) failed or never ran. The row is
 *   surfaced in the Review screen as recoverable and offered for delete-and-
 *   recreate.
 * * [COMPLETE] — every step finished. Identical semantics to "uploaded" in
 *   v11 and earlier.
 */
enum class InatUploadStatus { INCOMPLETE, COMPLETE }

/**
 * One row per observation created on iNaturalist for a given draft. A single
 * draft can produce many rows — iNaturalist treats each species as its own
 * observation, so we mirror that: per selected species we insert one row
 * the moment iNat returns a successful create-and-first-sound-upload, and
 * flip its [uploadStatus] to [InatUploadStatus.COMPLETE] when all follow-up
 * steps finish.
 */
@Entity(
    tableName = "inat_observations",
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
data class InatObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val draftId: String,
    val taxonScientificName: String,
    val taxonInatId: Long,
    val observationId: Long,
    val observationUrl: String,
    val createdAtUtcMs: Long,
    val uploadStatus: InatUploadStatus = InatUploadStatus.COMPLETE,
)
