package com.sound2inat.storage

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        Sound2iNatDb::class.java,
    )

    @Test
    fun `migrate 1 to 2 preserves draft row and adds nullable inat columns`() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                """INSERT INTO drafts (id, audioPath, recordedAtUtcMs, durationMs,
                    latitude, longitude, locationAccuracyMeters,
                    status, modelId, modelVersion, createdAtUtcMs, updatedAtUtcMs)
                   VALUES ('d1', '/audio/a.wav', 1000, 3000,
                    48.85, 2.35, 10.0,
                    'PENDING', 'birdnet', '2.4', 1000, 1000)""",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            2,
            true,
            Sound2iNatDb.MIGRATION_1_2,
        )

        db.query("SELECT inatObservationId, inatObservationUrl, inatLastError FROM drafts WHERE id='d1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
            assertThat(c.isNull(1)).isTrue()
            assertThat(c.isNull(2)).isTrue()
        }
        db.query("SELECT COUNT(*) FROM drafts").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
    }

    @Test
    fun `migrate 2 to 3 creates inat_observations table with index`() {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                """INSERT INTO drafts (id, audioPath, recordedAtUtcMs, durationMs,
                    latitude, longitude, locationAccuracyMeters,
                    status, modelId, modelVersion, createdAtUtcMs, updatedAtUtcMs,
                    inatObservationId, inatObservationUrl, inatLastError)
                   VALUES ('d2', '/audio/b.wav', 2000, 5000,
                    NULL, NULL, NULL,
                    'UPLOADED', 'birdnet', '2.4', 2000, 2000,
                    99, 'https://www.inaturalist.org/observations/99', NULL)""",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            Sound2iNatDb.MIGRATION_2_3,
        )

        db.query("SELECT COUNT(*) FROM inat_observations").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        db.query("SELECT COUNT(*) FROM drafts WHERE id='d2'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_inat_observations_draftId'",
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
        }
    }

    @Test
    fun `migrate 3 to 4 adds nullable sources column to detections`() {
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                """INSERT INTO drafts (id, audioPath, recordedAtUtcMs, durationMs,
                    latitude, longitude, locationAccuracyMeters,
                    status, modelId, modelVersion, createdAtUtcMs, updatedAtUtcMs,
                    inatObservationId, inatObservationUrl, inatLastError)
                   VALUES ('d3', '/audio/c.wav', 3000, 4000,
                    NULL, NULL, NULL,
                    'ANALYSED', 'birdnet', '2.4', 3000, 3000,
                    NULL, NULL, NULL)""",
            )
            db.execSQL(
                """INSERT INTO detections (draftId, taxonScientificName, taxonCommonName,
                    maxConfidence, detectedWindows, firstSeenMs, lastSeenMs, isSelectedByUser)
                   VALUES ('d3', 'Parus major', 'Great Tit',
                    0.87, 3, 0, 9000, 0)""",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            4,
            true,
            Sound2iNatDb.MIGRATION_3_4,
        )

        db.query("SELECT sources FROM detections WHERE draftId='d3'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
        }
        db.query("SELECT COUNT(*) FROM detections").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
    }

    @Test
    fun `migrate 4 to 5 adds fragmentRanges and aggregatedConfidence with safe defaults`() {
        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL(
                """INSERT INTO drafts (id, audioPath, recordedAtUtcMs, durationMs,
                    latitude, longitude, locationAccuracyMeters,
                    status, modelId, modelVersion, createdAtUtcMs, updatedAtUtcMs,
                    inatObservationId, inatObservationUrl, inatLastError)
                   VALUES ('d4', '/audio/d.wav', 4000, 6000,
                    NULL, NULL, NULL,
                    'ANALYSED', 'perch', '1.0', 4000, 4000,
                    NULL, NULL, NULL)""",
            )
            db.execSQL(
                """INSERT INTO detections (draftId, taxonScientificName, taxonCommonName,
                    maxConfidence, detectedWindows, firstSeenMs, lastSeenMs, isSelectedByUser,
                    sources)
                   VALUES ('d4', 'Sylvia atricapilla', 'Blackcap',
                    0.92, 5, 0, 15000, 1,
                    '{"birdnet":0.92}')""",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            5,
            true,
            Sound2iNatDb.MIGRATION_4_5,
        )

        db.query("SELECT fragmentRanges, aggregatedConfidence FROM detections WHERE draftId='d4'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("")
            assertThat(c.getDouble(1)).isEqualTo(0.0)
        }
        db.query("SELECT COUNT(*) FROM detections").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
    }

    @Test
    fun `migrate 5 to 6 creates draft_photos table with index`() {
        helper.createDatabase(dbName, 5).use { db ->
            db.execSQL(
                """INSERT INTO drafts (id, audioPath, recordedAtUtcMs, durationMs,
                    latitude, longitude, locationAccuracyMeters,
                    status, modelId, modelVersion, createdAtUtcMs, updatedAtUtcMs,
                    inatObservationId, inatObservationUrl, inatLastError)
                   VALUES ('d5', '/audio/e.wav', 5000, 7000,
                    NULL, NULL, NULL,
                    'ANALYSED', 'birdnet', '2.4', 5000, 5000,
                    NULL, NULL, NULL)""",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            6,
            true,
            Sound2iNatDb.MIGRATION_5_6,
        )

        // draft_photos table must exist and be empty (migration only creates the schema).
        db.query("SELECT COUNT(*) FROM draft_photos").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        // Verify index was created.
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_draft_photos_draftId'",
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
        }
        // Verify draft_photos columns: id, draftId, photoPath, takenAtMs.
        db.execSQL(
            "INSERT INTO draft_photos (id, draftId, photoPath, takenAtMs) VALUES ('p1', 'd5', '/photo/1.jpg', 5001)",
        )
        db.query("SELECT id, draftId, photoPath, takenAtMs FROM draft_photos WHERE id='p1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("p1")
            assertThat(c.getString(1)).isEqualTo("d5")
            assertThat(c.getString(2)).isEqualTo("/photo/1.jpg")
            assertThat(c.getLong(3)).isEqualTo(5001L)
        }
        // Pre-existing drafts row must still be there.
        db.query("SELECT COUNT(*) FROM drafts WHERE id='d5'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
    }

    @Test
    fun `migrate 6 to 7 drops inatObservationId and inatObservationUrl from drafts`() {
        helper.createDatabase(dbName, 6).use { db ->
            db.execSQL(
                """INSERT INTO drafts (id, audioPath, recordedAtUtcMs, durationMs,
                    latitude, longitude, locationAccuracyMeters,
                    status, modelId, modelVersion, createdAtUtcMs, updatedAtUtcMs,
                    inatObservationId, inatObservationUrl, inatLastError)
                   VALUES ('d6', '/audio/f.wav', 6000, 8000,
                    NULL, NULL, NULL,
                    'UPLOADED', 'birdnet', '2.4', 6000, 6000,
                    42, 'https://inat.org/obs/42', NULL)""",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            7,
            true,
            Sound2iNatDb.MIGRATION_6_7,
        )

        db.query("SELECT id, audioPath, inatLastError FROM drafts WHERE id='d6'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("d6")
            assertThat(c.getString(1)).isEqualTo("/audio/f.wav")
            assertThat(c.isNull(2)).isTrue()
        }
        db.query("SELECT COUNT(*) FROM drafts").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
    }

    @Test
    fun `migrate 7 to 8 creates photo album tables`() {
        helper.createDatabase(dbName, 7).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            8,
            true,
            Sound2iNatDb.MIGRATION_7_8,
        )

        db.query("SELECT COUNT(*) FROM photo_drafts").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        db.query("SELECT COUNT(*) FROM photo_draft_images").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_photo_draft_images_photoDraftId'",
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
        }
    }

    @Test
    fun `migrate 8 to 10 backfills original photo path for existing image rows`() {
        helper.createDatabase(dbName, 8).use { db ->
            db.execSQL(
                """INSERT INTO photo_drafts (
                    id, createdAtUtcMs, updatedAtUtcMs, observedAtUtcMs,
                    latitude, longitude, locationAccuracyMeters,
                    status, taxonScientificName, taxonCommonName, taxonInatId,
                    description, inatObservationId, inatObservationUrl, inatLastError
                ) VALUES (
                    'pd1', 1, 1, 1,
                    35.0, 33.0, 10.0,
                    'PENDING_REVIEW', null, null, null,
                    null, null, null, null
                )""",
            )
            db.execSQL(
                """INSERT INTO photo_draft_images (
                    id, photoDraftId, photoPath, takenAtUtcMs, sortOrder, width, height, mimeType
                ) VALUES (
                    'img1', 'pd1', '/tmp/current.jpg', 2, 0, 4000, 3000, 'image/jpeg'
                )""",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            10,
            true,
            Sound2iNatDb.MIGRATION_8_9,
            Sound2iNatDb.MIGRATION_9_10,
        )

        db.query(
            "SELECT originalPhotoPath, photoPath, cropLeftPx, cropTopPx, cropSizePx FROM photo_draft_images WHERE id='img1'"
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("/tmp/current.jpg")
            assertThat(c.getString(1)).isEqualTo("/tmp/current.jpg")
            assertThat(c.isNull(2)).isTrue()
            assertThat(c.isNull(3)).isTrue()
            assertThat(c.isNull(4)).isTrue()
        }
    }

    @Test
    fun `migrate all versions 1 through 10 preserves seed data`() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                """INSERT INTO drafts (id, audioPath, recordedAtUtcMs, durationMs,
                    latitude, longitude, locationAccuracyMeters,
                    status, modelId, modelVersion, createdAtUtcMs, updatedAtUtcMs)
                   VALUES ('d5', '/audio/e.wav', 5000, 7000,
                    59.33, 18.07, 5.0,
                    'PENDING', 'birdnet', '2.4', 5000, 5000)""",
            )
            db.execSQL(
                """INSERT INTO detections (draftId, taxonScientificName, taxonCommonName,
                    maxConfidence, detectedWindows, firstSeenMs, lastSeenMs, isSelectedByUser)
                   VALUES ('d5', 'Erithacus rubecula', 'European Robin',
                    0.78, 2, 0, 6000, 0)""",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName, 10, true,
            Sound2iNatDb.MIGRATION_1_2,
            Sound2iNatDb.MIGRATION_2_3,
            Sound2iNatDb.MIGRATION_3_4,
            Sound2iNatDb.MIGRATION_4_5,
            Sound2iNatDb.MIGRATION_5_6,
            Sound2iNatDb.MIGRATION_6_7,
            Sound2iNatDb.MIGRATION_7_8,
            Sound2iNatDb.MIGRATION_8_9,
            Sound2iNatDb.MIGRATION_9_10,
        )

        db.query("SELECT COUNT(*) FROM drafts WHERE id='d5'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        db.query(
            "SELECT taxonScientificName, sources, fragmentRanges, aggregatedConfidence FROM detections WHERE draftId='d5'",
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Erithacus rubecula")
            assertThat(c.isNull(1)).isTrue()
            assertThat(c.getString(2)).isEqualTo("")
            assertThat(c.getDouble(3)).isEqualTo(0.0)
        }
        db.query("SELECT COUNT(*) FROM inat_observations").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        db.query("SELECT COUNT(*) FROM photo_drafts").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
    }
}
