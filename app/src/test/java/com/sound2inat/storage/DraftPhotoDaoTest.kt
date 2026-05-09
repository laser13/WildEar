package com.sound2inat.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class DraftPhotoDaoTest {
    private lateinit var db: Sound2iNatDb
    private lateinit var dao: DraftPhotoDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            Sound2iNatDb::class.java,
        ).allowMainThreadQueries().build()
        dao = db.photos()
        // Insert a parent draft so the FK doesn't fire.
        db.drafts().insert(
            DraftEntity(
                id = "d1", audioPath = "/tmp/a.wav", recordedAtUtcMs = 0L,
                durationMs = 3000L, latitude = null, longitude = null,
                locationAccuracyMeters = null, status = DraftStatus.PENDING_REVIEW,
                modelId = null, modelVersion = null, createdAtUtcMs = 0L, updatedAtUtcMs = 0L,
            ),
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `insert and query photos for draft`() = runTest {
        dao.insert(DraftPhotoEntity(id = "p1", draftId = "d1", photoPath = "/a.jpg", takenAtMs = 1L))
        dao.insert(DraftPhotoEntity(id = "p2", draftId = "d1", photoPath = "/b.jpg", takenAtMs = 2L))
        val photos = dao.photosForDraft("d1").first()
        assertThat(photos).hasSize(2)
        assertThat(photos.map { it.id }).containsExactly("p1", "p2")
    }

    @Test
    fun `deleteById removes single photo`() = runTest {
        dao.insert(DraftPhotoEntity(id = "p1", draftId = "d1", photoPath = "/a.jpg", takenAtMs = 1L))
        dao.deleteById("p1")
        assertThat(dao.photosForDraft("d1").first()).isEmpty()
    }

    @Test
    fun `deleteByDraftId removes all photos for draft`() = runTest {
        dao.insert(DraftPhotoEntity(id = "p1", draftId = "d1", photoPath = "/a.jpg", takenAtMs = 1L))
        dao.insert(DraftPhotoEntity(id = "p2", draftId = "d1", photoPath = "/b.jpg", takenAtMs = 2L))
        dao.deleteByDraftId("d1")
        assertThat(dao.photosForDraft("d1").first()).isEmpty()
    }
}
