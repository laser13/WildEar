# Sound2iNat: two implementation plans

This document contains two separate plans:

1. **Private app plan** — an Android app installed only on the user's own phone, without Play Store/F-Droid/public distribution.
2. **Public app plan** — an app that can be published for other people, with proper licensing, OAuth, privacy policy, and distribution preparation.

The two plans share the same core product idea but differ in legal risk, model choices, packaging, registration, and release process.

---

# 1. Product idea

Build a small Android application for field sound observations.

Core workflow:

```text
Open app
↓
Tap Record
↓
Record bird sound with microphone
↓
Run live on-device sound classification
↓
Show detected species while recording
↓
Stop recording
↓
Review detected species
↓
User confirms one or more taxa
↓
Upload sound + GPS + time + selected taxon to user's iNaturalist account
```

Important: this is **not** a Merlin clone. Merlin is only a UX reference. The app should not depend on Merlin, reverse engineer Merlin, or reuse Merlin models unless Cornell explicitly provides a legal SDK/API.

---

# 2. Common principles for both plans

## 2.1 Human review before iNaturalist upload

The app must not silently create machine-generated observations.

Correct pattern:

```text
model suggests → human reviews → human taps Upload
```

Not acceptable:

```text
model predicts → app uploads automatically with no user oversight
```

Reason: iNaturalist community guidelines do not allow machine-generated observations, IDs, or comments without human oversight.

## 2.2 Original sound is the evidence

The app should upload the real recorded sound, not a screenshot of a classifier and not generated/altered evidence.

Allowed:
- original field audio,
- optional trimmed clip from original audio,
- optional notes saying that a model suggested the ID.

Avoid:
- uploading only a classifier screenshot,
- uploading denoised/generated audio as the only evidence,
- claiming certainty based only on a model.

## 2.3 One observation = one selected taxon

A single recording can contain several birds.

The review screen should allow:

```text
Recording: 00:00–01:30
Detected:
- Cyprus Warbler
- Sardinian Warbler
- Common Chiffchaff

User selects:
[x] Cyprus Warbler
[x] Sardinian Warbler
[ ] Common Chiffchaff
```

Then the app creates separate iNaturalist observations for each selected taxon, ideally attaching the same original sound or a clipped segment if implemented.

## 2.4 Offline-first

Field use must work without internet:

- record offline,
- classify offline if model is local,
- save draft locally,
- upload later when internet is available.

## 2.5 Swappable model layer

Do not hard-code the app to a single model.

Create an abstraction:

```kotlin
/**
 * Runs bioacoustic classification for short audio windows.
 */
interface BioacousticModel {
    /**
     * Loads model weights and metadata into memory.
     */
    suspend fun load()

    /**
     * Predicts likely taxa for a short audio window.
     */
    suspend fun predict(
        pcmFloat32: FloatArray,
        sampleRateHz: Int,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long
    ): List<ModelPrediction>

    /**
     * Releases native resources.
     */
    fun close()
}
```

This allows:
- BirdNET in private mode,
- Perch in public mode,
- server-side inference fallback,
- future custom model.

---

# 3. Plan A: private Android app for personal use

## 3.1 Goal

Create an APK that the user can install manually on their own Android phone.

No public app store.
No public redistribution.
No need to support other users.
No need to solve every licensing issue before experimentation, but still keep notes and attribution.

The goal is practical personal workflow:

```text
record → live suggestions → review → upload to my iNaturalist account
```

## 3.2 Recommended model strategy for private app

### Preferred practical model: BirdNET

For personal use, BirdNET is the most practical first choice because:

- it is mature for bird sound recognition,
- it has existing real-time implementations,
- it is likely closer to the Merlin-like UX for birds,
- it supports location/date filtering in the BirdNET ecosystem,
- there are reference apps and libraries.

However, still record the license situation:

- BirdNET source code is commonly MIT.
- BirdNET model weights are commonly CC BY-NC-SA 4.0.
- This is acceptable for cautious personal/non-commercial experimentation, but not automatically safe for public app distribution.

### Secondary model: Perch

Use Perch as a research track:

- test accuracy on Cyprus bird recordings,
- benchmark speed and RAM,
- check whether it can run efficiently on Android,
- keep it as migration path for public app.

### Do not use Merlin

Do not use:
- Merlin app internals,
- extracted Merlin model files,
- reverse-engineered APIs,
- unofficial scraping of Merlin resources.

## 3.3 Architecture for private app

Recommended stack:

```text
Kotlin
Jetpack Compose
AudioRecord
Room
WorkManager
FusedLocationProvider
ONNX Runtime Mobile or TensorFlow Lite
iNaturalist API
```

Alternative fast path:

- fork/study an existing Android BirdNET app such as whoBIRD,
- add review screen and iNaturalist upload,
- keep it private.

For a private prototype, speed matters more than ideal architecture. But avoid hacks that will block a future public version.

## 3.4 Private app modules

### 3.4.1 Audio recorder

Responsibilities:

- request microphone permission,
- record PCM audio,
- write WAV file,
- keep rolling windows for live inference,
- save session metadata.

Implementation notes:

- Use `AudioRecord`, not `MediaRecorder`, if live inference is needed.
- Store original WAV locally.
- Optionally encode M4A only for upload if iNaturalist accepts it and file size matters.

### 3.4.2 Live inference

Responsibilities:

- run model every 1–3 seconds on a 3–5 second audio window,
- show top predictions,
- store detections with time ranges.

Suggested private settings:

```text
window_size_seconds = 3 or 5
hop_seconds = 1
top_k = 5
min_confidence_display = 0.25
min_confidence_upload_default = 0.50
```

### 3.4.3 Detection aggregation

Group repeated predictions:

```text
Cyprus Warbler:
- max confidence: 0.86
- detected windows: 8
- first seen: 00:07
- last seen: 00:34
```

Use this to avoid overreacting to one weak detection.

### 3.4.4 Review screen

Show:

- audio player,
- list of detected species,
- confidence,
- number of detections,
- taxon mapping status,
- upload options.

Actions:

```text
[Upload selected]
[Upload as Birds / Aves]
[Save draft]
[Delete]
```

### 3.4.5 iNaturalist upload

For private use, two auth options:

#### Option 1: personal token / manual auth

Fastest for prototype.

Pros:
- simple,
- easy to debug.

Cons:
- not suitable for public app,
- token handling must still be careful.

#### Option 2: OAuth app

Better even for private use.

Pros:
- same path as public app,
- safer long term,
- easier to migrate.

Recommendation:
- Use OAuth if not too hard.
- If blocked, start with personal token only in local debug builds.

Upload flow:

```text
create observation
↓
upload sound
↓
store resulting observation URL
↓
mark local draft as uploaded
```

Observation notes example:

```text
Sound recorded by the observer.
On-device classifier suggestion: BirdNET / model version X.
Uploaded after human review.
```

## 3.5 Private app release process

1. Build debug/release APK locally.
2. Install via Android Studio or `adb install`.
3. Keep app unsigned or self-signed for personal use.
4. Keep credentials outside Git.
5. Do not publish APK if BirdNET model is bundled unless license is clarified.
6. Keep a local `LICENSE_NOTES.md`.

## 3.6 Private app milestones

### Milestone A1: model spike on laptop

Tasks:

- run BirdNET on sample recordings,
- run Perch on same recordings if feasible,
- compare top-k predictions,
- choose first model.

Deliverable:

```text
docs/private/MODEL_SPIKE.md
```

### Milestone A2: Android recorder

Tasks:

- Kotlin Compose app,
- microphone permission,
- location permission,
- record WAV,
- playback locally.

Deliverable:

```text
APK records and replays sound.
```

### Milestone A3: live BirdNET inference

Tasks:

- integrate runtime,
- run rolling-window predictions,
- show live species list.

Deliverable:

```text
APK shows live detections during recording.
```

### Milestone A4: review screen

Tasks:

- aggregate detections,
- allow user to select taxa,
- save draft.

Deliverable:

```text
User can review recording and choose species.
```

### Milestone A5: iNaturalist upload

Tasks:

- implement auth,
- resolve taxon IDs,
- create observation,
- upload sound,
- store iNaturalist URL.

Deliverable:

```text
End-to-end private MVP.
```

## 3.7 Private app success criteria

The private app is successful when:

1. It records field audio.
2. It shows live bird suggestions.
3. It stores GPS and timestamp.
4. It lets the user confirm species.
5. It uploads a sound observation to the user's iNaturalist account.
6. It does not post anything without explicit user confirmation.

---

# 4. Plan B: public application for other users

## 4.1 Goal

Create a distributable app that other users can install.

Possible channels:

- GitHub Releases,
- F-Droid,
- Google Play.

For public release, the app must be much stricter:

- safe model license,
- proper OAuth,
- privacy policy,
- attribution,
- no misleading iNaturalist affiliation,
- no unattended machine-generated uploads,
- clean data handling.

## 4.2 Recommended model strategy for public app

### Preferred model: Perch / Apache-compatible model

For public distribution, choose a model with a permissive license.

Perch is currently the best candidate because:

- Google Research Perch code is Apache-2.0,
- Perch model pages indicate Apache-compatible availability,
- Perch 2.0 covers many vocalizing species, not only birds,
- Apache-2.0 is much easier for public distribution than CC BY-NC-SA.

Required before implementation:

- verify exact model artifact license,
- record model URL,
- record SHA256,
- save license text,
- confirm redistribution/bundling rules,
- check whether the model can be bundled inside the APK/AAB or must be downloaded after install.

### BirdNET in public app: only with permission or user-managed download

BirdNET should not be the default public bundled model unless one of these is true:

1. Written permission is obtained from rights holders.
2. The model license for the exact artifact allows the intended distribution.
3. The app does not bundle the model and instead lets the user manually download/import a model after seeing the model license.

Even option 3 must be reviewed carefully.

Recommended public policy:

```text
Default public model: Perch or another permissive model.
BirdNET: optional experimental/private plugin, not bundled by default.
```

## 4.3 Public app architecture

Recommended stack:

```text
Kotlin
Jetpack Compose
AudioRecord
Room
WorkManager
Android Keystore
OAuth via browser/custom tab
TensorFlow Lite / ONNX Runtime Mobile
iNaturalist API
```

Why native Kotlin:

- best control over microphone,
- best Android permission handling,
- easy Play Store compliance,
- robust foreground service if needed,
- better token storage via Android Keystore.

## 4.4 Public app modules

Same modules as private app, but stricter.

### 4.4.1 Permissions

Required permissions:

```text
RECORD_AUDIO
ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION
INTERNET
POST_NOTIFICATIONS if foreground recording notification is needed
FOREGROUND_SERVICE_MICROPHONE if background/foreground service recording is used
```

Public UX must explain:

- why microphone is needed,
- why location is needed,
- when audio is uploaded,
- when location is uploaded.

### 4.4.2 Model manager

Public app needs a dedicated model manager.

Responsibilities:

- list installed models,
- show model license,
- show model attribution,
- download model if allowed,
- verify checksum,
- allow model deletion,
- expose model version in upload notes.

Suggested model metadata:

```json
{
  "model_id": "perch_2_0",
  "display_name": "Perch 2.0",
  "license": "Apache-2.0",
  "source_url": "...",
  "sha256": "...",
  "species_count": 15000,
  "runtime": "tflite",
  "version": "..."
}
```

### 4.4.3 Taxon resolver

For public app this must be robust.

Features:

- exact scientific name match,
- synonym handling,
- iNaturalist taxon search,
- manual taxon search,
- local cache,
- confidence warning if match is uncertain.

User must be able to override model suggestions.

### 4.4.4 Upload queue

Public app must handle unreliable networks cleanly.

Use `WorkManager`.

States:

```text
DRAFT
READY_TO_UPLOAD
UPLOADING
UPLOADED
FAILED_RETRYABLE
FAILED_FINAL
```

Never silently retry in a way that creates duplicates.

Use idempotency strategy:

- local upload job ID,
- store observation ID after creation,
- if sound upload fails after observation creation, resume attaching sound to same observation.

### 4.4.5 Privacy and data controls

User settings:

- default geoprivacy:
  - open,
  - obscured,
  - private.
- upload only on Wi-Fi.
- delete local audio after successful upload.
- keep local archive.
- include classifier note in observation description: on/off.

## 4.5 Public app iNaturalist registration

### 4.5.1 Register OAuth application

Steps:

1. Create project account or use developer account.
2. Register OAuth app in iNaturalist.
3. Configure redirect URI:
   - `sound2inat://oauth/callback`
4. Use browser/custom tab for login.
5. Store tokens in Android Keystore.
6. Implement logout.

Do not ask users to paste personal tokens.

### 4.5.2 Naming and affiliation

Avoid names that imply official affiliation.

Bad:

```text
iNaturalist Sound ID
Official iNat Sound Uploader
```

Better:

```text
Sound2iNat
Field Sound Uploader
Bioacoustic Observation Helper
```

Add disclaimer:

```text
This app is not affiliated with or endorsed by iNaturalist.
It uploads observations to your iNaturalist account only after your confirmation.
```

## 4.6 Public app iNaturalist policy compliance

Public app must explicitly prevent machine-generated spam.

Mandatory UX:

- user taps Upload,
- user sees selected taxon,
- user sees sound/location/time,
- user can edit or cancel,
- no background auto-posting.

Recommended note:

```text
Recorded by the observer. Species suggestion generated on-device by {model_name} {model_version} and confirmed by the uploader.
```

Do not post generic AI comments or IDs automatically.

## 4.7 Public app release checklist

### 4.7.1 Documents before coding

Create:

```text
docs/LICENSE_AUDIT.md
docs/MODEL_DECISION.md
docs/INAT_API_PLAN.md
docs/PRIVACY_POLICY.md
docs/TERMS_AND_DISCLAIMER.md
docs/PLAY_STORE_DATA_SAFETY.md
```

### 4.7.2 License audit

For every dependency and model:

```text
name
version
source URL
license
redistribution allowed?
commercial use allowed?
attribution required?
model weights license?
labels license?
notes
```

Blocker rule:

```text
No public release until model weights and labels have a public-distribution-compatible license.
```

### 4.7.3 Privacy policy

Must explain:

- microphone use,
- location use,
- local processing,
- upload to iNaturalist,
- OAuth token storage,
- whether analytics/crash reporting is used,
- whether data is shared with any backend,
- how to delete local recordings.

Simplest privacy-friendly approach:

```text
No custom backend.
No analytics.
No third-party tracking.
All classification on-device.
Uploads go directly from phone to iNaturalist after confirmation.
```

### 4.7.4 Google Play preparation

Prepare:

- app icon,
- screenshots,
- short description,
- full description,
- privacy policy URL,
- data safety form,
- permissions declarations,
- content rating,
- closed testing track,
- crash reporting decision.

If using microphone foreground service, ensure Android policy compliance.

### 4.7.5 F-Droid preparation

If targeting F-Droid:

- app source must be buildable,
- proprietary dependencies may be a problem,
- model download mechanism must be acceptable,
- no hidden tracking,
- license compatibility must be clean.

F-Droid may be easier ethically but stricter technically.

## 4.8 Public app milestones

### Milestone B1: license and model decision

Tasks:

- verify Perch model license,
- verify model artifact distribution rights,
- benchmark model,
- decide whether model is bundled or downloaded.

Deliverable:

```text
docs/public/MODEL_DECISION.md
```

### Milestone B2: clean Android skeleton

Tasks:

- Kotlin Compose app,
- permissions,
- local DB,
- settings screen,
- privacy-first defaults.

Deliverable:

```text
Installable app skeleton.
```

### Milestone B3: model manager + inference

Tasks:

- model download/import,
- checksum verification,
- license display,
- runtime integration,
- live predictions.

Deliverable:

```text
Public-safe live inference.
```

### Milestone B4: review workflow

Tasks:

- aggregation,
- user confirmation,
- manual taxon search,
- broader taxon fallback.

Deliverable:

```text
User can create upload-ready drafts.
```

### Milestone B5: iNaturalist OAuth and upload

Tasks:

- OAuth,
- taxon resolution,
- observation creation,
- sound upload,
- duplicate-safe retry.

Deliverable:

```text
End-to-end upload with user account.
```

### Milestone B6: beta compliance

Tasks:

- privacy policy,
- attribution page,
- license audit,
- Play Store/F-Droid packaging,
- beta testing.

Deliverable:

```text
Release candidate.
```

## 4.9 Public app success criteria

The public app is successful when:

1. It uses only legally distributable models/dependencies.
2. It records and classifies sound locally.
3. It never uploads observations without human confirmation.
4. It uses OAuth, not pasted tokens.
5. It has clear privacy policy and attribution.
6. It can survive Play Store / F-Droid review.
7. It does not imply official iNaturalist endorsement.

---

# 5. Main differences between private and public plans

| Area | Private app | Public app |
|---|---|---|
| Distribution | Sideload APK | Play Store / F-Droid / GitHub |
| Main goal | Personal workflow | Safe tool for many users |
| Model choice | BirdNET acceptable for prototype | Prefer Perch / permissive model |
| BirdNET model | OK for personal non-commercial testing after notes | Do not bundle unless permission/license allows |
| OAuth | Personal token or OAuth | OAuth required |
| Privacy policy | Not required, but useful | Required |
| License audit | Lightweight | Mandatory |
| App signing | Local/self-signed okay | Proper release signing |
| Token storage | Secure storage still recommended | Android Keystore required |
| Upload behavior | Human confirmation | Human confirmation + anti-spam UX |
| Support burden | None | Must handle errors, edge cases, docs |
| Backend | Avoid | Avoid unless necessary |
| iNaturalist naming | Private doesn't matter much | Must avoid official-affiliation confusion |

---

# 6. Recommended path

## Phase 1: private prototype

Build private app first using BirdNET or the fastest working model.

Goal:
- prove UX,
- prove iNaturalist upload,
- learn real constraints,
- collect personal test recordings.

Do not publish.

## Phase 2: model comparison

Compare:

```text
BirdNET vs Perch
```

On:

- Cyprus birds,
- noisy urban recordings,
- multiple simultaneous species,
- phone CPU speed,
- RAM,
- battery,
- taxon mapping quality.

## Phase 3: public-safe rewrite or hardening

If the prototype works:

- switch default model to Perch or other permissive model,
- keep BirdNET only as optional private plugin if license allows,
- clean up OAuth,
- add privacy policy,
- prepare release.

---

# 7. Agent prompt

Use this prompt for a coding/research agent.

```text
You are building Sound2iNat, an Android app for recording bird/wildlife sounds, running live on-device species classification, and uploading human-confirmed sound observations to the user's iNaturalist account.

There are two tracks:

PRIVATE TRACK:
- Build a sideloaded Android APK for one user's personal use.
- BirdNET may be used for prototyping after recording license notes.
- The app must record sound, capture GPS/time, run live detection, show a review screen, and upload only after user confirmation.
- No Play Store, no public distribution.

PUBLIC TRACK:
- Build a distributable app for other users.
- Use only models and dependencies compatible with public distribution.
- Prefer Perch or another Apache/MIT/permissive model.
- Do not bundle BirdNET models unless explicit permission or compatible license is confirmed.
- Implement OAuth, privacy policy, attribution, model license display, and anti-spam/human-review UX.

General rules:
1. Do not use or reverse engineer Merlin.
2. Do not scrape eBird, Macaulay, or iNaturalist media.
3. Do not create machine-generated iNaturalist observations without human review.
4. Keep original sound evidence.
5. Use official iNaturalist APIs.
6. Make the model layer swappable.
7. Store OAuth tokens securely.
8. Support offline drafts and later upload.
9. Avoid implying official affiliation with iNaturalist.
10. Before public release, create LICENSE_AUDIT.md, MODEL_DECISION.md, INAT_API_PLAN.md, PRIVACY_POLICY.md, and RELEASE_CHECKLIST.md.

First tasks:
1. Create repository structure.
2. Write LICENSE_AUDIT.md comparing BirdNET and Perch.
3. Write MODEL_DECISION.md with private/public model choices.
4. Build minimal Android recorder.
5. Add live inference.
6. Add review screen.
7. Add iNaturalist taxon resolver.
8. Add OAuth and upload.
```

---

# 8. Repository structure

```text
sound2inat/
  android/
    app/
      src/main/
  docs/
    private/
      MODEL_SPIKE.md
      PRIVATE_INSTALL.md
    public/
      LICENSE_AUDIT.md
      MODEL_DECISION.md
      INAT_API_PLAN.md
      PRIVACY_POLICY.md
      RELEASE_CHECKLIST.md
      PLAY_STORE_DATA_SAFETY.md
  models/
    README.md
    MODEL_METADATA.schema.json
  scripts/
    evaluate_model.py
    resolve_taxa.py
  testdata/
    README.md
```

---

# 9. Final recommendation

Start with the private app.

Private MVP:
- Kotlin + Compose.
- BirdNET if easiest.
- Local APK.
- Human review.
- Upload to your own iNaturalist account.

Then build the public version only after:
- Perch or another permissive model is validated on mobile,
- iNaturalist upload flow is stable,
- license audit is clean,
- privacy policy and attribution are ready.

This avoids getting stuck in legal/public-release complexity before proving that the core workflow is useful.
