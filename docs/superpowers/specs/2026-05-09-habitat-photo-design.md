# Habitat Photo — Attach Landscape Photo to iNat Observations

**Date:** 2026-05-09
**Scope:** Allow users to photograph the habitat/landscape while recording and optionally attach those photos to iNaturalist observations on a per-species basis.

---

## Problem

WildEar observations on iNaturalist contain only audio evidence. For ground-level recordings (in bushes, at water edges, dense vegetation), a habitat photo gives identifiers useful context about the environment. For aerial recordings (swifts, raptors) it's unnecessary. Currently there is no way to attach any photo to a WildEar observation.

## Goal

Let the user take one or more habitat photos during or after a recording. When submitting to iNaturalist, each species row has a checkbox to include or exclude the photo(s). Photos are uploaded to the corresponding observation via the iNat `/observation_photos` API.

---

## Design

### 1. Data Layer

**New Room entity** `DraftPhotoEntity` in the `storage` module:

```kotlin
@Entity(tableName = "draft_photos")
data class DraftPhotoEntity(
    @PrimaryKey val id: String,        // UUID
    val draftId: String,               // FK → DraftEntity.id
    val photoPath: String,             // absolute path to JPG on disk
    val takenAtMs: Long,               // epoch millis
)
```

**DAO** — `DraftPhotoDao`:
- `insert(photo: DraftPhotoEntity)`
- `deleteById(id: String)`
- `deleteByDraftId(draftId: String)`
- `photosForDraft(draftId: String): Flow<List<DraftPhotoEntity>>`

**Storage path:** `context.getExternalFilesDir("habitat_photos")/{draftId}/{uuid}.jpg`

`DraftEntity` is not modified. The "include photo" decision is per-submission UI state only — not persisted.

**Cascade delete:** `DefaultDraftRepository.delete()` calls `DraftPhotoDao.deleteByDraftId()` and deletes the JPG files from disk before or after removing the `DraftEntity` row.

---

### 2. Camera Capture

Uses `ActivityResultContracts.TakePicture()` — launches the system camera app, returns a result to the calling composable/fragment. No new library dependencies.

**Flow:**
1. User taps the camera button.
2. App creates an empty JPG file at the storage path and exposes it via `FileProvider`.
3. `TakePicture` contract is launched with the `FileProvider` URI.
4. On `result = true`: insert a `DraftPhotoEntity` row and emit the new photo list.
5. On `result = false` (user cancelled or camera failed): delete the empty file.

`FileProvider` authority: `com.sound2inat.app.fileprovider` (must be declared in `AndroidManifest.xml` and backed by a `res/xml/file_paths.xml` entry for `external-files-path`).

---

### 3. UI — Recording Screen

A small camera icon button placed next to the GPS indicator row. Tapping it launches the system camera mid-recording. The draft already exists at this point (created when recording starts), so the photo is immediately associated with it.

After a photo is taken, a badge `📷 N` appears next to the camera icon (N = count of photos taken so far). No thumbnail strip on this screen — keeps the recording UI minimal.

---

### 4. UI — Review Screen

**Habitat photo strip** — shown between the waveform/spectrogram area and the species list. Visible only when ≥ 1 photo exists for the draft:

```
[ + Add ]  [ thumbnail ]  [ thumbnail ]  →  horizontal scroll
```

- **+ Add** button: launches system camera.
- **Thumbnail tap**: opens a full-screen viewer with a Delete button (removes the photo from disk and DB).
- When no photos exist: the strip is hidden entirely (takes no vertical space).

**Per-species checkbox** — shown on each species row only when ≥ 1 habitat photo exists:

```
[ ✓ ] American Robin          ← species checkbox (existing)
      📷 Include habitat photo ← new, small secondary checkbox; default: ON
```

The "Include habitat photo" checkbox defaults to `true` when photos are present. The user can disable it per species (e.g., aerial species that don't need habitat context).

`SpeciesRow` in `ReviewUiState` gains:
```kotlin
val includeHabitatPhoto: Boolean = true
```

`ReviewUiState` gains:
```kotlin
val habitatPhotos: List<DraftPhotoEntity> = emptyList()
```

---

### 5. iNat Submission

`INatSubmitter.submit()` receives the list of photos to upload per observation. For each species where `includeHabitatPhoto = true` and `habitatPhotos.isNotEmpty()`:

After `POST /observation_sounds` succeeds, for each photo file:

```
POST /observation_photos
Content-Type: multipart/form-data

observation_photo[observation_id] = <observation UUID>
file                              = <JPG binary>
```

Photos are uploaded sequentially. A failure on any individual photo is logged and skipped — it does **not** roll back the observation or audio upload (same best-effort policy as existing annotation endpoints).

The observation `description` is not modified — iNat displays photos in the observation card automatically.

---

## Files

| File | Action |
|------|--------|
| `app/src/main/java/com/sound2inat/storage/DraftPhotoEntity.kt` | New entity |
| `app/src/main/java/com/sound2inat/storage/DraftPhotoDao.kt` | New DAO |
| `app/src/main/java/com/sound2inat/storage/Sound2iNatDatabase.kt` | Add entity + DAO, bump schema version |
| `app/schemas/com.sound2inat.storage.Sound2iNatDb/` | New migration JSON |
| `app/src/main/java/com/sound2inat/app/data/DraftRepository.kt` | Cascade-delete photos on draft delete |
| `app/src/main/res/xml/file_paths.xml` | New: FileProvider paths config |
| `app/src/main/AndroidManifest.xml` | Add FileProvider + CAMERA permission |
| `app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt` | Camera icon button + badge |
| `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt` | Handle photo capture, insert DraftPhotoEntity |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt` | Habitat photo strip + per-species checkbox |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` | Expose habitatPhotos flow, per-species toggle |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt` | Add habitatPhotos, includeHabitatPhoto to SpeciesRow |
| `app/src/main/java/com/sound2inat/inat/INatSubmitter.kt` | Upload photos via /observation_photos |
| `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt` | Add uploadObservationPhoto() method |
| `app/src/main/res/values/strings.xml` | Add strings for new UI elements |

---

## Non-Goals

- In-app camera preview (CameraX) — system camera is sufficient
- Per-photo crop or edit
- Uploading photos to already-submitted observations (only at submit time)
- Syncing deleted photos back to iNat after upload
- Video capture
