package com.sound2inat.inat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoObservationFileStore
import com.sound2inat.storage.Sound2iNatDb
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

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PhotoSubmitterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: Sound2iNatDb
    private lateinit var repo: PhotoDraftRepository
    private lateinit var submitter: PhotoSubmitter
    private lateinit var fileStore: PhotoObservationFileStore
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
        submitter = PhotoSubmitter(
            client = INaturalistClient(
                OkHttpClient(),
                baseUrl = server.url("/v1").toString().removeSuffix("/"),
                ioDispatcher = UnconfinedTestDispatcher(),
            ),
            repo = repo,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun `uploading a photo draft creates one observation and uploads every image`() = runTest {
        val draftId = draftWithImages(2)
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":2}]}"""))

        val result = submitter.submit("jwt", draftId)

        assertThat(result).isInstanceOf(PhotoSubmitResult.Ok::class.java)
        val saved = db.photoDrafts().getById(draftId)!!
        assertThat(saved.inatObservationId).isEqualTo(900L)
        assertThat(saved.inatObservationUrl).isEqualTo("https://www.inaturalist.org/observations/900")
        assertThat(server.takeRequest().path).isEqualTo("/v1/observations")
        assertThat(server.takeRequest().path).isEqualTo("/v2/observation_photos")
        assertThat(server.takeRequest().path).isEqualTo("/v2/observation_photos")
    }

    @Test
    fun `blank token fails before network`() = runTest {
        val draftId = draftWithImages(1)

        val result = submitter.submit("", draftId)

        assertThat(result).isInstanceOf(PhotoSubmitResult.Failure::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `all photo upload failures clean up created observation`() = runTest {
        val draftId = draftWithImages(1)
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        val result = submitter.submit("jwt", draftId)

        assertThat(result).isInstanceOf(PhotoSubmitResult.Failure::class.java)
        assertThat(db.photoDrafts().getById(draftId)!!.inatLastError).contains("All photo uploads failed")
        assertThat(server.takeRequest().path).isEqualTo("/v1/observations")
        assertThat(server.takeRequest().path).isEqualTo("/v2/observation_photos")
        assertThat(server.takeRequest().path).isEqualTo("/v1/observations/900")
    }

    private suspend fun draftWithImages(count: Int): String {
        val draftId = repo.createDraft(1L, latitude = 35.1, longitude = 33.3, accuracyMeters = 5f)
        repeat(count) { idx ->
            val file = fileStore.newPhotoFile(draftId, "p$idx").apply { writeText("jpeg") }
            repo.addImage(draftId, file, takenAtUtcMs = idx.toLong(), width = 100, height = 100)
        }
        return draftId
    }
}
