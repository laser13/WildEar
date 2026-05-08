# Reanalyze: Skip YAMNet Gate + Multi-Model Checkbox Dialog

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** During user-triggered re-analysis, skip the YAMNet gate (it currently adds overhead without skipping model inference); change the re-analysis dialog from two separate buttons to checkboxes so both BirdNET and Perch can be selected and run sequentially.

**Architecture:** Two sequential tasks. Task 1 adds `inferenceReanalysis` and `perchReanalysis` to `InferenceUseCase` (both always pass `yamNetGate = null`) and adds `reanalyze(runBirdnet, runPerch)` to `ReviewViewModel`. Task 2 rewrites `ModelPickerDialog` with checkboxes and calls `vm.reanalyze()`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Hilt, Coroutines, TFLite.

---

## Background — Why YAMNet Doesn't Help During Re-Analysis

`InferenceRunner.kt:74–92` always calls both `yamNetGate?.classify()` AND `model.predict()` on every window. YAMNet only post-filters results — it never skips a `model.predict()` call. So YAMNet adds ~30 % overhead with zero inference savings. During re-analysis the user has explicitly chosen a model, so even post-filtering is undesirable.

The fix: expose separate "no-gate" job instances (`inferenceReanalysis`, `perchReanalysis`) that pass `yamNetGate = null` to `InferenceRunner`. Initial auto-analysis (`PENDING_INFERENCE`) is unchanged.

---

## File Structure

| File | Action |
|------|--------|
| `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt` | Add `inferenceReanalysis` + `perchReanalysis` to interface and `DefaultInferenceUseCase` |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` | Add two constructor params with defaults; add `reanalyze()` function; update factory wiring |
| `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt` | Add three tests for `reanalyze()` |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt` | Rewrite `ModelPickerDialog`; update call site |
| `app/src/main/res/values/strings.xml` | Update `dialog_reanalyze_body` text |

---

### Task 1: No-gate inference jobs + `reanalyze()` in ViewModel

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
- Modify: `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt`

> **Do NOT run tests** — user runs them manually. Write the test code; leave execution to the user.

- [ ] **Step 1: Read the files before editing**

Read these four files in full before making any changes:
- `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt`
- `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` (the full file — the factory `ReviewViewModelFactory` is at the end)
- `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt`
- `app/src/test/java/com/sound2inat/inference/InferenceUseCaseTest.kt`

Pay attention to:
- The current `InferenceUseCase` interface shape (currently has `inference` and `perchAnalysis`)
- How `DefaultInferenceUseCase` creates `ProductionInferenceJob` and `ProductionPerchAnalysisJob`
- The `ReviewViewModel` constructor parameter list and their defaults
- How `ReviewViewModelFactory.create()` wires params
- Whether `InferenceUseCaseTest` implements or mocks `InferenceUseCase` (update it if it does)

- [ ] **Step 2: Extend `InferenceUseCase` interface and `DefaultInferenceUseCase`**

In `InferenceUseCase.kt`, add two properties to the interface:

```kotlin
interface InferenceUseCase {
    val inference: InferenceJob
    /** YAMNet gate always disabled — use for user-triggered re-analysis. */
    val inferenceReanalysis: InferenceJob
    val perchAnalysis: PerchAnalysisJob
    /** YAMNet gate always disabled — use for user-triggered re-analysis. */
    val perchReanalysis: PerchAnalysisJob
}
```

In `DefaultInferenceUseCase`, add the two implementations right after the existing `inference` and `perchAnalysis` properties. Pass `yamNetGate = null` — that's the only difference from their gated counterparts:

```kotlin
override val inferenceReanalysis: InferenceJob = ProductionInferenceJob(
    models = models,
    descriptors = descriptors,
    modelManager = modelManager,
    settings = settings,
    yamNetGate = null,
    birdNetMeta = birdNetMeta,
)

override val perchReanalysis: PerchAnalysisJob = ProductionPerchAnalysisJob(
    models = models,
    modelManager = modelManager,
    settings = settings,
    yamNetGate = null,
)
```

- [ ] **Step 3: Add two constructor params to `ReviewViewModel`**

In `ReviewViewModel` class header, add `inferenceReanalysis` and `perchReanalysis` after the existing `perchAnalysis` param. Both default to their gated counterparts so all existing tests compile without changes:

```kotlin
private val inferenceReanalysis: InferenceJob = inference,
private val perchReanalysis: PerchAnalysisJob = perchAnalysis,
```

Insert them after the `perchAnalysis` param and before `perchInstalledProbe`.

- [ ] **Step 4: Add `reanalyze()` function to `ReviewViewModel`**

Add this function near `reanalyzeBirdnet()` (around line 492). It runs BirdNET then Perch sequentially in one coroutine, both without YAMNet gate:

```kotlin
/**
 * User-triggered re-analysis. Skips the YAMNet gate (the gate adds overhead
 * without skipping model inference — see InferenceRunner). Runs the selected
 * models sequentially: BirdNET first (if [runBirdnet]), then Perch (if [runPerch]).
 * No-op when either progress field is already active.
 */
fun reanalyze(runBirdnet: Boolean, runPerch: Boolean) {
    if (!runBirdnet && !runPerch) return
    if (_state.value.inferenceProgress != null || _state.value.perchProgress != null) return
    val path = _state.value.audioPath ?: return
    val lat = _state.value.latitude
    val lon = _state.value.longitude
    val recordedAt = _state.value.recordedAtUtcMs
    inferenceJob?.cancel()
    perchJob?.cancel()
    inferenceStarted = true
    _windowPreds.value = emptyList()
    inferenceJob = scope.launch {
        if (runBirdnet) {
            _state.value = _state.value.copy(inferenceError = null, inferenceProgress = 0f)
            val outcome = inferenceReanalysis.run(path, lat, lon, recordedAt) { p ->
                _state.value = _state.value.copy(inferenceProgress = p.coerceIn(0f, 1f))
            }
            when (outcome) {
                is InferenceOutcome.Success -> {
                    repo.mergeAndPersist(
                        draftId = draftId,
                        newModelId = outcome.modelId,
                        newModelVersion = outcome.modelVersion,
                        freshDetections = outcome.detections,
                        promoteToReviewed = true,
                    )
                    _windowPreds.value = outcome.windows
                    _state.value = _state.value.copy(inferenceProgress = null)
                }
                is InferenceOutcome.Failure -> {
                    _state.value = _state.value.copy(
                        inferenceProgress = null,
                        inferenceError = outcome.message,
                    )
                }
            }
        }
        if (runPerch) {
            _state.value = _state.value.copy(perchProgress = 0f, perchError = null)
            try {
                val outcome = perchReanalysis.run(path, lat, lon, recordedAt) { p ->
                    _state.value = _state.value.copy(perchProgress = p.coerceIn(0f, 1f))
                }
                when (outcome) {
                    is PerchAnalysisOutcome.Success -> {
                        repo.mergeAndPersist(
                            draftId = draftId,
                            newModelId = ModelIds.PERCH,
                            newModelVersion = "perch",
                            freshDetections = outcome.detections,
                        )
                    }
                    is PerchAnalysisOutcome.Failure -> {
                        _state.value = _state.value.copy(perchError = outcome.message)
                    }
                    PerchAnalysisOutcome.NotInstalled -> {
                        _state.value = _state.value.copy(
                            perchError = "Perch model is not installed",
                        )
                    }
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    perchError = t.message ?: t::class.simpleName.orEmpty(),
                )
            } finally {
                _state.value = _state.value.copy(perchProgress = null)
                perchInstalled = runCatching { perchInstalledProbe() }.getOrDefault(perchInstalled)
                recomputePerchEligibility()
            }
        }
        launchAnnotationIfIdle()
    }
}
```

- [ ] **Step 5: Update `ReviewViewModelFactory.create()`**

In `ReviewViewModelFactory.create()` (at the end of the same file), add the two new params after the existing `perchAnalysis` line:

```kotlin
inferenceReanalysis = inferenceUseCase.inferenceReanalysis,
perchReanalysis = inferenceUseCase.perchReanalysis,
```

- [ ] **Step 6: Write tests in `ReviewViewModelTest.kt`**

Add three test functions. Study the existing test helpers first: `draftFor()` produces a `DraftEntity` with `audioPath = "/tmp/$id.wav"`, `PENDING_REVIEW` status, and coordinates. `repo()` builds a `DraftRepository`. `noopInference()` returns a no-op `InferenceJob`. Use `UnconfinedTestDispatcher` and `backgroundScope` as all other tests do.

```kotlin
@Test
fun `reanalyze BirdNET-only calls inferenceReanalysis not inference`() =
    runTest(UnconfinedTestDispatcher()) {
        val draftId = "reanalyze_birdnet"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        var gatedCalls = 0
        var noGateCalls = 0
        val inference = InferenceJob { _, _, _, _, _ ->
            gatedCalls++
            InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
        }
        val inferenceReanalysis = InferenceJob { _, _, _, _, _ ->
            noGateCalls++
            InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
        }
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo(draftDao, FakeDetectionDao()),
            player = FakeAudioPlayer(),
            inference = inference,
            inferenceReanalysis = inferenceReanalysis,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
        )
        vm.reanalyze(runBirdnet = true, runPerch = false)
        assertThat(noGateCalls).isEqualTo(1)
        assertThat(gatedCalls).isEqualTo(0)
    }

@Test
fun `reanalyze Perch-only calls perchReanalysis`() =
    runTest(UnconfinedTestDispatcher()) {
        val draftId = "reanalyze_perch"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        var perchReanalysisCalls = 0
        val perchReanalysis = PerchAnalysisJob { _, _, _, _, _ ->
            perchReanalysisCalls++
            PerchAnalysisOutcome.Success(emptyList())
        }
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo(draftDao, FakeDetectionDao()),
            player = FakeAudioPlayer(),
            inference = noopInference(),
            perchReanalysis = perchReanalysis,
            perchInstalledProbe = { true },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
        )
        vm.reanalyze(runBirdnet = false, runPerch = true)
        assertThat(perchReanalysisCalls).isEqualTo(1)
    }

@Test
fun `reanalyze both runs BirdNET then Perch in order`() =
    runTest(UnconfinedTestDispatcher()) {
        val draftId = "reanalyze_both"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val order = mutableListOf<String>()
        val inferenceReanalysis = InferenceJob { _, _, _, _, _ ->
            order += "birdnet"
            InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
        }
        val perchReanalysis = PerchAnalysisJob { _, _, _, _, _ ->
            order += "perch"
            PerchAnalysisOutcome.Success(emptyList())
        }
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo(draftDao, FakeDetectionDao()),
            player = FakeAudioPlayer(),
            inference = noopInference(),
            inferenceReanalysis = inferenceReanalysis,
            perchReanalysis = perchReanalysis,
            perchInstalledProbe = { true },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
        )
        vm.reanalyze(runBirdnet = true, runPerch = true)
        assertThat(order).containsExactly("birdnet", "perch").inOrder()
    }
```

- [ ] **Step 7: Self-review and compile check**

After making all changes, read each modified file in full and verify:
- `InferenceUseCase` interface has 4 properties; `DefaultInferenceUseCase` implements all 4
- `inferenceReanalysis` and `perchReanalysis` in `DefaultInferenceUseCase` use `yamNetGate = null`
- `ReviewViewModel` constructor has `inferenceReanalysis: InferenceJob = inference` and `perchReanalysis: PerchAnalysisJob = perchAnalysis` with correct defaults
- `reanalyze()` uses `inferenceReanalysis` and `perchReanalysis`, never `inference` or `perchAnalysis`
- `ReviewViewModelFactory.create()` passes both new params from `inferenceUseCase`
- All existing tests still compile (defaults ensure backward compat)
- `InferenceUseCaseTest.kt` compiles — if it has a class implementing `InferenceUseCase`, the two new properties must be added

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt \
        app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt \
        app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt
git commit -m "feat(inference): skip YAMNet gate during re-analysis; add reanalyze()"
```

---

### Task 2: Multi-select re-analysis dialog

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

> **Do NOT run tests** — user runs them manually. This is a pure UI change; acceptance criterion is manual inspection.

> **Prerequisite:** Task 1 must be committed. `vm.reanalyze(runBirdnet, runPerch)` must exist in `ReviewViewModel`.

- [ ] **Step 1: Read the files before editing**

Read these two files in full:
- `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- `app/src/main/res/values/strings.xml`

Note:
- `ModelPickerDialog` is the composable to rewrite (currently ~line 399–436)
- The call site passes `onPickBirdnet` and `onPickPerch` (to be replaced with `onConfirm`)
- `dialog_reanalyze_body` string needs update
- Existing imports in `ReviewScreen.kt` — you will likely need to add `Checkbox` and `Alignment`

- [ ] **Step 2: Update `dialog_reanalyze_body` string in `strings.xml`**

Find the line:
```xml
<string name="dialog_reanalyze_body">Pick a model to run again. New detections are merged with existing ones — matching species pick up an extra source badge.</string>
```

Replace with:
```xml
<string name="dialog_reanalyze_body">Select models to run again. New detections are merged with existing ones — matching species pick up an extra source badge.</string>
```

- [ ] **Step 3: Rewrite `ModelPickerDialog` in `ReviewScreen.kt`**

Replace the entire `ModelPickerDialog` composable (from `@Suppress("FunctionNaming")` above it through the closing `}` of the function) with:

```kotlin
@Suppress("FunctionNaming")
@Composable
private fun ModelPickerDialog(
    isPerchInstalled: Boolean,
    onConfirm: (runBirdnet: Boolean, runPerch: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var birdnetChecked by remember { mutableStateOf(true) }
    var perchChecked by remember { mutableStateOf(isPerchInstalled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_reanalyze_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.dialog_reanalyze_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = birdnetChecked,
                        onCheckedChange = { birdnetChecked = it },
                    )
                    Text(stringResource(R.string.btn_birdnet))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = perchChecked,
                        onCheckedChange = { perchChecked = it },
                        enabled = isPerchInstalled,
                    )
                    Text(stringResource(R.string.btn_perch))
                }
                if (!isPerchInstalled) {
                    Text(
                        stringResource(R.string.dialog_perch_not_installed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 48.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(birdnetChecked, perchChecked) },
                enabled = birdnetChecked || perchChecked,
            ) {
                Text(stringResource(R.string.btn_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}
```

Add any missing imports. `Checkbox` is from `androidx.compose.material3`. `Alignment` is from `androidx.compose.ui.Alignment`. `remember` and `mutableStateOf` are from `androidx.compose.runtime`. `by` delegate needs `androidx.compose.runtime.getValue` and `androidx.compose.runtime.setValue`.

- [ ] **Step 4: Update the call site of `ModelPickerDialog` in `ReviewScreen.kt`**

Find the block (around line 361–374):
```kotlin
if (pickerVisible) {
    ModelPickerDialog(
        isPerchInstalled = state.isPerchInstalled,
        onPickBirdnet = {
            pickerVisible = false
            vm.reanalyzeBirdnet()
        },
        onPickPerch = {
            pickerVisible = false
            vm.analyzeWithPerch()
        },
        onDismiss = { pickerVisible = false },
    )
}
```

Replace with:
```kotlin
if (pickerVisible) {
    ModelPickerDialog(
        isPerchInstalled = state.isPerchInstalled,
        onConfirm = { runBirdnet, runPerch ->
            pickerVisible = false
            vm.reanalyze(runBirdnet, runPerch)
        },
        onDismiss = { pickerVisible = false },
    )
}
```

- [ ] **Step 5: Self-review**

Read the modified `ReviewScreen.kt` and verify:
- `ModelPickerDialog` signature is `(isPerchInstalled, onConfirm, onDismiss)` — no more `onPickBirdnet`/`onPickPerch`
- The call site uses `onConfirm = { runBirdnet, runPerch -> ... vm.reanalyze(runBirdnet, runPerch) }`
- `Checkbox` for BirdNET is always enabled, defaults to `true`
- `Checkbox` for Perch is `enabled = isPerchInstalled`, defaults to `isPerchInstalled`
- Confirm button is disabled when both unchecked
- "Not installed" hint shows with `start = 48.dp` indent (aligns under the label, past the Checkbox width)
- All required imports are present

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(review): multi-model checkbox dialog for re-analysis"
```

---

## Self-review

**Spec coverage:**
- ✅ YAMNet skipped during re-analysis: `inferenceReanalysis`/`perchReanalysis` use `yamNetGate = null`
- ✅ Both models can be selected: checkboxes in dialog, `reanalyze(true, true)` runs both
- ✅ Sequential execution: BirdNET in `if (runBirdnet)` block, Perch in `if (runPerch)` block, same coroutine
- ✅ No-op when neither selected: guard at top of `reanalyze()`
- ✅ Initial auto-analysis unchanged: `startInference()` still uses `inference` (with gate)
- ✅ Existing tests unaffected: new VM params have defaults matching current behaviour
- ✅ Perch disabled when not installed: `enabled = isPerchInstalled` on the Checkbox

**No placeholders:** All code is complete.

**Type consistency:**
- `reanalyze(runBirdnet: Boolean, runPerch: Boolean)` used in both ViewModel and call site
- `inferenceReanalysis: InferenceJob` / `perchReanalysis: PerchAnalysisJob` consistent across interface, implementation, VM constructor, and factory wiring
