package com.sound2inat.app.ui.photos

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.data.Settings
import com.sound2inat.inat.INatAuthRepository
import com.sound2inat.inat.INatTokenStore
import com.sound2inat.inat.INaturalistClient
import com.sound2inat.inat.PhotoSubmitResult
import com.sound2inat.inat.PhotoSubmitter
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoObservationFileStore
import com.sound2inat.storage.Sound2iNatDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `successful upload state survives later draft emissions`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val submitter = fakeSubmitter { _, _ -> PhotoSubmitResult.Ok("https://inat.test/observations/1") }
        val vm = viewModel(draftId, submitter = submitter)

        vm.submit()
        assertThat(vm.state.value.uploadedUrl).isEqualTo("https://inat.test/observations/1")

        repo.updateDetails(draftId, "Quercus robur", null, 123L, "tree")

        assertThat(vm.state.value.uploadedUrl).isEqualTo("https://inat.test/observations/1")
    }

    @Test
    fun `submit ignores duplicate calls while upload is in progress`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val releaseUpload = CompletableDeferred<Unit>()
        var calls = 0
        val submitter = fakeSubmitter { _, _ ->
            calls++
            releaseUpload.await()
            PhotoSubmitResult.Ok("https://inat.test/observations/$calls")
        }
        val vm = viewModel(draftId, submitter = submitter)

        vm.submit()
        vm.submit()

        assertThat(calls).isEqualTo(1)
        releaseUpload.complete(Unit)
    }

    @Test
    fun `submit uses refreshed auth token`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        var submittedToken: String? = null
        val submitter = fakeSubmitter { token, _ ->
            submittedToken = token
            PhotoSubmitResult.Ok("https://inat.test/observations/1")
        }
        val vm = viewModel(
            draftId = draftId,
            auth = fakeAuth(token = null, validToken = "fresh-jwt"),
            submitter = submitter,
        )

        vm.submit()

        assertThat(submittedToken).isEqualTo("fresh-jwt")
    }

    private fun viewModel(
        draftId: String,
        auth: INatAuthRepository = fakeAuth(),
        submitter: PhotoSubmitter = PhotoSubmitter(INaturalistClient(OkHttpClient()), repo),
    ): PhotoReviewViewModel = PhotoReviewViewModel(
        savedStateHandle = SavedStateHandle(mapOf("photoDraftId" to draftId)),
        repo = repo,
        auth = auth,
        submitter = submitter,
        externalScope = TestScope(UnconfinedTestDispatcher()),
    )

    private fun fakeSubmitter(
        block: suspend (String, String) -> PhotoSubmitResult,
    ): PhotoSubmitter = object : PhotoSubmitter(INaturalistClient(OkHttpClient()), repo) {
        override suspend fun submit(token: String, draftId: String): PhotoSubmitResult =
            block(token, draftId)
    }

    private fun fakeAuth(
        token: String? = "jwt",
        validToken: String? = token,
    ): INatAuthRepository = object : INatAuthRepository(
        context = ApplicationProvider.getApplicationContext(),
        storage = object : INatTokenStore {
            override val token: String? = token
            override val tokenFetchedAtUtcMs: Long = 0L
            override val login: String? = null
            override val userId: Long? = null
            override fun save(token: String, login: String?, userId: Long?, fetchedAtUtcMs: Long) = Unit
            override fun clear() = Unit
        },
        settings = Settings(ApplicationProvider.getApplicationContext()),
        client = INaturalistClient(OkHttpClient()),
    ) {
        override suspend fun getValidToken(refreshDispatcher: kotlinx.coroutines.CoroutineDispatcher): String? =
            validToken
    }
}
