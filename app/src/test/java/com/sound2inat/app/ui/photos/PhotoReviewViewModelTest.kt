package com.sound2inat.app.ui.photos

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoObservationFileStore
import com.sound2inat.storage.Sound2iNatDb
import kotlinx.coroutines.test.TestScope
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
class PhotoReviewViewModelTest {
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
    fun `review loads draft images and can delete one image`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val image = fileStore.newPhotoFile(draftId, "p1").apply { writeText("jpeg") }
        repo.addImage(draftId, image, takenAtUtcMs = 2L, width = 100, height = 100)
        val vm = viewModel(draftId)

        assertThat(vm.state.value.images).hasSize(1)
        val imageId = vm.state.value.images.single().id

        vm.deleteImage(imageId)

        assertThat(vm.state.value.images).isEmpty()
        assertThat(image.exists()).isFalse()
    }

    @Test
    fun `save details updates review state`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val vm = viewModel(draftId)

        vm.saveDetails("Quercus robur", "English Oak", 123L, "old tree")

        assertThat(vm.state.value.taxonScientificName).isEqualTo("Quercus robur")
        assertThat(vm.state.value.description).isEqualTo("old tree")
    }

    private fun viewModel(draftId: String): PhotoReviewViewModel = PhotoReviewViewModel(
        savedStateHandle = SavedStateHandle(mapOf("photoDraftId" to draftId)),
        repo = repo,
        externalScope = TestScope(UnconfinedTestDispatcher()),
    )
}
