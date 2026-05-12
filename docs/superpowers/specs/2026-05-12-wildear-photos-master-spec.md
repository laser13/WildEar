# WildEar Photos Platform Master Spec

> **For agentic workers:** This spec is the top-level design for the photo platform. Implement it only after the linked sub-specs are written and approved. The photo work is intentionally split into smaller specs so each hard problem can be analyzed independently.

**Goal:** Add a first-class photo-observation flow to WildEar with its own `Photos` tab, global camera quick action, CameraX capture, photo review, and iNaturalist upload, while preserving the existing audio pipeline unchanged.

**Architecture:** Keep audio and photo as separate domains. Reuse shared infrastructure where it already fits, but do not force a single generic draft model too early. The existing audio flow continues to own sound recording, post-recording inference, and species-centric submission. The new photo flow owns multi-image albums, capture lifecycle, photo review, and photo-only iNaturalist upload.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose, Room, Hilt, CameraX, OkHttp, iNaturalist REST API, coroutines, Flow.

---

## 1. Why this spec exists

WildEar currently behaves like an audio-first field app. That is still the core use case, but the product direction now includes a parallel visual workflow:

- hear a bird, press record
- see a plant, insect, fungi, track, or other organism, press camera
- inspect nearby observations, open Radar

This master spec exists to define the product shape and the domain boundaries before implementation starts. The hardest part is not camera plumbing by itself; it is avoiding a messy merge of audio and photo concepts into one over-generic draft system.

The main design decision is:

- keep the current audio domain intact
- add a separate photo domain
- connect both through the same app shell and shared iNaturalist client where appropriate

This keeps the work incrementally shippable and makes the future video/GIF discussion much easier.

---

## 2. Current codebase reality

The existing project already contains a useful base for this work:

- `app/src/main/java/com/sound2inat/app/nav/Routes.kt`
  - currently has `HOME`, `RADAR`, `RECORDING`, `REVIEW`, `SETTINGS`
- `app/src/main/java/com/sound2inat/app/nav/RootScaffold.kt`
  - bottom navigation currently shows only Home and Radar
- `app/src/main/java/com/sound2inat/storage/DraftEntity.kt`
  - audio-centric draft model
- `app/src/main/java/com/sound2inat/storage/DraftPhotoEntity.kt`
  - existing habitat photo attachment table for audio drafts
- `app/src/main/java/com/sound2inat/storage/PhotoFileStore.kt`
  - file storage for those audio draft photos
- `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt`
  - already supports `createObservation`, `uploadObservationPhoto`, `deleteObservation`, and taxon lookup helpers
- `app/src/main/java/com/sound2inat/inat/INatSubmitter.kt`
  - audio-specific upload orchestration, not suitable as-is for photo albums
- `app/src/main/java/com/sound2inat/app/permissions/PermissionsController.kt`
  - currently has audio, location, and notification permissions, but no camera permission
- `app/src/main/java/com/sound2inat/storage/Sound2iNatDb.kt`
  - current database version is `7`

Important implication:

- `DraftPhotoEntity` is already used for habitat photos attached to audio observations.
- The new photo album feature must not reuse that table as if it were a generic media album store.

---

## 3. Product shape

### 3.1 Top-level navigation

The app should present three main tabs:

- `Audio`
- `Photos`
- `Radar`

The `Audio` tab continues the current recording/review workflow.
The `Photos` tab becomes the home for photo albums and photo observations.
The `Radar` tab remains a discovery/nearby-observations surface.

### 3.2 Global quick actions

On top-level tabs, the user should always have fast access to:

- `Record audio`
- `Take photos`

These actions should be visible only on top-level browsing surfaces. They should be hidden during capture, review, settings, and other full-screen or modal flows.

### 3.3 Photo UX model

The photo workflow is album-based:

- one camera session creates one photo draft
- the user can capture multiple images into that draft
- the draft is later reviewed as a single iNaturalist observation

The product should treat photo albums as a first-class entity, not as a side attachment of audio observations.

---

## 4. Domain boundaries

### 4.1 Audio domain stays as-is

The current audio domain already includes:

- recording
- inference
- review
- per-species submission

That flow should remain stable while photo work is added.

### 4.2 Photo domain is separate

The new photo domain owns:

- photo album draft lifecycle
- JPEG capture and persistence
- photo album review
- photo upload to iNaturalist

### 4.3 Shared infrastructure

Shared pieces that can be reused:

- app shell and navigation
- permissions framework
- Room database patterns
- iNaturalist client transport and authentication
- file storage conventions

Shared pieces that should not be over-generalized too early:

- draft entities
- review screens
- submitters
- upload state machines

The rule is simple:

- reuse the transport and app shell
- keep the domain models separate

---

## 5. Sub-spec decomposition

This master spec is intentionally broad. The implementation should be decomposed into smaller specs that each own one hard area.

### 5.1 Planned sub-specs

1. Navigation and shell
   - tabs
   - top-level routes
   - global camera/audio quick actions
   - visibility rules

2. Permissions and camera entry
   - camera permission
   - optional location permission behavior
   - entry gating for photo capture

3. Photo storage and Room schema
   - dedicated photo draft entities
   - image rows
   - database migration
   - DAO layer

4. Photo repository and file store
   - file paths
   - CRUD semantics
   - deletion behavior
   - summary projections

5. CameraX capture flow
   - preview
   - image capture
   - lifecycle binding
   - capture session lifecycle

6. Photos tab
   - list view
   - empty state
   - grouping and statuses

7. Photo review flow
   - inspect images
   - add/remove images
   - edit taxon and notes
   - save/upload actions

8. Photo submission
   - iNaturalist observation creation
   - photo upload
   - failure handling
   - persistence of remote IDs and URLs

### 5.2 Dependency order

The recommended order is:

1. navigation and shell
2. permissions and camera entry
3. storage schema
4. repository and file store
5. CameraX capture
6. Photos tab
7. review flow
8. submission

That order keeps each sub-spec buildable on top of the previous one.

---

## 6. Architecture decision: separate photo domain

### 6.1 Decision

Create a dedicated photo domain instead of extending `DraftEntity` into a generic media draft model.

### 6.2 Why

The existing audio draft has fields and behavior that do not fit photos well:

- `audioPath`
- `durationMs`
- inference status
- spectrogram-oriented review
- selected-species submission

Photo albums have different needs:

- multiple JPEGs
- no duration
- no spectrogram
- no detection list
- one observation with multiple photos

Trying to stretch audio into generic media now would create nullable fields, branching UI, and a lot of `if (type == ...)` logic.

### 6.3 What photo domain should own

The photo domain should introduce its own:

- draft entity
- image entity
- repository
- file store
- capture view model
- review view model
- submitter

### 6.4 What remains shared

The photo domain may reuse:

- `INaturalistClient`
- Hilt wiring
- existing location abstractions
- the same app database

But its entities and workflows should be separate.

---

## 7. Data model direction

This spec does not fully define the schema line-by-line. That belongs in the storage sub-spec. It does, however, set the shape of the model.

### 7.1 Photo draft

A photo draft represents a single observation-in-progress.

Core draft fields should conceptually cover:

- identity
- creation/update timestamps
- observed time
- optional GPS coordinates
- optional location accuracy
- status
- taxon information
- user notes
- remote iNaturalist IDs and URLs
- latest upload error

### 7.2 Photo image rows

Each image row should represent one JPEG in the album.

Core image fields should conceptually cover:

- identity
- owning draft id
- file path
- capture timestamp
- ordering within the album
- optional width and height
- MIME type

### 7.3 Summary projection

The list screen should not need to load full image rows for every album card.

The repository should expose a summary shape with:

- first thumbnail path
- photo count
- status
- taxon display fields
- location
- remote URL or error

### 7.4 Status model

The status model should stay simple and durable.

Recommended persistent states:

- pending review
- reviewed
- uploaded

Upload progress should remain runtime UI state, not a database status.

---

## 8. Storage and file path rules

### 8.1 Separate file root

Photo album files should live under their own root directory, not mixed with audio habitat photos.

Recommended shape:

- `filesDir/photo_observations/{photoDraftId}/{photoId}.jpg`

### 8.2 Why this matters

Keeping photo albums separate prevents accidental collisions with the existing audio attachment store and makes cleanup obvious:

- delete draft
- delete image metadata
- delete file tree

### 8.3 File lifecycle

The repository should own file lifecycle rules:

- create file before capture
- register metadata after successful save
- delete temp file on failure
- delete all files when draft is deleted

The camera UI should not manually manage long-lived file cleanup.

---

## 9. CameraX product constraints

### 9.1 MVP camera scope

The first camera milestone should only use:

- `Preview`
- `ImageCapture`

### 9.2 Not in MVP

Do not include these in the first pass:

- `ImageAnalysis`
- video capture
- CameraX extensions
- physical tele/macro lens selection
- depth features
- size estimation

### 9.3 Why

The first version should prove the field workflow:

- open camera fast
- capture multiple photos
- review them
- upload one observation

Additional camera intelligence can come later, but it should not block the core album flow.

---

## 10. Permissions model

### 10.1 Camera permission

Camera permission is required to start capture.

If denied:

- show a clear explanation
- offer a path to app settings
- do not crash or dead-end

### 10.2 Location permission

Location permission is optional for photo capture.

If available:

- store coordinates and accuracy

If not available:

- the photo workflow should still work
- the review screen should show that location is missing

### 10.3 Existing permissions infrastructure

The current permissions controller already abstracts request and status handling. The photo work should extend that model rather than bypass it with ad hoc activity code.

---

## 11. Navigation and shell constraints

### 11.1 Tab rules

The bottom navigation should show the three primary tabs:

- Audio
- Photos
- Radar

### 11.2 Quick action visibility

Show global capture actions only on top-level browsing screens.

Hide them on:

- recording
- photo capture
- audio review
- photo review
- settings
- other full-screen flows

### 11.3 Navigation shape

The new route space should be able to express:

- photo tab entry
- photo capture entry
- photo review entry
- re-entering capture to add more photos to an existing album

The route design should keep those cases distinct instead of overloading one route with too many meanings.

---

## 12. iNaturalist integration rules

### 12.1 Keep the client shared

The existing `INaturalistClient` should remain the transport layer for both audio and photo flows.

### 12.2 Do not reuse the audio submitter

`INatSubmitter` is audio-specific and should remain audio-specific.

Photo submission needs a dedicated submitter because the workflow is different:

- one draft
- one observation
- multiple photo uploads
- no audio crop
- no species-splitting loop

### 12.3 Photo submission behavior

The photo submitter should:

- validate a token exists
- validate at least one image exists
- resolve or confirm taxon selection
- create the observation
- upload every photo
- persist the returned observation ID and URL
- store any warning or error message

### 12.4 Failure policy

Photo upload failures should be handled carefully:

- if observation creation fails, the draft remains unuploaded
- if some photos fail, the album should not be lost
- if all photos fail after observation creation, attempt cleanup where appropriate

The exact failure ladder belongs in the upload sub-spec.

---

## 13. Relationship to the existing audio habitat photos

This is a key boundary and should be explicit in every later sub-spec.

### 13.1 Existing audio habitat photos

The current `draft_photos` feature is part of audio observation support.

It is not a photo album feature.

### 13.2 New photo albums

Photo albums are separate observations with separate storage, separate screens, and separate submission logic.

### 13.3 Why separation matters

This avoids three classes of bugs:

- accidental coupling between audio review and photo review
- schema confusion around which `draftId` owns which rows
- UI complexity from mixing habitat attachments with album media

---

## 14. Implementation milestones

The actual implementation should be split into sub-specs, but the milestone sequence is:

1. shell and navigation
2. permissions and camera entry
3. schema and repository
4. CameraX capture
5. Photos tab
6. review
7. upload

This is deliberately staged so the app can remain usable even if the camera work takes longer than expected.

---

## 15. Risks and design guardrails

### 15.1 Risk: over-generic media abstraction

Guardrail:

- keep audio and photo separate
- only share transport and app shell
- postpone media unification until video is real

### 15.2 Risk: navigation clutter

Guardrail:

- only top-level tabs get global actions
- capture/review screens hide them

### 15.3 Risk: photo capture race conditions

Guardrail:

- camera UI owns lifecycle-bound CameraX objects
- view models own only draft state and file/repo operations

### 15.4 Risk: partial upload failure

Guardrail:

- preserve the draft
- preserve already uploaded state where possible
- surface a clear error string for retry

### 15.5 Risk: scope creep into advanced camera features

Guardrail:

- do not add extensions, zoom UX, autofocus, or video until the base album flow is verified

---

## 16. Open questions for later sub-specs

These questions are deliberately not solved here:

- exact photo review layout
- whether draft creation happens immediately on camera open or only after first shot
- whether GPS should be required for upload
- how to present manual taxon entry versus future autocomplete
- whether album status should expose a separate "uploading" runtime state in the UI only
- whether the Photos tab is grouped by day or shown as a flat feed first

The sub-specs should answer those questions one by one instead of mixing them together.

---

## 17. Recommended next documents

Write these in order after this master spec:

1. `docs/superpowers/specs/2026-05-12-wildear-photos-navigation-design.md`
2. `docs/superpowers/specs/2026-05-12-wildear-photos-permissions-camera-entry-design.md`
3. `docs/superpowers/specs/2026-05-12-wildear-photos-storage-repository-design.md`
4. `docs/superpowers/specs/2026-05-12-wildear-photos-camerax-capture-design.md`
5. `docs/superpowers/specs/2026-05-12-wildear-photos-list-review-upload-design.md`

Those documents should reference this master spec and should not restate the entire product vision.

---

## 18. Approval checkpoint

This master spec is meant to be reviewed before any implementation planning begins.

Once approved, the next step is to write the first sub-spec, starting with navigation and shell behavior, because that unlocks the visible product structure without touching the camera or database yet.
