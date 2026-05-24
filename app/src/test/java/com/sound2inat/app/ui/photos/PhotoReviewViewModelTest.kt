package com.sound2inat.app.ui.photos

import android.graphics.Bitmap
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.data.Settings
import com.sound2inat.inat.INatAuthRepository
import com.sound2inat.inat.INatTokenStore
import com.sound2inat.inat.INaturalistClient
import com.sound2inat.inat.PhotoAnnotationUseCase
import com.sound2inat.inat.PhotoSubmitResult
import com.sound2inat.inat.PhotoSubmitter
import com.sound2inat.inat.PhotoVisionLadder
import com.sound2inat.inat.PhotoVisionSuggestion
import com.sound2inat.inat.PhotoVisionTarget
import com.sound2inat.inat.PhotoVisionUseCase
import com.sound2inat.inat.SubmissionProgress
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoObservationFileStore
import com.sound2inat.storage.Sound2iNatDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
        assertThat(vm.state.value.isUploaded).isTrue()

        repo.updateDetails(draftId, "Quercus robur", null, 123L, "tree")

        assertThat(vm.state.value.uploadedUrl).isEqualTo("https://inat.test/observations/1")
        assertThat(vm.state.value.isUploaded).isTrue()
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
        assertThat(image.id).isEqualTo("p1")
        assertThat(image.originalPhotoPath).isEqualTo(fileStore.originalPhotoFile(draftId, "p1").absolutePath)
        assertThat(image.photoPath).isNotEqualTo(original.absolutePath)
        assertThat(image.width).isEqualTo(2)
        assertThat(image.height).isEqualTo(2)
        assertThat(original.exists()).isFalse()
        assertThat(File(image.originalPhotoPath).exists()).isTrue()
        assertThat(File(image.photoPath).exists()).isTrue()
    }

    @Test
    fun `cropping in original mode still applies the viewport crop`() = runTest {
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

        vm.cropImage(
            "p1",
            PhotoCropRequest(
                frameSizePx = 4,
                frameHeightPx = 2,
                scale = 2f,
                offsetX = 0f,
                offsetY = 0f,
                cropMode = PhotoCropMode.Original,
            ),
        )

        val image = vm.state.value.images.single()
        assertThat(image.cropLeftPx).isNull()
        assertThat(image.cropTopPx).isNull()
        assertThat(image.cropSizePx).isNull()
        assertThat(image.width).isEqualTo(2)
        assertThat(image.height).isEqualTo(1)
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
    fun `submit threads photo submission progress and clears it when done`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val releaseUpload = CompletableDeferred<Unit>()
        val submitter = object : PhotoSubmitter(client, repo, UnconfinedTestDispatcher()) {
            override suspend fun submit(
                token: String,
                draftId: String,
                onProgress: (SubmissionProgress) -> Unit,
            ): PhotoSubmitResult {
                onProgress(
                    SubmissionProgress.Species(
                        speciesIndex = 1,
                        totalSpecies = 1,
                        taxonScientificName = "Photo observation",
                        step = SubmissionProgress.Step.UploadingPrimaryPhoto,
                    ),
                )
                releaseUpload.await()
                return PhotoSubmitResult.Ok("https://inat.test/observations/1")
            }
        }
        val vm = viewModel(draftId, submitter = submitter)

        vm.submit()

        assertThat(vm.state.value.submissionProgress)
            .isInstanceOf(SubmissionProgress.Species::class.java)
        assertThat((vm.state.value.submissionProgress as SubmissionProgress.Species).step)
            .isEqualTo(SubmissionProgress.Step.UploadingPrimaryPhoto)

        releaseUpload.complete(Unit)

        assertThat(vm.state.value.submissionProgress).isNull()
    }

    @Test
    fun `loads incomplete photo upload into review state`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        repo.markIncompleteUpload(
            draftId = draftId,
            observationId = 42L,
            observationUuid = "uuid-42",
            observationUrl = "https://www.inaturalist.org/observations/42",
        )

        val vm = viewModel(draftId)

        assertThat(vm.state.value.incompleteObservation?.observationId).isEqualTo(42L)
        assertThat(vm.state.value.incompleteObservation?.url)
            .isEqualTo("https://www.inaturalist.org/observations/42")
    }

    @Test
    fun `retryIncomplete deletes remote observation then clears local incomplete upload`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        repo.markIncompleteUpload(
            draftId = draftId,
            observationId = 42L,
            observationUuid = "uuid-42",
            observationUrl = "https://www.inaturalist.org/observations/42",
        )
        server.enqueue(observationDetailResponse())
        server.enqueue(MockResponse().setResponseCode(204))
        val vm = viewModel(
            draftId = draftId,
            auth = fakeAuth(token = null, validToken = "fresh-token"),
        )

        vm.retryIncomplete()

        val request = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }
            .firstOrNull { it.method == "DELETE" }
        assertThat(request?.method).isEqualTo("DELETE")
        assertThat(request?.path).isEqualTo("/v1/observations/42")
        assertThat(repo.observeIncomplete(draftId).first()).isNull()
        assertThat(vm.state.value.retryIncompleteError).isNull()
    }

    @Test
    fun `retryIncomplete without token preserves row and surfaces error`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        repo.markIncompleteUpload(
            draftId = draftId,
            observationId = 42L,
            observationUuid = "uuid-42",
            observationUrl = "https://www.inaturalist.org/observations/42",
        )
        server.enqueue(observationDetailResponse())
        val vm = viewModel(
            draftId = draftId,
            auth = fakeAuth(token = null, validToken = null),
        )

        vm.retryIncomplete()

        assertThat(repo.observeIncomplete(draftId).first()).isNotNull()
        assertThat(vm.state.value.retryIncompleteError).contains("No iNaturalist token")
    }

    @Test
    fun `retryIncomplete delete failure preserves row and surfaces error`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        repo.markIncompleteUpload(
            draftId = draftId,
            observationId = 42L,
            observationUuid = "uuid-42",
            observationUrl = "https://www.inaturalist.org/observations/42",
        )
        server.enqueue(observationDetailResponse())
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        val vm = viewModel(
            draftId = draftId,
            auth = fakeAuth(token = null, validToken = "fresh-token"),
        )

        vm.retryIncomplete()

        val request = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }
            .firstOrNull { it.method == "DELETE" }
        assertThat(request?.path).isEqualTo("/v1/observations/42")
        assertThat(repo.observeIncomplete(draftId).first()).isNotNull()
        assertThat(vm.state.value.retryIncompleteError).isNotNull()
    }

    @Test
    fun `load vision suggestions and apply species via fake use cases`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        repo.markIncompleteUpload(
            draftId,
            observationId = 777L,
            observationUuid = "uuid-777",
            observationUrl = "https://www.inaturalist.org/observations/777",
        )
        repo.markPhotoUploadComplete(draftId)
        // getObservation is still called via client (syncObservationDetails)
        server.enqueue(
            MockResponse().setBody(
                """{
                  "results": [
                    {
                      "quality_grade": "needs_id",
                      "comments_count": 0,
                      "identifications": [],
                      "comments": [],
                      "taxon": null
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val fakeLadder = PhotoVisionLadder(
            topCandidates = listOf(
                PhotoVisionSuggestion(
                    taxonId = 102L,
                    scientificName = "Ammophila sabulosa",
                    commonName = "Sand wasp",
                    rank = "species",
                    rankLevel = 10,
                    score = 0.71,
                    iconicTaxonName = "Insecta",
                ),
            ),
            higherTaxa = emptyList(),
        )
        var visionCalledWithObservationId: Long? = null
        var identificationCalledWithTaxonId: Long? = null
        var annotationsCalledWithUuid: String? = null

        val visionUseCase = object : PhotoVisionUseCase(client) {
            override suspend fun scoreSuggestions(token: String, observationId: Long): PhotoVisionLadder {
                visionCalledWithObservationId = observationId
                return fakeLadder
            }
        }
        val annotationUseCase = object : PhotoAnnotationUseCase(client) {
            override suspend fun addIdentification(
                token: String,
                observationId: Long,
                suggestion: PhotoVisionSuggestion,
            ) {
                identificationCalledWithTaxonId = suggestion.taxonId
            }

            override suspend fun applyAnnotations(
                token: String,
                observationUuid: String,
                suggestion: PhotoVisionSuggestion,
            ) {
                annotationsCalledWithUuid = observationUuid
            }
        }

        val vm = viewModel(
            draftId,
            visionUseCase = visionUseCase,
            annotationUseCase = annotationUseCase,
            submitter = fakeSubmitter { _, _ -> error("submit not expected") },
        )

        vm.loadVisionSuggestions()

        repeat(50) {
            if (vm.state.value.vision.ladder != null) return@repeat
            delay(10)
        }
        assertThat(vm.state.value.vision.ladder).isNotNull()
        assertThat(visionCalledWithObservationId).isEqualTo(777L)

        vm.applyVision(PhotoVisionTarget.SPECIES)

        repeat(50) {
            if (vm.state.value.vision.message != null) return@repeat
            delay(10)
        }
        assertThat(vm.state.value.vision.message).isNotNull()
        assertThat(identificationCalledWithTaxonId).isEqualTo(102L)
        assertThat(annotationsCalledWithUuid).isEqualTo("uuid-777")
        assertThat(vm.state.value.taxonScientificName).isEqualTo("Ammophila sabulosa")
        assertThat(vm.state.value.taxonCommonName).isEqualTo("Sand wasp")
        assertThat(vm.state.value.taxonInatId).isEqualTo(102L)
    }

    @Test
    fun `load vision suggestions without upload shows friendly prompt`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val vm = viewModel(draftId)

        vm.loadVisionSuggestions()

        assertThat(vm.state.value.vision.error).isEqualTo(
            "Upload this photo observation before requesting iNaturalist suggestions.",
        )
    }

    @Test
    fun `opened uploaded draft auto syncs observation details and comments`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        repo.markIncompleteUpload(
            draftId,
            observationId = 777L,
            observationUuid = "uuid-777",
            observationUrl = "https://www.inaturalist.org/observations/777",
        )
        repo.markPhotoUploadComplete(draftId)
        server.enqueue(
            MockResponse().setBody(
                """{
                  "results": [
                    {
                      "quality_grade": "research",
                      "comments_count": 2,
                      "identifications": [
                        {"current": true},
                        {"current": false}
                      ],
                      "comments": [
                        {"user": {"login": "alice"}, "body": "Nice find"},
                        {"user": {"login": "bob"}, "body": "Agree"}
                      ],
                      "taxon": {
                        "name": "Ammophila sabulosa",
                        "preferred_common_name": "Sand wasp"
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val vm = viewModel(draftId)

        repeat(50) {
            if (vm.state.value.observationDetail != null) return@repeat
            delay(10)
        }

        val detail = vm.state.value.observationDetail
        assertThat(detail).isNotNull()
        assertThat(detail?.qualityGrade).isEqualTo("research")
        assertThat(detail?.agreeingIdCount).isEqualTo(1)
        assertThat(detail?.commentsCount).isEqualTo(2)
        assertThat(detail?.comments).hasSize(2)
        assertThat(detail?.taxonScientificName).isEqualTo("Ammophila sabulosa")
        assertThat(detail?.taxonCommonName).isEqualTo("Sand wasp")
        assertThat(vm.state.value.syncError).isNull()
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)?.path).isEqualTo("/v1/observations/777")
    }

    @Test
    fun `apply vision refuses annotations when observation uuid is missing`() = runTest {
        val draftId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        repo.markIncompleteUpload(
            draftId,
            observationId = 777L,
            observationUuid = "",
            observationUrl = "https://www.inaturalist.org/observations/777",
        )
        repo.markPhotoUploadComplete(draftId)
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
        submitter: PhotoSubmitter = PhotoSubmitter(client, repo),
        cropper: PhotoImageCropper = PhotoImageCropper(),
        visionUseCase: PhotoVisionUseCase = PhotoVisionUseCase(client),
        annotationUseCase: PhotoAnnotationUseCase = PhotoAnnotationUseCase(client),
    ): PhotoReviewViewModel = PhotoReviewViewModel(
        savedStateHandle = SavedStateHandle(mapOf("photoDraftId" to draftId)),
        repo = repo,
        fileStore = fileStore,
        auth = auth,
        client = client,
        submitter = submitter,
        cropper = cropper,
        visionUseCase = visionUseCase,
        annotationUseCase = annotationUseCase,
        externalScope = TestScope(UnconfinedTestDispatcher()),
    )

    private fun fakeSubmitter(
        block: suspend (String, String) -> PhotoSubmitResult,
    ): PhotoSubmitter = object : PhotoSubmitter(INaturalistClient(OkHttpClient()), repo) {
        override suspend fun submit(
            token: String,
            draftId: String,
            onProgress: (com.sound2inat.inat.SubmissionProgress) -> Unit,
        ): PhotoSubmitResult = block(token, draftId)
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

    private fun observationDetailResponse(): MockResponse =
        MockResponse().setBody(
            """{
              "results": [
                {
                  "quality_grade": "needs_id",
                  "comments_count": 0,
                  "identifications": [],
                  "comments": [],
                  "taxon": null
                }
              ]
            }
            """.trimIndent(),
        )
}
