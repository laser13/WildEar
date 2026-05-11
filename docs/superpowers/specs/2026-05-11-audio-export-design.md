# Audio Export / Share — Design Spec

**Date:** 2026-05-11  
**Feature:** Share and Save audio files from recordings and species clips

---

## Overview

Add two export actions — **Share** (Android Sharesheet) and **Save to device** (MediaStore) — for both full recordings and per-species cropped clips. No changes to the recording pipeline, inference, or iNaturalist submission logic.

---

## Architecture

### New: `AudioExportManager`

```
com.sound2inat.app.AudioExportManager
```

Hilt singleton via `@Inject constructor`. No `@Provides` needed in `AppModule`.

```kotlin
@Singleton
class AudioExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun saveToDownloads(file: File, displayName: String): Uri
}
```

`AudioExportManager` is responsible only for **saving** (MediaStore). Share intent construction is handled in the Composable (it needs `LocalContext` and is trivial — ~5 lines). This keeps `AudioExportManager` off the UI layer entirely.

**`saveToDownloads`:**
- Inserts a `MediaStore.Audio.Media` entry with:
  - `DISPLAY_NAME` = `displayName`
  - `MIME_TYPE` = `"audio/wav"`
  - `RELATIVE_PATH` = `Environment.DIRECTORY_DOWNLOADS + "/WildEar"` (Android 10+)
- Opens the output stream, copies bytes from `file` using `use`.
- On copy failure: deletes the partial MediaStore entry via `context.contentResolver.delete(uri, null, null)`.
- Returns the `Uri` on success.
- This is a suspend function; callers must dispatch on `Dispatchers.IO`.

---

### Modified: `ReviewUiState`

Add two fields:

```kotlin
val exportingAction: ExportingAction? = null,
val exportEffect: ReviewExportEffect? = null,   // one-shot, cleared after consumption
```

```kotlin
sealed interface ExportingAction {
    data object FullRecordingShare : ExportingAction
    data object FullRecordingSave  : ExportingAction
    data class  SpeciesClipShare(val detectionId: Long) : ExportingAction
    data class  SpeciesClipSave (val detectionId: Long) : ExportingAction
}

sealed interface ReviewExportEffect {
    data class ShareAudioFile(val file: File)   : ReviewExportEffect
    data class ShowSnackbar(val message: String) : ReviewExportEffect
}
```

`ReviewExportEffect` is a one-shot field: the screen clears it by calling `vm.consumeExportEffect()` immediately after collecting it. This avoids duplicate shows on recomposition and removes the need for a `Channel`.

---

### Modified: `ReviewViewModel`

Inject `AudioExportManager` via `ReviewViewModelFactory` (not directly into the VM constructor — the factory pattern is already established).

Add to constructor: `private val audioExport: AudioExportManager`.

**New public methods:**

```kotlin
fun onShareFullRecording()
fun onSaveFullRecording()
fun onShareSpeciesClip(row: SpeciesRow)
fun onSaveSpeciesClip(row: SpeciesRow)
fun consumeExportEffect()
```

**Internal helper:**
```kotlin
private suspend fun prepareSpeciesClip(row: SpeciesRow): File
```

Uses `WavTrimmer.trimMono16` with the same `firstSeenMs - PADDING_MS .. lastSeenMs + PADDING_MS` bounds as `INatSubmitter.cropPerSpecies`. Clip files live in `cacheDir/export_clips/`. Filename is stable:

```
<draftId>__<sanitizedSpecies>__<firstSeenMs>_<lastSeenMs>.wav
```

If the file already exists and `size > 0`, it is reused without re-trimming.

**Flow for Share (full recording):**
1. `onShareFullRecording()` sets `exportingAction = FullRecordingShare`.
2. Gets `audioPath` from state; validates file exists.
3. Calls `audioExport.createShareIntent(File(audioPath))`.
4. Emits `ReviewExportEffect.ShareAudioFile(file)`.
5. Clears `exportingAction`.

**Flow for Share (species clip):**
1. `onShareSpeciesClip(row)` sets `exportingAction = SpeciesClipShare(row.detectionId)`.
2. Launches coroutine on `ioDispatcher`:  
   `val clip = prepareSpeciesClip(row)`
3. Calls `audioExport.createShareIntent(clip)`.
4. Emits `ReviewExportEffect.ShareAudioFile(clip)`.
5. Clears `exportingAction`.

**Flow for Save (full recording or clip):**
1. Sets appropriate `exportingAction`.
2. Calls `audioExport.saveToDownloads(file, displayName)` on `ioDispatcher`.
3. On success: emits `ReviewExportEffect.ShowSnackbar("Audio saved to Downloads")`.
4. On failure: emits `ReviewExportEffect.ShowSnackbar("Could not save audio")`.
5. Clears `exportingAction`.

**Error handling in all actions:**
- File missing → `ShowSnackbar("Audio file is missing")`
- File empty → `ShowSnackbar("Audio file is empty")`
- Any other exception → `ShowSnackbar("Could not share audio")` / `"Could not save audio"`

---

### Modified: `ReviewViewModelFactory`

Inject `AudioExportManager` and pass it into `ReviewViewModel.create(...)`.

---

### Modified: `ReviewScreen.kt`

**`PlayerControls` composable** — add two `IconButton`s after the Play/Pause button:

```
[Play/Pause]  [00:42 / 03:15]  [Share ↑]  [Save ⬇]
```

- Share button: spinner while `exportingAction is FullRecordingShare`, else `Icons.Outlined.Share`.
- Save button: spinner while `exportingAction is FullRecordingSave`, else `Icons.Outlined.FileDownload` (or `SaveAlt`).
- Both call `vm.onShareFullRecording()` / `vm.onSaveFullRecording()`.

**Effect collection** (in `ReviewPage` body, via `LaunchedEffect`).

The share intent is built inline in the Composable — `AudioExportManager` stays off the UI layer. `ActivityNotFoundException` is caught to show a Snackbar instead of crashing if no app can handle the intent.

```kotlin
val snackbarHostState = remember { SnackbarHostState() }

LaunchedEffect(state.exportEffect) {
    when (val effect = state.exportEffect) {
        is ReviewExportEffect.ShareAudioFile -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(
                    Intent.EXTRA_STREAM,
                    FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, effect.file),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, null))
            } catch (_: ActivityNotFoundException) {
                snackbarHostState.showSnackbar("Could not share audio")
            }
            vm.consumeExportEffect()
        }
        is ReviewExportEffect.ShowSnackbar -> {
            snackbarHostState.showSnackbar(effect.message)
            vm.consumeExportEffect()
        }
        null -> Unit
    }
}
```

**`Scaffold`** needs a `SnackbarHost`: `ReviewPage`'s current `Scaffold` has no `snackbarHost` — add `snackbarHost = { SnackbarHost(snackbarHostState) }` and pass `snackbarHostState` down.

---

### Modified: `SpeciesDetailsSheet.kt`

Add a "Share clip / Save clip" row at the bottom of the sheet (after the time-ranges block, before the iNat section):

```
[Share clip ↑]  [Save clip ⬇]
```

Implemented as `OutlinedButton`s (matching the player controls style) or `TextButton`s — match the existing button style in the sheet (`TextButton` used for "View" and "Retry").

Use `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))` with two buttons.

Each button shows `CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)` in place of the icon/text while `exportingAction` matches this species. Loading state check:

```kotlin
val isShareLoading = exportingAction is ExportingAction.SpeciesClipShare &&
    exportingAction.detectionId == row.detectionId
```

The sheet receives `exportingAction` and the two callbacks as parameters. It does **not** hold its own `SnackbarHost` — snackbars are shown by the parent `ReviewPage`.

Signature addition:
```kotlin
internal fun SpeciesDetailsSheet(
    ...existing params...,
    exportingAction: ExportingAction? = null,
    onShareClip: () -> Unit = {},
    onSaveClip: () -> Unit = {},
)
```

---

### Modified: `file_paths.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- Existing: habitat photos in internal storage -->
    <files-path
        name="habitat_photos_internal"
        path="habitat_photos/" />

    <!-- Existing: habitat photos in external app storage -->
    <external-files-path
        name="habitat_photos"
        path="habitat_photos/" />

    <!-- New: full recordings for share -->
    <files-path
        name="recordings"
        path="recordings/" />

    <!-- New: temporary export clips in cache -->
    <cache-path
        name="export_clips"
        path="export_clips/" />
</paths>
```

---

## File naming

| Type             | Example                                                   |
|------------------|-----------------------------------------------------------|
| Full recording   | `recording_2026-05-11_14-30-00.wav`                       |
| Species clip     | `clip_Sylvia_atricapilla_2026-05-11_14-30-00.wav`         |

`displayName` for MediaStore is derived from the draft's `recordedAtUtcMs`. Species name is sanitized (`[^A-Za-z0-9]+` → `_`), matching `INatSubmitter.cropFileName` convention.

---

## Error handling matrix

| Condition                      | Action         | User message                |
|-------------------------------|----------------|-----------------------------|
| Audio file missing             | Share / Save   | "Audio file is missing"     |
| Audio file empty               | Share / Save   | "Audio file is empty"       |
| No app to handle share intent  | Share          | "Could not share audio"     |
| MediaStore insert failed       | Save           | "Could not save audio"      |
| Bytes copy failed              | Save           | "Could not save audio"      |
| MediaStore entry cleaned up    | (internal)     | "Could not save audio"      |

ActivityNotFoundException (no share target) is caught around `startActivity(chooser)` in the Composable.

---

## What is NOT changed

- `INatSubmitter` — untouched; it keeps its own `cropPerSpecies` / `WavTrimmer` call.
- Recording/playback pipeline — untouched.
- `WavTrimmer` — used as-is (public object, no modifications needed).
- `WRITE_EXTERNAL_STORAGE` permission — not requested; MediaStore on API 29+ does not need it.

---

## Manual QA checklist

**Share — full recording:**
- [ ] Tap Share on Review screen → Sharesheet opens
- [ ] Share to Telegram — file arrives and can be played
- [ ] Share to Gmail — file attaches
- [ ] Share to Google Drive — file uploads

**Share — species clip:**
- [ ] Open SpeciesDetailsSheet → tap Share clip → spinner shows → Sharesheet opens
- [ ] Clip duration matches detection window ± 1 s padding
- [ ] Share to Telegram — clip plays correctly

**Save:**
- [ ] Save full recording → Toast "Audio saved to Downloads"
- [ ] File visible in Files app under Downloads/WildEar/
- [ ] File playable in Files app
- [ ] Save species clip → correct file name includes species name

**Edge cases:**
- [ ] Share when audio file deleted → Snackbar "Audio file is missing"
- [ ] Filename with spaces / non-Latin species name → file saved correctly
- [ ] Double-tap Share → only one Sharesheet opens (spinner disabled during export)
- [ ] Second share of same clip reuses cached file (no re-trim)
- [ ] Android 10, 13, 14 tested
