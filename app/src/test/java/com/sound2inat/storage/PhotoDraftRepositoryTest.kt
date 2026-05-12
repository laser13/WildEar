package com.sound2inat.storage

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PhotoDraftRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val instant = InstantTaskExecutorRule()

    private lateinit var db: Sound2iNatDb
    private lateinit var fileStore: PhotoObservationFileStore
    private lateinit var repo: PhotoDraftRepository
    private var nextId = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            Sound2iNatDb::class.java,
        ).allowMainThreadQueries().build()
        fileStore = PhotoObservationFileStore(tmp.root)
        repo = PhotoDraftRepository(
            draftDao = db.photoDrafts(),
            imageDao = db.photoDraftImages(),
            fileStore = fileStore,
            nowMs = { 10L },
            idFactory = { "id${++nextId}" },
            ioDispatcher = UnconfinedTestDispatcher(),
            runInTransaction = { block -> db.runInTransaction(block) },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `create draft add image and delete draft cleanly`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val imageFile = fileStore.newPhotoFile(draftId, "photo1").apply {
            parentFile?.mkdirs()
            writeText("jpeg")
        }

        repo.addImage(draftId, imageFile, takenAtUtcMs = 2L, width = 4000, height = 3000)

        assertThat(repo.observeWithImages(draftId).first()?.images).hasSize(1)
        assertThat(imageFile.exists()).isTrue()

        repo.deleteDraft(draftId)

        assertThat(db.photoDrafts().getById(draftId)).isNull()
        assertThat(db.photoDraftImages().listForDraft(draftId)).isEmpty()
        assertThat(imageFile.exists()).isFalse()
    }

    @Test
    fun `observe summaries returns newest first with first thumbnail and count`() = runTest {
        val oldId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val newId = repo.createDraft(2L, latitude = 10.0, longitude = 20.0, accuracyMeters = 5f)
        val firstPhoto = fileStore.newPhotoFile(newId, "first").apply { writeText("jpeg") }
        val secondPhoto = fileStore.newPhotoFile(newId, "second").apply { writeText("jpeg") }

        repo.addImage(newId, firstPhoto, takenAtUtcMs = 3L, width = 100, height = 100)
        repo.addImage(newId, secondPhoto, takenAtUtcMs = 4L, width = 200, height = 200)

        val summaries = repo.observeSummaries().first()

        assertThat(summaries.map { it.id }).containsExactly(newId, oldId).inOrder()
        assertThat(summaries.first().firstPhotoPath).isEqualTo(firstPhoto.absolutePath)
        assertThat(summaries.first().photoCount).isEqualTo(2)
        assertThat(summaries.first().latitude).isEqualTo(10.0)
    }
}
