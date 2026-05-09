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
        server.enqueue(MockResponse().setBody("""{"results":[{"id":42,"login":"alice"}]}"""))
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
        assertThat(res.url).isEqualTo("https://www.inaturalist.org/observations/98765")
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
    fun `hasObservationsNear returns true when at least one observation found`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":5,"name":"Parus major"}]}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}],"total_results":1}"""))
        val found = client.hasObservationsNear("Parus major", 55.75, 37.62, 200)
        assertThat(found).isTrue()
        server.takeRequest() // taxa lookup
        val obsReq = server.takeRequest()
        assertThat(obsReq.path).contains("taxon_id=5")
        assertThat(obsReq.path).contains("lat=55.75")
        assertThat(obsReq.path).contains("lng=37.62")
        assertThat(obsReq.path).contains("radius=200")
        assertThat(obsReq.path).contains("quality_grade=research")
        assertThat(obsReq.path).contains("per_page=1")
    }

    @Test
    fun `hasObservationsNear returns false when no observations found`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":7,"name":"Gnorimopsar chopi"}]}"""))
        server.enqueue(MockResponse().setBody("""{"results":[],"total_results":0}"""))
        val found = client.hasObservationsNear("Gnorimopsar chopi", 55.75, 37.62, 200)
        assertThat(found).isFalse()
    }

    @Test
    fun `hasObservationsNear returns true on network error (fail-open)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("error")) // taxa lookup fails
        val found = client.hasObservationsNear("Parus major", 55.75, 37.62, 200)
        assertThat(found).isTrue()
    }

    @Test fun `verifyTokenWithUser returns login and id`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"results":[{"id":42,"login":"alice"}]}"""),
        )
        val (login, id) = client.verifyTokenWithUser("jwt")
        assertThat(login).isEqualTo("alice")
        assertThat(id).isEqualTo(42L)
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/users/me")
        assertThat(req.getHeader("Authorization")).isEqualTo("jwt")
    }

    @Suppress("LongMethod")
    @Test
    fun `nearbySpeciesCounts URL has all params and parses response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{
                  "results": [
                    {
                      "count": 12,
                      "taxon": {
                        "id": 9083,
                        "name": "Regulus regulus",
                        "preferred_common_name": "Goldcrest",
                        "iconic_taxon_name": "Aves",
                        "default_photo": { "medium_url": "https://example/goldcrest.jpg" }
                      }
                    },
                    {
                      "count": 3,
                      "taxon": {
                        "id": 1234,
                        "name": "Rana temporaria",
                        "preferred_common_name": null,
                        "iconic_taxon_name": "Amphibia",
                        "default_photo": null
                      }
                    }
                  ]
                }""",
            ),
        )
        val key = com.sound2inat.app.ui.radar.FilterKey(
            latGrid = 5050,
            lonGrid = 1010,
            radiusKm = 5,
            periodDays = 7,
            taxa = setOf("Aves", "Amphibia"),
            excludeUserId = 42L,
        )
        val results = client.nearbySpeciesCounts(key, periodEndDateUtc = "2026-04-25")

        assertThat(results).hasSize(2)
        val first = results[0]
        assertThat(first.taxonId).isEqualTo(9083L)
        assertThat(first.scientificName).isEqualTo("Regulus regulus")
        assertThat(first.commonName).isEqualTo("Goldcrest")
        assertThat(first.iconicTaxon).isEqualTo("Aves")
        assertThat(first.photoUrl).isEqualTo("https://example/goldcrest.jpg")
        assertThat(first.observationCount).isEqualTo(12)
        // nearestObservationKm and nearestObservationUrl are populated by the
        // repository's join step; the client itself returns sentinel values.
        assertThat(first.nearestObservationKm).isEqualTo(-1f)
        assertThat(first.nearestObservationUrl).isEqualTo("https://www.inaturalist.org/taxa/9083")

        val req = server.takeRequest()
        val path = req.path!!
        assertThat(path).startsWith("/v1/observations/species_counts?")
        assertThat(path).contains("lat=50.5")
        assertThat(path).contains("lng=10.1")
        assertThat(path).contains("radius=5")
        assertThat(path).contains("d1=2026-04-25")
        assertThat(path).contains("iconic_taxa=")
        // The set order is non-deterministic, so the test asserts both items
        // are present rather than a specific order.
        assertThat(path).matches(".*iconic_taxa=(Aves%2CAmphibia|Amphibia%2CAves).*")
        assertThat(path).contains("not_user_id=42")
        assertThat(path).contains("quality_grade=research%2Cneeds_id")
        assertThat(path).contains("per_page=100")
        assertThat(path).contains("order_by=count")
    }

    @Test fun `nearbySpeciesCounts omits empty taxa and null user_id`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val key = com.sound2inat.app.ui.radar.FilterKey(
            latGrid = 5050,
            lonGrid = 1010,
            radiusKm = 25,
            periodDays = 30,
            taxa = emptySet(),
            excludeUserId = null,
        )
        client.nearbySpeciesCounts(key, periodEndDateUtc = "2026-04-02")
        val path = server.takeRequest().path!!
        assertThat(path).doesNotContain("iconic_taxa=")
        assertThat(path).doesNotContain("not_user_id=")
    }

    @Test fun `nearbyObservations URL has all params and parses response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{
              "results": [
                {
                  "id": 11122,
                  "uuid": "00000000-0000-0000-0000-00000000aaa1",
                  "taxon": { "id": 9083, "name": "Regulus regulus" },
                  "geojson": { "coordinates": [10.1, 50.5] }
                },
                {
                  "id": 22233,
                  "uuid": "00000000-0000-0000-0000-00000000aaa2",
                  "taxon": { "id": 1234, "name": "Rana temporaria" },
                  "geojson": { "coordinates": [10.105, 50.512] }
                },
                {
                  "id": 33344,
                  "uuid": "00000000-0000-0000-0000-00000000aaa3",
                  "taxon": null,
                  "geojson": { "coordinates": [10.11, 50.52] }
                }
              ]
            }""",
            ),
        )
        val key = com.sound2inat.app.ui.radar.FilterKey(
            latGrid = 5050,
            lonGrid = 1010,
            radiusKm = 5,
            periodDays = 7,
            taxa = setOf("Aves"),
            excludeUserId = null,
        )
        val pins = client.nearbyObservations(key, periodEndDateUtc = "2026-04-25")
        assertThat(pins).hasSize(2) // entry 3 has no taxon — skipped
        assertThat(pins[0].observationId).isEqualTo(11122L)
        assertThat(pins[0].taxonId).isEqualTo(9083L)
        assertThat(pins[0].lat).isEqualTo(50.5)
        assertThat(pins[0].lon).isEqualTo(10.1)
        assertThat(pins[0].obsUrl).isEqualTo(
            "https://www.inaturalist.org/observations/00000000-0000-0000-0000-00000000aaa1",
        )

        val path = server.takeRequest().path!!
        assertThat(path).startsWith("/v1/observations?")
        assertThat(path).contains("lat=50.5")
        assertThat(path).contains("lng=10.1")
        assertThat(path).contains("per_page=200")
        assertThat(path).contains("order_by=observed_on")
    }

    @Test fun `nearbyObservations uses numeric id in obsUrl when uuid absent`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{
                  "results": [{
                    "id": 99,
                    "uuid": "",
                    "taxon": { "id": 1, "name": "Passer domesticus" },
                    "geojson": { "coordinates": [10.0, 50.0] }
                  }]
                }""",
            ),
        )
        val key = com.sound2inat.app.ui.radar.FilterKey(
            latGrid = 5000,
            lonGrid = 1000,
            radiusKm = 5,
            periodDays = 7,
            taxa = emptySet(),
            excludeUserId = null,
        )
        val pins = client.nearbyObservations(key, periodEndDateUtc = "2026-04-25")
        assertThat(pins).hasSize(1)
        assertThat(pins[0].obsUrl).isEqualTo("https://www.inaturalist.org/observations/99")
    }

    @Test fun `getNearbyCountryPlaces returns all country-level place ids`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":{"standard":[{"id":96773,"name":"Northern Cyprus","admin_level":0},{"id":10289,"name":"Cyprus","admin_level":0}],"community":[]}}""",
            ),
        )
        val placeIds = client.getNearbyCountryPlaces(34.9, 33.1)
        assertThat(placeIds).containsExactly(96773L, 10289L).inOrder()
        val req = server.takeRequest()
        assertThat(req.path).contains("places/nearby")
        assertThat(req.path).contains("no_geojson=true")
        assertThat(req.path).contains("nelat=35.9")
        assertThat(req.path).contains("swlat=33.9")
    }

    @Test fun `getNearbyCountryPlaces skips continents`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":{"standard":[{"id":97395,"name":"Asia","admin_level":-10},{"id":7257,"name":"Cyprus","admin_level":0}],"community":[]}}""",
            ),
        )
        val placeIds = client.getNearbyCountryPlaces(34.9, 33.1)
        assertThat(placeIds).containsExactly(7257L)
    }

    @Test fun `getNearbyCountryPlaces returns empty when no standard places`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"results":{"standard":[],"community":[]}}"""),
        )
        val placeIds = client.getNearbyCountryPlaces(34.9, 33.1)
        assertThat(placeIds).isEmpty()
    }

    @Test fun `getNearbyCountryPlaces returns empty when only continents available`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":{"standard":[{"id":97395,"name":"Asia","admin_level":-10}],"community":[]}}""",
            ),
        )
        val placeIds = client.getNearbyCountryPlaces(34.9, 33.1)
        assertThat(placeIds).isEmpty()
    }

    @Test fun `hasObservationsInPlaces returns true when found in first place`() = runTest {
        // taxon lookup, then observations
        server.enqueue(MockResponse().setBody("""{"results":[{"id":5,"name":"Parus major"}]}"""))
        server.enqueue(MockResponse().setBody("""{"results":[],"total_results":1}"""))
        val found = client.hasObservationsInPlaces("Parus major", listOf(7257L))
        assertThat(found).isTrue()
        server.takeRequest() // taxa
        val obsReq = server.takeRequest()
        assertThat(obsReq.path).contains("taxon_id=5")
        assertThat(obsReq.path).contains("place_id=7257")
        assertThat(obsReq.path).contains("quality_grade=research")
        assertThat(obsReq.path).contains("per_page=1")
    }

    @Test fun `hasObservationsInPlaces stops at first match — does not check remaining places`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":5,"name":"Parus major"}]}"""))
        server.enqueue(MockResponse().setBody("""{"results":[],"total_results":1}"""))
        val found = client.hasObservationsInPlaces("Parus major", listOf(7257L, 96773L))
        assertThat(found).isTrue()
        assertThat(server.requestCount).isEqualTo(2) // taxa + first place only
    }

    @Test fun `hasObservationsInPlaces checks all places and returns true if any match`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":5,"name":"Parus major"}]}"""))
        server.enqueue(MockResponse().setBody("""{"results":[],"total_results":0}""")) // first place: not found
        server.enqueue(MockResponse().setBody("""{"results":[],"total_results":1}""")) // second place: found
        val found = client.hasObservationsInPlaces("Parus major", listOf(7257L, 96773L))
        assertThat(found).isTrue()
    }

    @Test fun `hasObservationsInPlaces returns false when no observations in any place`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":5,"name":"Parus major"}]}"""))
        server.enqueue(MockResponse().setBody("""{"results":[],"total_results":0}"""))
        server.enqueue(MockResponse().setBody("""{"results":[],"total_results":0}"""))
        val found = client.hasObservationsInPlaces("Parus major", listOf(7257L, 96773L))
        assertThat(found).isFalse()
    }

    @Test fun `hasObservationsInPlaces uses taxon_name fallback when taxa lookup fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("error")) // taxa lookup fails
        server.enqueue(MockResponse().setBody("""{"results":[],"total_results":1}"""))
        val found = client.hasObservationsInPlaces("Columba palumbus", listOf(7257L))
        assertThat(found).isTrue()
        server.takeRequest() // taxa
        val obsReq = server.takeRequest()
        assertThat(obsReq.path).contains("taxon_name=Columba+palumbus")
    }

    @Test fun `fetchTaxonPhotoUrl picks exact name match over first result`() = runTest {
        // Server returns Corvus cornix first, Corvus corone second.
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                    {"name":"Corvus cornix","default_photo":{"medium_url":"https://example.com/cornix.jpg"}},
                    {"name":"Corvus corone","default_photo":{"medium_url":"https://example.com/corone.jpg"}}
                ]}""",
            ),
        )
        val url = client.fetchTaxonPhotoUrl("Corvus corone")
        assertThat(url).isEqualTo("https://example.com/corone.jpg")
        val req = server.takeRequest()
        assertThat(req.path).contains("per_page=5")
    }

    @Test fun `fetchTaxonPhotoUrl falls back to first result when no exact match`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                    {"name":"Corvus cornix","default_photo":{"medium_url":"https://example.com/cornix.jpg"}}
                ]}""",
            ),
        )
        val url = client.fetchTaxonPhotoUrl("Corvus corone")
        assertThat(url).isEqualTo("https://example.com/cornix.jpg")
    }

    @Test fun `getObservation parses quality grade, id count and comments`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "results": [{
                    "quality_grade": "research",
                    "comments_count": 2,
                    "identifications": [
                      {"current": true},
                      {"current": false},
                      {"current": true}
                    ],
                    "comments": [
                      {"body": "Great find!", "user": {"login": "alice"}},
                      {"body": "Agreed!", "user": {"login": "bob"}}
                    ]
                  }]
                }
                """.trimIndent(),
            ),
        )
        val detail = client.getObservation("12345")
        assertThat(detail.qualityGrade).isEqualTo("research")
        assertThat(detail.agreeingIdCount).isEqualTo(2)
        assertThat(detail.commentsCount).isEqualTo(2)
        assertThat(detail.comments).hasSize(2)
        assertThat(detail.comments[0].username).isEqualTo("alice")
        assertThat(detail.comments[0].body).isEqualTo("Great find!")
        val req = server.takeRequest()
        assertThat(req.path).startsWith("/v1/observations/12345")
    }

    @Test fun `getObservation caps comments at 3`() = runTest {
        val manyComments = (1..5).joinToString(",") {
            """{"body": "Comment $it", "user": {"login": "user$it"}}"""
        }
        server.enqueue(
            MockResponse().setBody(
                """
                {"results": [{"quality_grade": "needs_id", "comments_count": 5,
                  "identifications": [], "comments": [$manyComments]}]}
                """.trimIndent(),
            ),
        )
        val detail = client.getObservation("42")
        assertThat(detail.comments).hasSize(3)
    }

    @Test fun `getObservation throws INatException on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
        val ex = runCatching { client.getObservation("99999") }.exceptionOrNull()
        assertThat(ex).isInstanceOf(INatException::class.java)
        assertThat((ex as INatException).code).isEqualTo(404)
    }

    @Test fun `getObservation handles missing identifications array`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results": [{"quality_grade": "needs_id", "comments_count": 0}]}""",
            ),
        )
        val detail = client.getObservation("1")
        assertThat(detail.agreeingIdCount).isEqualTo(0)
        assertThat(detail.comments).isEmpty()
    }

    @Test
    fun `uploadObservationPhoto posts to v2 with correct fields and returns id`() = runTest {
        // Enqueue a v2-shaped response: { "results": [{ "id": 42 }] }
        server.enqueue(
            MockResponse().setBody("""{"results":[{"id":42}]}""").setResponseCode(200),
        )
        val photo = tmp.newFile("habitat.jpg").apply { writeText("JPEG") }
        val result = client.uploadObservationPhoto(
            token = "Bearer test-token",
            observationUuid = "obs-uuid-123",
            photoFile = photo,
        )
        assertThat(result).isEqualTo(42L)

        // Verify request went to v2 endpoint
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v2/observation_photos")
        assertThat(req.method).isEqualTo("POST")
        val body = req.body.readUtf8()
        assertThat(body).contains("observation_photo[observation_id]")
        assertThat(body).contains("obs-uuid-123")
        assertThat(body).contains("name=\"file\"")
        assertThat(body).contains(photo.name)
    }
}
