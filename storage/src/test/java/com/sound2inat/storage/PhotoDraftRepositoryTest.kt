package com.sound2inat.storage

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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

    // Shared scheduler so that ioDispatcher and runTest use the same virtual clock.
    // This is required because combine() creates child coroutines via produce(), and
    // flowOn(ioDispatcher) must share the TestCoroutineScheduler with runTest.
    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = UnconfinedTestDispatcher(testScheduler)

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
            ioDispatcher = testDispatcher,
            runInTransaction = { block -> db.runInTransaction(block) },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `create draft add image and delete draft cleanly`() = runTest(testScheduler) {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val imageFile = fileStore.newPhotoFile(draftId, "photo1").apply {
            parentFile?.mkdirs()
            writeText("jpeg")
        }

        repo.addImage(
            draftId = draftId,
            photoId = "photo1",
            imageFile = imageFile,
            takenAtUtcMs = 2L,
            width = 4000,
            height = 3000,
        )

        assertThat(repo.observeWithImages(draftId).first()?.images).hasSize(1)
        assertThat(repo.observeWithImages(draftId).first()?.images?.single()?.id).isEqualTo("photo1")
        assertThat(repo.observeWithImages(draftId).first()?.images?.single()?.originalPhotoPath)
            .isEqualTo(imageFile.absolutePath)
        assertThat(imageFile.exists()).isTrue()

        repo.deleteDraft(draftId)

        assertThat(db.photoDrafts().getById(draftId)).isNull()
        assertThat(db.photoDraftImages().listForDraft(draftId)).isEmpty()
        assertThat(imageFile.exists()).isFalse()
    }

    @Test
    fun `observe summaries returns newest first with first thumbnail and count`() = runTest(testScheduler) {
        val oldId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val newId = repo.createDraft(2L, latitude = 10.0, longitude = 20.0, accuracyMeters = 5f)
        val firstPhoto = fileStore.newPhotoFile(newId, "first").apply { writeText("jpeg") }
        val secondPhoto = fileStore.newPhotoFile(newId, "second").apply { writeText("jpeg") }

        repo.addImage(
            draftId = newId,
            photoId = "first",
            imageFile = firstPhoto,
            takenAtUtcMs = 3L,
            width = 100,
            height = 100,
        )
        repo.addImage(
            draftId = newId,
            photoId = "second",
            imageFile = secondPhoto,
            takenAtUtcMs = 4L,
            width = 200,
            height = 200,
        )

        val summaries = repo.observeSummaries().first()

        assertThat(summaries.map { it.id }).containsExactly(newId, oldId).inOrder()
        assertThat(summaries.first().firstPhotoPath).isEqualTo(firstPhoto.absolutePath)
        assertThat(summaries.first().photoCount).isEqualTo(2)
        assertThat(summaries.first().latitude).isEqualTo(10.0)
        assertThat(summaries.first().locationAccuracyMeters).isEqualTo(5f)
        assertThat(db.photoDraftImages().listForDraft(newId).map { it.id }).containsExactly("first", "second").inOrder()
    }

    @Test
    fun `observeSummaries reacts to image changes on a single subscription`() = runTest(testScheduler) {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val p1 = fileStore.newPhotoFile(draftId, "p1").apply { writeText("jpeg") }
        val p2 = fileStore.newPhotoFile(draftId, "p2").apply { writeText("jpeg") }
        repo.addImage(draftId, "p1", p1, takenAtUtcMs = 1L, width = 10, height = 10)
        repo.addImage(draftId, "p2", p2, takenAtUtcMs = 2L, width = 10, height = 10)

        assertThat(repo.observeSummaries().first().single().photoCount).isEqualTo(2)

        // Delete one image. A correct reactive flow must reflect the new count
        // WITHOUT re-subscribing (i.e. a freshly collected first() must be 1).
        repo.deleteImage("p2")

        val after = repo.observeSummaries().first().single()
        assertThat(after.photoCount).isEqualTo(1)
        assertThat(after.firstPhotoPath).isEqualTo(p1.absolutePath)
    }

    @Test
    fun `single observeSummaries subscription emits again when only images change`() = runTest(testScheduler) {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val p1 = fileStore.newPhotoFile(draftId, "p1").apply { writeText("jpeg") }
        val p2 = fileStore.newPhotoFile(draftId, "p2").apply { writeText("jpeg") }
        repo.addImage(draftId, "p1", p1, takenAtUtcMs = 1L, width = 10, height = 10)
        repo.addImage(draftId, "p2", p2, takenAtUtcMs = 2L, width = 10, height = 10)

        val emissions = mutableListOf<Int>()
        val job = launch {
            repo.observeSummaries().collect { summaries ->
                emissions += summaries.firstOrNull()?.photoCount ?: 0
            }
        }
        runCurrent()
        assertThat(emissions.last()).isEqualTo(2)

        // deleteImage touches only the images table (no draft row update) — a
        // non-reactive map{} over draftDao.observeAll() would NOT re-emit here.
        repo.deleteImage("p2")
        runCurrent()

        assertThat(emissions.last()).isEqualTo(1) // only p1 remains
        job.cancel()
    }

    @Test
    fun `updateSyncedTaxon writes taxon names only`() = runTest(testScheduler) {
        val id = repo.createDraft(observedAtUtcMs = 100L, latitude = null, longitude = null, accuracyMeters = null)
        repo.updateDetails(
            id,
            taxonScientificName = "Old name",
            taxonCommonName = "Old common",
            taxonInatId = 42L,
            description = null,
        )
        val before = db.photoDrafts().getById(id)!!

        repo.updateSyncedTaxon(
            id,
            taxonScientificName = "Turdus merula",
            taxonCommonName = "Eurasian Blackbird",
        )

        val after = db.photoDrafts().getById(id)!!
        assertThat(after.taxonScientificName).isEqualTo("Turdus merula")
        assertThat(after.taxonCommonName).isEqualTo("Eurasian Blackbird")
        assertThat(after.status).isEqualTo(before.status)
        assertThat(after.updatedAtUtcMs).isEqualTo(before.updatedAtUtcMs)
        assertThat(after.taxonInatId).isEqualTo(before.taxonInatId)
    }

    @Test
    fun `updateSyncedTaxon is a no-op when values are unchanged`() = runTest(testScheduler) {
        val id = repo.createDraft(observedAtUtcMs = 100L, latitude = null, longitude = null, accuracyMeters = null)
        repo.updateDetails(
            id,
            taxonScientificName = "Turdus merula",
            taxonCommonName = "Eurasian Blackbird",
            taxonInatId = 1L,
            description = null,
        )
        val before = db.photoDrafts().getById(id)!!

        repo.updateSyncedTaxon(
            id,
            taxonScientificName = "Turdus merula",
            taxonCommonName = "Eurasian Blackbird",
        )

        val after = db.photoDrafts().getById(id)!!
        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `updateSyncedTaxon ignores a missing draft`() = runTest(testScheduler) {
        repo.updateSyncedTaxon("does-not-exist", taxonScientificName = "X", taxonCommonName = null)
    }
}
