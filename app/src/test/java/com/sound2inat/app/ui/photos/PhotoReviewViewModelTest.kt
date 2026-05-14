package com.sound2inat.app.ui.photos

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.graphics.Bitmap
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoReviewViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val instant = InstantTaskExecutorRule()

    private lateinit var server: MockWebServer
    private lateinit var db: Sound2iNatDb
    private lateinit var fileStore: PhotoObservationFileStore
    private lateinit var repo: PhotoDraftRepository
    private lateinit var client: INaturalistClient
    private var nextId = 0

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
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
        client = INaturalistClient(
            OkHttpClient(),
            baseUrl = server.url("/v1").toString().removeSuffix("/"),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun `review loads draft images and can delete one image`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val image = fileStore.newPhotoFile(draftId, "p1").apply { writeText("jpeg") }
        repo.addImage(
            draftId = draftId,
            photoId = "p1",
            imageFile = image,
            takenAtUtcMs = 2L,
            width = 100,
            height = 100,
        )
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
    fun `cropping an image replaces it with a new file and keeps original on disk`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val original = fileStore.newPhotoFile(draftId, "p1").apply {
            parentFile?.mkdirs()
            outputStream().use { out ->
                Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        }
        repo.addImage(
            draftId = draftId,
            photoId = "p1",
            imageFile = original,
            takenAtUtcMs = 2L,
            width = 4,
            height = 2,
        )
        val vm = viewModel(draftId)

        vm.cropImageSquare("p1")

        val image = vm.state.value.images.single()
        assertThat(image.id).isNotEqualTo("p1")
        assertThat(image.width).isEqualTo(2)
        assertThat(image.height).isEqualTo(2)
        assertThat(original.exists()).isTrue()
        assertThat(File(image.photoPath).exists()).isTrue()
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

    @Test
    fun `load vision suggestions and apply genus from iNat response`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        repo.markUploaded(
            draftId,
            observationId = 777L,
            observationUuid = "uuid-777",
            observationUrl = "https://www.inaturalist.org/observations/777",
        )
        server.enqueue(
            MockResponse().setBody(
                """{
                  "results": [
                    {
                      "combined_score": 0.82,
                      "taxon": {
                        "id": 101,
                        "name": "Ammophila",
                        "preferred_common_name": null,
                        "rank": "genus",
                        "rank_level": 20,
                        "ancestry": "1/2/101",
                        "iconic_taxon_name": "Insecta"
                      }
                    },
                    {
                      "combined_score": 0.71,
                      "taxon": {
                        "id": 102,
                        "name": "Ammophila sabulosa",
                        "preferred_common_name": "Sand wasp",
                        "rank": "species",
                        "rank_level": 10,
                        "ancestry": "1/2/101/102",
                        "iconic_taxon_name": "Insecta"
                      }
                    }
                  ]
                }""".trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                    {"id":1,"name":"Animalia","rank":"kingdom","rank_level":70,"iconic_taxon_name":"Animalia"},
                    {"id":2,"name":"Vespidae","preferred_common_name":"Paper wasps","rank":"family","rank_level":30,"iconic_taxon_name":"Insecta"},
                    {"id":101,"name":"Ammophila","rank":"genus","rank_level":20,"iconic_taxon_name":"Insecta"},
                    {"id":102,"name":"Ammophila sabulosa","rank":"species","rank_level":10,"iconic_taxon_name":"Insecta"}
                ]}""".trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setBody("""{"id":1}"""))
        server.enqueue(MockResponse().setBody("""{"id":2}"""))
        server.enqueue(MockResponse().setBody("""{"id":3}"""))

        val vm = viewModel(
            draftId,
            client = client,
            submitter = fakeSubmitter { _, _ -> error("submit not expected") },
        )

        vm.loadVisionSuggestions()

        repeat(50) {
            if (vm.state.value.vision.ladder != null) return@repeat
            delay(10)
        }
        assertThat(vm.state.value.vision.ladder).isNotNull()

        vm.applyVision(PhotoVisionTarget.GENUS)

        repeat(50) {
            if (vm.state.value.vision.message != null) return@repeat
            delay(10)
        }
        assertThat(vm.state.value.vision.message).isNotNull()
        assertThat(vm.state.value.taxonScientificName).isEqualTo("Ammophila")
        assertThat(vm.state.value.taxonInatId).isEqualTo(101L)
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)?.path).isEqualTo("/v1/computervision/score_observation/777")
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)?.path).contains("/v1/taxa?id=1,2,101,102")
        val identificationRequest = server.takeRequest(5, TimeUnit.SECONDS)
        val identificationBody = identificationRequest?.body?.readUtf8().orEmpty()
        assertThat(identificationRequest?.path).isEqualTo("/v1/identifications")
        assertThat(identificationBody).contains("\"observation_id\":777")
        assertThat(identificationBody).doesNotContain("WildEar CV")
        assertThat(identificationBody).doesNotContain("\"body\"")
    }

    @Test
    fun `apply vision refuses annotations when observation uuid is missing`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        repo.markUploaded(
            draftId,
            observationId = 777L,
            observationUuid = "",
            observationUrl = "https://www.inaturalist.org/observations/777",
        )
        val vm = viewModel(draftId)

        vm.applyVisionSuggestion(
            PhotoVisionSuggestion(
                taxonId = 101L,
                scientificName = "Ammophila",
                commonName = null,
                rank = "genus",
                rankLevel = 20,
                score = 0.82,
                iconicTaxonName = "Insecta",
            ),
        )

        assertThat(vm.state.value.vision.error).contains("Missing iNaturalist observation UUID")
    }

    private fun viewModel(
        draftId: String,
        auth: INatAuthRepository = fakeAuth(),
        client: INaturalistClient = this.client,
        submitter: PhotoSubmitter = PhotoSubmitter(client, repo),
        cropper: PhotoImageCropper = PhotoImageCropper(),
    ): PhotoReviewViewModel = PhotoReviewViewModel(
        savedStateHandle = SavedStateHandle(mapOf("photoDraftId" to draftId)),
        repo = repo,
        auth = auth,
        client = client,
        submitter = submitter,
        cropper = cropper,
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
