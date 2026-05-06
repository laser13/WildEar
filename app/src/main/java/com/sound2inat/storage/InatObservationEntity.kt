package com.sound2inat.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per observation created on iNaturalist for a given draft. A single
 * draft can produce many rows — iNaturalist treats each species as its own
 * observation, so we mirror that: per selected species we create one row
 * here once the upload succeeded.
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
)
