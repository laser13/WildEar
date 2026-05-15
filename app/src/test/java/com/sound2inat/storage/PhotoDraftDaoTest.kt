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
class PhotoDraftDaoTest {
    private lateinit var db: Sound2iNatDb
    private lateinit var draftDao: PhotoDraftDao
    private lateinit var imageDao: PhotoDraftImageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            Sound2iNatDb::class.java,
        ).allowMainThreadQueries().build()
        draftDao = db.photoDrafts()
        imageDao = db.photoDraftImages()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and query images for draft`() = runTest {
        draftDao.insert(photoDraft("d1"))
        imageDao.insert(
            PhotoDraftImageEntity("p1", "d1", "/tmp/a-original.jpg", "/tmp/a.jpg", null, null, null, 2L, 0, 4000, 3000)
        )

        val images = draftDao.observeWithImages("d1").first()?.images.orEmpty()

        assertThat(images).hasSize(1)
        assertThat(images.single().id).isEqualTo("p1")
    }

    @Test
    fun `delete draft cascades images`() = runTest {
        draftDao.insert(photoDraft("d1"))
        imageDao.insert(
            PhotoDraftImageEntity("p1", "d1", "/tmp/a-original.jpg", "/tmp/a.jpg", null, null, null, 2L, 0, 4000, 3000)
        )

        draftDao.deleteById("d1")

        assertThat(draftDao.observeWithImages("d1").first()).isNull()
        assertThat(imageDao.listForDraft("d1")).isEmpty()
    }

    private fun photoDraft(id: String): PhotoDraftEntity = PhotoDraftEntity(
        id = id,
        createdAtUtcMs = 1L,
        updatedAtUtcMs = 1L,
        observedAtUtcMs = 1L,
        latitude = null,
        longitude = null,
        locationAccuracyMeters = null,
        status = PhotoDraftStatus.PENDING_REVIEW,
        taxonScientificName = null,
        taxonCommonName = null,
        taxonInatId = null,
        description = null,
        inatObservationId = null,
        inatObservationUuid = null,
        inatObservationUrl = null,
        inatLastError = null,
    )
}
