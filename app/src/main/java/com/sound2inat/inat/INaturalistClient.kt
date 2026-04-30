package com.sound2inat.inat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Thin wrapper around the iNaturalist v1 REST API for the operations the
 * sound2iNat MVP needs: authenticate (verify token), resolve scientific
 * names to taxon ids, create observations, upload sound files, and add
 * extra identifications.
 *
 * Auth: the personal API token from
 * https://www.inaturalist.org/users/api_token is a JWT. iNaturalist's own
 * Android app sends it in `Authorization` directly, **without** the
 * `Bearer ` prefix; mirroring that convention here.
 *
 * Tokens expire after ~24 h — callers should treat HTTP 401 as
 * "re-paste the token in Settings".
 */
class INaturalistClient(
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * `/observation_sounds` is exclusively a v2 route — confirmed against
     * `inaturalist/iNaturalistAPI/openapi/paths/v2/observation_sounds.js`.
     * v1 has neither sounds nor a legacy proxy. Tests inject a different URL
     * to point at MockWebServer.
     */
    private val v2BaseUrl: String = baseUrl.removeSuffix("/v1") + "/v2",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Returns the authenticated login on success; throws [INatException] otherwise. */
    suspend fun verifyToken(token: String): String = withContext(ioDispatcher) {
        val req = authedGet(token, "/users/me")
        executeJson(req).getJSONArray("results").getJSONObject(0).getString("login")
    }

    /**
     * Looks up the iNaturalist taxon id for [scientificName]. Returns null if
     * the API returns no match OR the top hit isn't an animal — we record
     * audio of birds/mammals/insects/herps, so a `Plantae` or `Fungi` hit is
     * categorically wrong (e.g. "Fireworks" → `Solidago rugosa`).
     *
     * Throws on transport / auth errors.
     */
    suspend fun resolveTaxon(scientificName: String, token: String?): Long? =
        withContext(ioDispatcher) {
            val q = scientificName.replace(' ', '+')
            // exact_match=true would need a different endpoint; the public /taxa
            // search ranks exact-name matches first, which is good enough.
            val path = "/taxa?q=$q&rank=species&is_active=true&per_page=1"
            val req = if (token != null) authedGet(token, path) else anonGet(path)
            val results = executeJson(req).optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null
            val top = results.getJSONObject(0)
            val iconic = top.optString("iconic_taxon_name", "")
            if (iconic !in ANIMAL_ICONIC_TAXA) {
                android.util.Log.w(
                    LOG_TAG,
                    "resolveTaxon($scientificName) -> iconic=$iconic — rejected (not an animal)",
                )
                return@withContext null
            }
            top.getLong("id")
        }

    /**
     * Creates an observation. Returns the new observation id and its public URL.
     */
    suspend fun createObservation(token: String, body: ObservationBody): CreatedObservation =
        withContext(ioDispatcher) {
            val obs = JSONObject().apply {
                body.observedAtIso?.let { put("observed_on_string", it) }
                body.latitude?.let { put("latitude", it) }
                body.longitude?.let { put("longitude", it) }
                body.positionalAccuracy?.let { put("positional_accuracy", it) }
                body.taxonId?.let { put("taxon_id", it) }
                body.description?.let { put("description", it) }
                body.licenseCode?.let { put("license_code", it) }
            }
            val payload = JSONObject()
                .put("observation", obs)
                // Match iNat Android: avoid implicit photo handling on POST.
                .put("ignore_photos", true)
            val req = Request.Builder()
                .url(baseUrl + "/observations")
                .header("Authorization", token)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            val json = executeJsonOrArrayFirst(req)
            val id = json.getLong("id")
            val uuid = json.optString("uuid", "")
            CreatedObservation(
                id = id,
                uuid = uuid,
                url = "https://www.inaturalist.org/observations/${if (uuid.isNotEmpty()) uuid else id.toString()}",
            )
        }

    /**
     * Uploads [audioFile] (WAV) and links it to the observation identified
     * by [observationUuid] via the v2 multipart form. Returns the iNat
     * sound id.
     *
     * Multipart shape (from
     * `inaturalist/iNaturalistAPI/openapi/schema/request/observation_sounds_create_multipart.js`):
     *   - `observation_sound[observation_id]` — string UUID, required
     *   - `file` — binary, required (note: NOT `audio`; that's the v1
     *     legacy controller field name and the v1 path itself returns 500)
     */
    suspend fun uploadSound(token: String, observationUuid: String, audioFile: File): Long =
        withContext(ioDispatcher) {
            require(observationUuid.isNotBlank()) {
                "uploadSound requires the observation's UUID (got blank)"
            }
            require(audioFile.exists() && audioFile.length() > 0) {
                "Audio file missing or empty: ${audioFile.absolutePath}"
            }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("observation_sound[observation_id]", observationUuid)
                .addFormDataPart(
                    name = "file",
                    filename = audioFile.name,
                    body = audioFile.asRequestBody(WAV_MEDIA_TYPE),
                )
                .build()
            val req = Request.Builder()
                .url("$v2BaseUrl/observation_sounds")
                .header("Authorization", token)
                .header("Accept", "application/json")
                .post(body)
                .build()
            // v2 wraps single resources in `{ results: [...] }` — read the
            // first entry's id.
            val resp = executeJson(req)
            val results = resp.optJSONArray("results")
            if (results != null && results.length() > 0) {
                results.getJSONObject(0).getLong("id")
            } else {
                resp.getLong("id")
            }
        }

    /**
     * Deletes an observation. Used to roll back orphan observations when
     * the subsequent sound upload fails — no point leaving an empty record
     * on the user's iNaturalist account.
     */
    suspend fun deleteObservation(token: String, observationId: Long) =
        withContext(ioDispatcher) {
            val req = Request.Builder()
                .url(baseUrl + "/observations/" + observationId)
                .header("Authorization", token)
                .header("Accept", "application/json")
                .delete()
                .build()
            // 204 No Content has empty body; we don't need to parse anything.
            http.newCall(req).execute().use { resp ->
                logHttp(req, resp.code, resp.body?.string().orEmpty())
            }
            Unit
        }

    /**
     * Updates the observation's free-text description. Used by the multi-species
     * submission flow to cross-link sibling observations after they're all created.
     */
    suspend fun updateObservationDescription(
        token: String,
        observationId: Long,
        description: String,
    ) = withContext(ioDispatcher) {
        val payload = JSONObject().apply {
            put("observation", JSONObject().apply { put("description", description) })
            put("ignore_photos", true)
        }
        val req = Request.Builder()
            .url(baseUrl + "/observations/" + observationId)
            .header("Authorization", token)
            .header("Accept", "application/json")
            .put(payload.toString().toRequestBody(JSON))
            .build()
        executeJsonOrArrayFirst(req)
        Unit
    }

    /**
     * Attaches a single annotation (controlled-term value) to an existing
     * observation. Best-effort: a 4xx here does NOT invalidate the parent
     * observation, so callers should not roll back the upload on failure.
     *
     * The relevant attribute/value IDs come from iNaturalist's controlled
     * vocabulary — see the iNaturalist Helper Chrome extension's
     * `scripts/vision.js` for the canonical id table. The ones we use:
     * `Alive or Dead` (attr 17 / value 18 = Alive),
     * `Evidence of Presence` (attr 22 / value 24 = Organism).
     */
    suspend fun createAnnotation(
        token: String,
        observationUuid: String,
        controlledAttributeId: Int,
        controlledValueId: Int,
    ) = withContext(ioDispatcher) {
        val payload = JSONObject().apply {
            put("resource_type", "Observation")
            put("resource_id", observationUuid)
            put("controlled_attribute_id", controlledAttributeId)
            put("controlled_value_id", controlledValueId)
        }
        val req = Request.Builder()
            .url(baseUrl + "/annotations")
            .header("Authorization", token)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        executeJsonOrArrayFirst(req)
        Unit
    }

    /**
     * Returns the `medium_url` of the taxon's default photo, or null if the
     * name doesn't resolve or the taxon has no photo. Anonymous — no token
     * required, so safe to call from the Review screen before submission.
     */
    suspend fun fetchTaxonPhotoUrl(scientificName: String): String? = withContext(ioDispatcher) {
        val q = scientificName.replace(' ', '+')
        val req = anonGet("/taxa?q=$q&rank=species&is_active=true&per_page=1")
        val results = runCatching { executeJson(req).optJSONArray("results") }
            .getOrNull() ?: return@withContext null
        if (results.length() == 0) return@withContext null
        results.getJSONObject(0)
            .optJSONObject("default_photo")
            ?.optString("medium_url")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Returns true if [scientificName] has at least one observation within [radiusKm] km of
     * ([lat], [lon]) on iNaturalist. Anonymous — no token required.
     *
     * Fail-open: returns true on any network or parse error so a transient outage never
     * silently drops a valid detection.
     */
    suspend fun hasObservationsNear(
        scientificName: String,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): Boolean = withContext(ioDispatcher) {
        val q = scientificName.replace(' ', '+')
        val path = "/observations?taxon_name=$q&lat=$lat&lng=$lon&radius=$radiusKm&per_page=1"
        val req = anonGet(path)
        runCatching { executeJson(req) }
            .map { json -> json.optInt("total_results", 0) > 0 }
            .getOrDefault(true)
    }

    /** Adds a single identification (taxon vote) on [observationId]. */
    suspend fun addIdentification(token: String, observationId: Long, taxonId: Long, body: String?) =
        withContext(ioDispatcher) {
            val payload = JSONObject().apply {
                put(
                    "identification",
                    JSONObject().apply {
                        put("observation_id", observationId)
                        put("taxon_id", taxonId)
                        body?.let { put("body", it) }
                    },
                )
            }
            val req = Request.Builder()
                .url(baseUrl + "/identifications")
                .header("Authorization", token)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            executeJsonOrArrayFirst(req)
            Unit
        }

    private fun authedGet(token: String, path: String): Request =
        Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", token)
            .header("Accept", "application/json")
            .get()
            .build()

    private fun anonGet(path: String): Request =
        Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
            .get()
            .build()

    @Suppress("ThrowsCount", "TooGenericExceptionCaught", "SwallowedException")
    private fun executeJson(req: Request): JSONObject {
        try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                logHttp(req, resp.code, raw)
                if (!resp.isSuccessful) throw INatException(resp.code, summarize(resp.code, raw))
                return JSONObject(raw)
            }
        } catch (e: INatException) {
            throw e
        } catch (e: IOException) {
            // Wrap into a domain error so callers don't have to know about IOException.
            throw INatException(code = 0, message = "Network: ${e.message}")
        } catch (e: Throwable) {
            throw INatException(code = -1, message = "Parse: ${e.message}")
        }
    }

    /**
     * Some iNat endpoints return `[ { ... } ]` (a single-element array)
     * instead of a bare JSON object — the legacy shim. Accept either.
     */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught", "SwallowedException")
    private fun executeJsonOrArrayFirst(req: Request): JSONObject {
        try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                logHttp(req, resp.code, raw)
                if (!resp.isSuccessful) throw INatException(resp.code, summarize(resp.code, raw))
                val trimmed = raw.trimStart()
                return if (trimmed.startsWith("[")) {
                    JSONArray(raw).getJSONObject(0)
                } else {
                    JSONObject(raw)
                }
            }
        } catch (e: INatException) {
            throw e
        } catch (e: IOException) {
            throw INatException(code = 0, message = "Network: ${e.message}")
        } catch (e: Throwable) {
            throw INatException(code = -1, message = "Parse: ${e.message}")
        }
    }

    /**
     * Always log full body to logcat under tag "INatHttp" — invaluable when
     * the server returns a 500 HTML page or a "JWT expired" JSON, neither of
     * which is helpful when surfaced verbatim in the toast.
     */
    private fun logHttp(req: Request, code: Int, raw: String) {
        if (code in SUCCESS_RANGE) {
            android.util.Log.d(LOG_TAG, "${req.method} ${req.url} -> $code (${raw.length}B)")
        } else {
            android.util.Log.w(LOG_TAG, "${req.method} ${req.url} -> $code body=${raw.take(LOG_BODY_LEN)}")
        }
    }

    /**
     * Builds a short human-readable failure message. iNat's error responses
     * vary wildly: HTML pages from Cloudflare/nginx, `{"error": "..."}` JSON
     * blobs from the Rails app, plain text from the JWT layer. We strip HTML
     * tags, prefer JSON `error`/`errors[0]`, and clamp length so the toast
     * stays readable.
     */
    private fun summarize(code: Int, raw: String): String {
        val short = raw.trim().let { body ->
            // Try to read iNat's JSON error shape first.
            runCatching {
                val obj = JSONObject(body)
                val direct = obj.optString("error").takeIf { it.isNotBlank() }
                val nested = obj.optJSONArray("errors")?.optString(0)?.takeIf { it.isNotBlank() }
                direct ?: nested
            }.getOrNull() ?: stripHtml(body)
        }
        return "HTTP $code: ${short.take(MAX_ERR_LEN)}"
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.inaturalist.org/v1"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
        private const val MAX_ERR_LEN = 200
        private const val LOG_TAG = "INatHttp"
        private const val LOG_BODY_LEN = 1000
        private val SUCCESS_RANGE = 200..299

        // iNaturalist's "iconic taxa" — the top-level groupings on a taxon's
        // record. We accept anything that's a vocalising or audibly active
        // organism; explicitly reject Plantae/Fungi/Protozoa/Chromista where
        // a name collision would be silly (or worse, recorded under a wrong
        // kingdom on the user's account).
        private val ANIMAL_ICONIC_TAXA = setOf(
            "Animalia", "Aves", "Mammalia", "Insecta", "Arachnida",
            "Reptilia", "Amphibia", "Mollusca", "Actinopterygii",
        )
    }
}

/** Inputs for [INaturalistClient.createObservation]. */
data class ObservationBody(
    val observedAtIso: String?,
    val latitude: Double?,
    val longitude: Double?,
    val positionalAccuracy: Float?,
    val taxonId: Long?,
    val description: String?,
    val licenseCode: String?,
)

data class CreatedObservation(val id: Long, val uuid: String, val url: String)

class INatException(val code: Int, override val message: String) : RuntimeException(message)
