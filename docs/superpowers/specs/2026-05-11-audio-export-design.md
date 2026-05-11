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

`AudioExportManager` is responsible only for **saving to public storage** (MediaStore). Share intent construction is handled in the Composable — it is ~5 lines using `LocalContext` and does not belong in a class. This keeps `AudioExportManager` off the UI layer entirely.

**`saveToDownloads` — implementation:**

`AudioExportManager` does **not** switch dispatchers internally. The caller (ReviewViewModel) must invoke it from `ioDispatcher`.

**API 29+ (Android 10+) only.** On API 28, `saveToDownloads` must immediately throw `UnsupportedOperationException("API 29+ required")` — the caller (VM) catches this and emits `ShowSnackbar("Saving to Downloads is not supported on this Android version")`. No `WRITE_EXTERNAL_STORAGE` permission is added.

On API 29+:

- Defensively verify `file.exists() && file.isFile && file.length() > 0` before inserting into MediaStore; throw `IllegalArgumentException` if the check fails.
- Insert via `MediaStore.Downloads.EXTERNAL_CONTENT_URI` with:
  - `DISPLAY_NAME` = `displayName`
  - `MIME_TYPE` = `"audio/wav"`
  - `RELATIVE_PATH` = `Environment.DIRECTORY_DOWNLOADS + "/WildEar"`
- Open output stream, copy bytes from `file` with `use`.
- On copy failure: delete the partial entry via `contentResolver.delete(uri, null, null)`, then rethrow.
- Return `Uri` on success.

Full recording **Share** does not copy the file — FileProvider exposes the original `filesDir/recordings/<id>.wav` directly. Full recording **Save** copies the file to public storage. **Share works on all API levels (28+) via FileProvider.**

---

### Modified: `ReviewUiState`

Add two fields:

```kotlin
val exportingAction: ExportingAction? = null,
val exportEffect: ReviewExportEffect? = null,   // one-shot, cleared before the side effect fires
```

```kotlin
sealed interface ExportingAction {
    data object FullRecordingShare : ExportingAction
    data object FullRecordingSave  : ExportingAction
    data class  SpeciesClipShare(val detectionId: Long) : ExportingAction
    data class  SpeciesClipSave (val detectionId: Long) : ExportingAction
}

sealed interface ReviewExportEffect {
    data class ShareAudioFile(val file: File)    : ReviewExportEffect
    data class ShowSnackbar(val message: String) : ReviewExportEffect
}
```

`ReviewExportEffect` is a one-shot field. The screen must call `vm.consumeExportEffect()` **before** executing the side effect (before `startActivity` or before `showSnackbar`). This prevents the effect from being re-triggered by recomposition or config change, since by the time the side effect runs the state field is already `null`.

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

Uses `WavTrimmer.trimMono16`. Bounds must be clamped before the call:

```kotlin
val startMs = maxOf(0L, row.firstSeenMs - PADDING_MS)
val endMs   = minOf(recordingDurationMs, row.lastSeenMs + PADDING_MS)
require(endMs > startMs) { "clip range is empty" }
```

`PADDING_MS = 1000` (same constant as `INatSubmitter`). `recordingDurationMs` is read from `_state.value.durationMs`.

`prepareSpeciesClip` is a pure helper — it **throws** on any failure (invalid bounds, `WavTrimmer` error, I/O error). It has no knowledge of UI messages. The calling action method (`onShareSpeciesClip` / `onSaveSpeciesClip`) maps all exceptions to the appropriate `ShowSnackbar` in its `catch` block.

Clip files live in `cacheDir/export_clips/`. Filename is stable:

```
<draftId>__<sanitizedSpecies>__<firstSeenMs>_<lastSeenMs>.wav
```

If the file already exists and `file.length() > 0`, it is reused without re-trimming.

`cacheDir/export_clips/` is created with `mkdirs()` on first use.

---

All four action methods follow the same try/catch/finally structure:

```kotlin
// exportingAction is set at the top of each method (before the try block)
try {
    // validate, prepare, call audioExport, emit effect
} catch (_: UnsupportedOperationException) {
    // API 28 save path only
    emitEffect(ShowSnackbar("Saving to Downloads is not supported on this Android version"))
} catch (_: Exception) {
    emitEffect(ShowSnackbar("Could not share/save audio"))   // use specific message per action
} finally {
    clearExportingAction()   // always clears, even on uncaught errors
}
```

**Flow for Share (full recording):**

1. Set `exportingAction = FullRecordingShare`.
2. `try`: validate `File(audioPath)` exists and `length() > 0`; if not, throw `IllegalStateException`.
3. Emit `ReviewExportEffect.ShareAudioFile(File(audioPath))`.
4. `catch (Exception)` → emit `ShowSnackbar("Audio file is missing")` (file check) or `"Could not share audio"` (other).
5. `finally` → clear `exportingAction`. The Composable builds and starts the share intent.

**Flow for Share (species clip):**

1. Set `exportingAction = SpeciesClipShare(row.detectionId)`.
2. `try` on `ioDispatcher`: `val clip = prepareSpeciesClip(row)` (throws on any failure).
3. Emit `ReviewExportEffect.ShareAudioFile(clip)`.
4. `catch (Exception)` → emit `ShowSnackbar("Could not share audio")`.
5. `finally` → clear `exportingAction`. The Composable builds and starts the share intent.

**Flow for Save (full recording):**

1. Set `exportingAction = FullRecordingSave`.
2. `try` on `ioDispatcher`: validate file, then `audioExport.saveToDownloads(file, displayName)`.
3. On success → emit `ShowSnackbar("Audio saved to Downloads")`.
4. `catch (UnsupportedOperationException)` → emit `ShowSnackbar("Saving to Downloads is not supported on this Android version")`.
5. `catch (Exception)` → emit `ShowSnackbar("Could not save audio")`.
6. `finally` → clear `exportingAction`.

**Flow for Save (species clip):**

1. Set `exportingAction = SpeciesClipSave(row.detectionId)`.
2. `try` on `ioDispatcher`: `prepareSpeciesClip(row)` then `audioExport.saveToDownloads(clip, displayName)`.
3. On success → emit `ShowSnackbar("Audio saved to Downloads")`.
4. `catch (UnsupportedOperationException)` → emit `ShowSnackbar("Saving to Downloads is not supported on this Android version")`.
5. `catch (Exception)` → emit `ShowSnackbar("Could not save audio")`.
6. `finally` → clear `exportingAction`.

---

### Modified: `ReviewViewModelFactory`

Inject `AudioExportManager` and pass it into `ReviewViewModel.create(...)`.

---

### Modified: `ReviewScreen.kt`

**`PlayerControls` composable** — add two `IconButton`s after the Play/Pause button:

```
[Play/Pause]  [00:42 / 03:15]  [Share ↑]  [Save ⬇]
```

- Share button: `CircularProgressIndicator` while `exportingAction is FullRecordingShare`, else `Icons.Outlined.Share`. Disabled while any `exportingAction` is active.
- Save button: `CircularProgressIndicator` while `exportingAction is FullRecordingSave`, else `Icons.Outlined.FileDownload`. Disabled while any `exportingAction` is active.

**Effect collection** — added to `ReviewPage` body:

`FILE_PROVIDER_AUTHORITY` is the existing constant from `UiUtils.kt`. Do not hardcode the string in new code. Verify it matches the `android:authorities` value in `AndroidManifest.xml` before using it here.

```kotlin
val snackbarHostState = remember { SnackbarHostState() }

LaunchedEffect(state.exportEffect) {
    val effect = state.exportEffect ?: return@LaunchedEffect
    vm.consumeExportEffect()               // consume FIRST, before any side effect
    when (effect) {
        is ReviewExportEffect.ShareAudioFile -> {
            val uri = FileProvider.getUriForFile(
                context, FILE_PROVIDER_AUTHORITY, effect.file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                // ClipData ensures URI permissions propagate to all receiving apps
                clipData = ClipData.newUri(context.contentResolver, effect.file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, null))
            } catch (_: ActivityNotFoundException) {
                snackbarHostState.showSnackbar("Could not share audio")
            }
        }
        is ReviewExportEffect.ShowSnackbar -> {
            snackbarHostState.showSnackbar(effect.message)
        }
    }
}
```

**`Scaffold`** needs a `SnackbarHost`: `ReviewPage`'s current `Scaffold` has no `snackbarHost`. Add:

```kotlin
snackbarHost = { SnackbarHost(snackbarHostState) }
```

---

### Modified: `SpeciesDetailsSheet.kt`

Add a row with "Share clip" and "Save clip" buttons after the time-ranges block, before the iNat section:

```
[Share clip ↑]   [Save clip ⬇]
```

Use `TextButton`s (matching the existing "View" / "Retry" style in the sheet). Each shows `CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)` instead of text while loading.

Loading state check:

```kotlin
val isShareLoading = exportingAction is ExportingAction.SpeciesClipShare &&
    (exportingAction as ExportingAction.SpeciesClipShare).detectionId == row.detectionId
val isSaveLoading = exportingAction is ExportingAction.SpeciesClipSave &&
    (exportingAction as ExportingAction.SpeciesClipSave).detectionId == row.detectionId
```

The sheet does **not** hold a `SnackbarHost` — snackbars are shown by the parent `ReviewPage`.

Signature addition:

```kotlin
internal fun SpeciesDetailsSheet(
    // ...existing params unchanged...
    exportingAction: ExportingAction? = null,
    onShareClip: () -> Unit = {},
    onSaveClip: () -> Unit = {},
)
```

---

### Modified: `file_paths.xml`

Recording files are confirmed to live in `context.filesDir/recordings/` (via `WavFileStore`). Export clips live in `context.cacheDir/export_clips/`.

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- Existing -->
    <files-path name="habitat_photos_internal" path="habitat_photos/" />
    <external-files-path name="habitat_photos" path="habitat_photos/" />

    <!-- New: full recordings for FileProvider share -->
    <files-path name="recordings" path="recordings/" />

    <!-- New: temporary export clips -->
    <cache-path name="export_clips" path="export_clips/" />
</paths>
```

---

## File naming

| Type           | MediaStore displayName example                          |
|----------------|--------------------------------------------------------|
| Full recording | `recording_2026-05-11_14-30-00.wav`                    |
| Species clip   | `clip_Sylvia_atricapilla_2026-05-11_14-30-00.wav`      |

`displayName` is derived from the draft's `recordedAtUtcMs`. Species name is sanitized (`[^A-Za-z0-9]+` → `_`), matching the convention in `INatSubmitter.cropFileName`.

---

## Error handling matrix

| Condition                          | Action       | User-visible Snackbar                                          |
|------------------------------------|--------------|----------------------------------------------------------------|
| Condition                          | Action       | User-visible Snackbar                                          |
|------------------------------------|--------------|----------------------------------------------------------------|
| Audio file missing or empty        | Share / Save | "Audio file is missing"                                        |
| prepareSpeciesClip failure         | Share clip   | "Could not share audio"                                        |
| prepareSpeciesClip failure         | Save clip    | "Could not save audio"                                         |
| No app for share intent            | Share        | "Could not share audio"                                        |
| MediaStore insert or copy failed   | Save         | "Could not save audio"                                         |
| API 28 (UnsupportedOperationException) | Save     | "Saving to Downloads is not supported on this Android version" |

All user messages are Snackbars — no Toasts.

---

## What is NOT changed

- `INatSubmitter` — untouched; it keeps its own `cropPerSpecies` / `WavTrimmer` call.
- Recording / playback pipeline — untouched.
- `WavTrimmer` — used as-is (public object, no modifications needed).
- `AndroidManifest.xml` — `FileProvider` is already declared; only `file_paths.xml` needs updating.
- `WRITE_EXTERNAL_STORAGE` — not requested. Save is only implemented for API 29+ where Scoped Storage removes the need. On API 28, Save shows a Snackbar instead of attempting a MediaStore write.

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
- [ ] Second tap reuses cached clip file (no re-trim)

**Save:**

- [ ] Save full recording → Snackbar "Audio saved to Downloads"
- [ ] File visible in Files app under Downloads/WildEar/ (API 29+)
- [ ] File playable in Files app
- [ ] Save species clip → file name includes species name

**Edge cases:**

- [ ] Share when audio file deleted → Snackbar "Audio file is missing"
- [ ] Species name with non-Latin characters → clip filename sanitized correctly
- [ ] Double-tap Share → only one action runs (buttons disabled while exportingAction is set)
- [ ] No share target available → Snackbar "Could not share audio" (no crash)
- [ ] Android 9 (API 28) — Save shows Snackbar "not supported"; Share still works
- [ ] Android 10, 13, 14 — Save lands in Downloads/WildEar/
