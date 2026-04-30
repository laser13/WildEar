package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
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

// Robolectric supplies a real org.json — the Android stub in android.jar
// returns null from JSONObject.put(...), which breaks every JSON round-trip.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class INaturalistClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: INaturalistClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = INaturalistClient(OkHttpClient(), baseUrl = server.url("/v1").toString().trimEnd('/'))
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `verifyToken returns login on 200`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"login":"alice"}]}"""))
        val login = client.verifyToken("jwt")
        assertThat(login).isEqualTo("alice")
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/users/me")
        assertThat(req.getHeader("Authorization")).isEqualTo("jwt")
    }

    @Test fun `verifyToken throws INatException on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        val ex = runCatching { client.verifyToken("bad") }.exceptionOrNull()
        assertThat(ex).isInstanceOf(INatException::class.java)
        assertThat((ex as INatException).code).isEqualTo(401)
    }

    @Test fun `resolveTaxon returns first result id when iconic taxon is animal`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"id":12345,"name":"Parus major","rank":"species","iconic_taxon_name":"Aves"}]}""",
            ),
        )
        val id = client.resolveTaxon("Parus major", token = null)
        assertThat(id).isEqualTo(12345L)
        val req = server.takeRequest()
        assertThat(req.path).contains("q=Parus+major")
        assertThat(req.path).contains("rank=species")
    }

    @Test fun `resolveTaxon returns null when no results`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val id = client.resolveTaxon("Nonexistent species", token = null)
        assertThat(id).isNull()
    }

    @Test fun `resolveTaxon rejects non-animal hit (Plantae)`() = runTest {
        // Real-world example: querying "Fireworks" matches Solidago rugosa,
        // a plant whose common name set includes "Fireworks". We must NOT
        // upload a sound under a Plantae taxon.
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"id":54321,"iconic_taxon_name":"Plantae"}]}""",
            ),
        )
        val id = client.resolveTaxon("Fireworks", token = null)
        assertThat(id).isNull()
    }

    @Test fun `createObservation posts wrapped JSON and parses id and url`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":98765,"uuid":"abc-uuid"}"""))
        val res = client.createObservation(
            "jwt",
            ObservationBody(
                observedAtIso = "2026-04-30T12:00:00+0000",
                latitude = 35.16,
                longitude = 33.36,
                positionalAccuracy = 5f,
                taxonId = 7L,
                description = "test",
                licenseCode = "cc-by-nc",
            ),
        )
        assertThat(res.id).isEqualTo(98765L)
        assertThat(res.url).isEqualTo("https://www.inaturalist.org/observations/abc-uuid")
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/v1/observations")
        assertThat(req.getHeader("Authorization")).isEqualTo("jwt")
        val body = req.body.readUtf8()
        // Wrapper key + nested taxon_id present.
        assertThat(body).contains("\"observation\"")
        assertThat(body).contains("\"taxon_id\":7")
        assertThat(body).contains("\"latitude\":35.16")
        assertThat(body).contains("\"ignore_photos\":true")
    }

    @Test fun `createObservation handles legacy array response`() = runTest {
        // Some iNat endpoints wrap a single object in a top-level array.
        server.enqueue(MockResponse().setBody("""[{"id":11,"uuid":""}]"""))
        val res = client.createObservation(
            "jwt",
            ObservationBody(null, null, null, null, taxonId = 1L, description = null, licenseCode = null),
        )
        assertThat(res.id).isEqualTo(11L)
        // No uuid → falls back to numeric id in the public URL.
        assertThat(res.url).isEqualTo("https://www.inaturalist.org/observations/11")
    }

    @Test fun `uploadSound posts multipart to v2 with file field and uuid`() = runTest {
        val wav = tmp.newFile("clip.wav").apply { writeBytes(ByteArray(64)) }
        // v2 wraps single resources in { results: [...] }.
        server.enqueue(MockResponse().setBody("""{"results":[{"id":42}]}"""))
        val soundId = client.uploadSound("jwt", observationUuid = "abc-uuid", audioFile = wav)
        assertThat(soundId).isEqualTo(42L)
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/v2/observation_sounds")
        val body = req.body.readUtf8()
        assertThat(body).contains("observation_sound[observation_id]")
        assertThat(body).contains("abc-uuid")
        // v2 uses `file`, not `audio` (audio was the v1 legacy controller field).
        assertThat(body).contains("name=\"file\"")
        assertThat(body).contains(wav.name)
    }

    @Test fun `uploadSound rejects missing file before any HTTP call`() = runTest {
        val ex = runCatching {
            client.uploadSound("jwt", "abc-uuid", File(tmp.root, "missing.wav"))
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `addIdentification posts wrapped JSON`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":7}"""))
        client.addIdentification("jwt", observationId = 99L, taxonId = 5L, body = "agree")
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/identifications")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"identification\"")
        assertThat(body).contains("\"observation_id\":99")
        assertThat(body).contains("\"taxon_id\":5")
        assertThat(body).contains("\"body\":\"agree\"")
    }

    @Test
    fun `hasObservationsNear returns true when total_results is positive`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"results":[{"id":1}],"total_results":1}"""),
        )
        val found = client.hasObservationsNear("Parus major", 55.75, 37.62, 200)
        assertThat(found).isTrue()
        val req = server.takeRequest()
        assertThat(req.path).contains("taxon_name=Parus+major")
        assertThat(req.path).contains("lat=55.75")
        assertThat(req.path).contains("lng=37.62")
        assertThat(req.path).contains("radius=200")
        assertThat(req.path).contains("per_page=1")
    }

    @Test
    fun `hasObservationsNear returns false when total_results is zero`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"results":[],"total_results":0}"""),
        )
        val found = client.hasObservationsNear("Gnorimopsar chopi", 55.75, 37.62, 200)
        assertThat(found).isFalse()
    }

    @Test
    fun `hasObservationsNear returns true on network error (fail-open)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        val found = client.hasObservationsNear("Parus major", 55.75, 37.62, 200)
        assertThat(found).isTrue()
    }
}
