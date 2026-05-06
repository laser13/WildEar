# iNaturalist API — notes for sound2iNat

Source of truth: `swagger-v1.json` (downloaded from
`https://api.inaturalist.org/v1/swagger.json`). Re-fetch after any reported
behaviour change. v2 swagger is **not** publicly served (404).

## Hosts and paths — DO NOT GUESS

`/v1` covers most endpoints (`/observations`, `/taxa`, `/identifications`,
`/observation_photos`, …). **`/observation_sounds` is on `/v2` only** —
not on `/v1`, not on the legacy root.

Quick experiment that locked this down:
- `POST /v1/observation_sounds` → 500 HTML "Internal Server Error" (route
  exists in v1 controller but is broken / unused).
- `POST /observation_sounds` (legacy root) → 404 HTML "Cannot POST".
- `POST /v2/observation_sounds` → ✅ documented in
  [iNaturalistAPI/openapi/paths/v2/observation_sounds.js](https://github.com/inaturalist/iNaturalistAPI/blob/main/openapi/paths/v2/observation_sounds.js)
  and the multipart schema lives at
  [openapi/schema/request/observation_sounds_create_multipart.js](https://github.com/inaturalist/iNaturalistAPI/blob/main/openapi/schema/request/observation_sounds_create_multipart.js).

We model both URLs in
[INaturalistClient](../../app/src/main/java/com/sound2inat/inat/INaturalistClient.kt):
`baseUrl` (default `…/v1`) for everything else, and `v2BaseUrl` (computed
as `baseUrl` minus `/v1` plus `/v2`) for sound uploads.

**Important v2 quirks for sound uploads:**
- `observation_sound[observation_id]` is a **UUID string**, not the
  integer `id`. Pass `created.uuid`, never `created.id.toString()`.
- File field is named **`file`** (not `audio` — that was the v1
  legacy controller). Photos also use `file` on `/v1/observation_photos`.
- v2 wraps single resources in `{ "results": [ {...} ] }`. Read the
  first entry's id, not a top-level `id`.

## Auth

`Authorization: <jwt>` — the personal API token from
`/users/api_token`. **No `Bearer ` prefix** (iNaturalistAndroid sends the raw
JWT on the node API; `Bearer` is only used for legacy OAuth2 access tokens).

JWTs expire ≈ 24 h. Treat HTTP 401 as "user must re-paste the token".

## Endpoint cheat-sheet (only what we actually use)

| What                         | Method + URL                                          | Body                                                                                  |
|---                           |---                                                    |---                                                                                    |
| Verify token / who am I      | `GET /v1/users/me`                                    | (none) → `{ results: [{ login, ... }] }`                                              |
| Resolve scientific name      | `GET /v1/taxa?q=<name>&rank=species&is_active=true`   | (none) → `{ results: [{ id, name, rank, ... }] }`                                     |
| Create observation           | `POST /v1/observations`                               | `{"observation": {observed_on_string, latitude, longitude, positional_accuracy, taxon_id, description, license_code}, "ignore_photos": true}` |
| Update observation (description, etc.) | `PUT /v1/observations/{id}`                  | same wrapper                                                                          |
| Delete observation           | `DELETE /v1/observations/{id}`                        | (none)                                                                                |
| Add identification           | `POST /v1/identifications`                            | `{"identification": {observation_id, taxon_id, body}}`                                |
| Upload sound (multipart)     | `POST /v2/observation_sounds` *(v2 only — see above)* | multipart: `observation_sound[observation_id]=<UUID string>`, file field `file`       |
| Upload photo (multipart, FYI)| `POST /v1/observation_photos`                         | multipart: `observation_photo[observation_id]=<integer id>`, file field `file`        |

**Field-name asymmetry**: photos use file field `file`, sounds use `audio`.
Confirmed against iNaturalistAndroid's `INaturalistServiceImplementation`
(`params.add(new Pair("audio", os.filename))`). Don't unify them.

## Response shapes

iNat sometimes returns `{ ... }` and sometimes `[ { ... } ]` (legacy shim).
`INaturalistClient.executeJsonOrArrayFirst` accepts both — keep using it for
write endpoints.

Errors arrive as one of:
- `{"error": "..."}`
- `{"errors": ["..."]}`
- raw HTML page (Rails / nginx)

Our `summarize(code, raw)` peels these into a short toast string.

## iNat data model gotchas

- **One observation = one species.** When the user picks N species in
  Review we MUST create N separate observations. Identifications are votes
  on someone's observation, not a way to merge multiple species into one.
- Cross-link sibling observations after creation via `PUT description`.
- `observed_on_string` accepts ISO-8601 with timezone (`yyyy-MM-dd'T'HH:mm:ssZ`).
  We always send UTC.
- `license_code = "cc-by-nc"` matches the BirdNET v2.4 weights license; do
  NOT publish under a more permissive code unless we replace the model.
- BirdNET v2.4 outputs **raw logits**, NOT softmax. We sigmoid per top-K
  (BirdNetTfliteModel). The `MODEL_SPIKE.md` was wrong.

## When something fails

1. `adb logcat -c && adb logcat -s INatHttp:V INatSubmitter:V AndroidRuntime:E '*:F'`
2. Find the offending endpoint in `swagger-v1.json` first; do **not** assume
   parity between v1 and legacy root.
3. If the response is HTML, that's almost certainly a routing mismatch
   (wrong host/path) — check the cheat-sheet above.
