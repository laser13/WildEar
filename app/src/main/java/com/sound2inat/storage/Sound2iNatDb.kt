package com.sound2inat.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DraftEntity::class,
        DetectionEntity::class,
        InatObservationEntity::class,
        DraftPhotoEntity::class,
        PhotoDraftEntity::class,
        PhotoDraftImageEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class Sound2iNatDb : RoomDatabase() {
    abstract fun drafts(): DraftDao
    abstract fun detections(): DetectionDao
    abstract fun inatObservations(): InatObservationDao
    abstract fun photos(): DraftPhotoDao
    abstract fun photoDrafts(): PhotoDraftDao
    abstract fun photoDraftImages(): PhotoDraftImageDao

    companion object {
        // v2: iNaturalist submission tracking columns on `drafts`.
        // The new `UPLOADED` enum value reuses the existing TEXT column;
        // only the new columns require an ALTER TABLE.
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drafts ADD COLUMN inatObservationId INTEGER")
                db.execSQL("ALTER TABLE drafts ADD COLUMN inatObservationUrl TEXT")
                db.execSQL("ALTER TABLE drafts ADD COLUMN inatLastError TEXT")
            }
        }

        // v3: separate table for per-species observation rows. iNaturalist
        // demands one observation per species; we used to wedge a single
        // observation_id into `drafts` which only worked for the top-1 case.
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS inat_observations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        draftId TEXT NOT NULL,
                        taxonScientificName TEXT NOT NULL,
                        taxonInatId INTEGER NOT NULL,
                        observationId INTEGER NOT NULL,
                        observationUrl TEXT NOT NULL,
                        createdAtUtcMs INTEGER NOT NULL,
                        FOREIGN KEY(draftId) REFERENCES drafts(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inat_observations_draftId ON inat_observations(draftId)")
            }
        }

        // v4: per-source confidence tracking on detections. New `sources`
        // column is nullable so rows persisted under v1–v3 (single-model)
        // remain readable; aggregator falls back to maxConfidence when null.
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE detections ADD COLUMN sources TEXT")
            }
        }

        // v5: per-window fragment ranges and aggregated confidence on detections.
        // Both columns have safe defaults so pre-v5 rows remain readable.
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE detections ADD COLUMN fragmentRanges TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE detections ADD COLUMN aggregatedConfidence REAL NOT NULL DEFAULT 0.0")
            }
        }

        // v6: habitat photo attachments for iNaturalist observations.
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS draft_photos (
                        id TEXT NOT NULL PRIMARY KEY,
                        draftId TEXT NOT NULL,
                        photoPath TEXT NOT NULL,
                        takenAtMs INTEGER NOT NULL,
                        FOREIGN KEY(draftId) REFERENCES drafts(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_draft_photos_draftId ON draft_photos(draftId)",
                )
            }
        }

        // v7: remove orphaned inatObservationId and inatObservationUrl columns from drafts.
        // These were superseded by the inat_observations table added in v3.
        // SQLite on API 28 does not support DROP COLUMN, so the table must be recreated.
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS drafts_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        audioPath TEXT NOT NULL,
                        recordedAtUtcMs INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        locationAccuracyMeters REAL,
                        status TEXT NOT NULL,
                        modelId TEXT,
                        modelVersion TEXT,
                        createdAtUtcMs INTEGER NOT NULL,
                        updatedAtUtcMs INTEGER NOT NULL,
                        inatLastError TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO drafts_new
                    SELECT id, audioPath, recordedAtUtcMs, durationMs, latitude, longitude,
                           locationAccuracyMeters, status, modelId, modelVersion,
                           createdAtUtcMs, updatedAtUtcMs, inatLastError
                    FROM drafts
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE drafts")
                db.execSQL("ALTER TABLE drafts_new RENAME TO drafts")
            }
        }

        // v8: standalone photo-observation albums. This is intentionally
        // separate from draft_photos, which remains audio habitat-photo support.
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS photo_drafts (
                        id TEXT NOT NULL PRIMARY KEY,
                        createdAtUtcMs INTEGER NOT NULL,
                        updatedAtUtcMs INTEGER NOT NULL,
                        observedAtUtcMs INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        locationAccuracyMeters REAL,
                        status TEXT NOT NULL,
                        taxonScientificName TEXT,
                        taxonCommonName TEXT,
                        taxonInatId INTEGER,
                        description TEXT,
                        inatObservationId INTEGER,
                        inatObservationUrl TEXT,
                        inatLastError TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS photo_draft_images (
                        id TEXT NOT NULL PRIMARY KEY,
                        photoDraftId TEXT NOT NULL,
                        photoPath TEXT NOT NULL,
                        takenAtUtcMs INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        width INTEGER,
                        height INTEGER,
                        mimeType TEXT NOT NULL,
                        FOREIGN KEY(photoDraftId) REFERENCES photo_drafts(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_photo_draft_images_photoDraftId ON photo_draft_images(photoDraftId)",
                )
            }
        }
    }
}
