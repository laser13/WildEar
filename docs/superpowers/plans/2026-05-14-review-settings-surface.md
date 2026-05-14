# Review Settings Surface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the review screen so the top bar shows recording metadata instead of `Review`, the main controls are icon-only and cleaner, and all configuration moves into one tabbed settings sheet without breaking the current one-profile workflow.

**Architecture:** Keep `ReviewViewModel` as the source of truth for the active audio-processing profile and spectrogram preview config. Move UI-only selection state into `ReviewScreen`, let the settings sheet stage draft audio/visual edits locally, and commit both drafts together on `Apply`. The existing audio profile still drives playback, reanalysis, and upload; the visual tab only affects preview rendering.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, StateFlow, existing review VM and spectrogram renderer.

---

### Task 1: Replace the Review title with recording metadata and make the top actions icon-only

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/ui/review/ReviewHeaderText.kt`
- Test: `app/src/test/java/com/sound2inat/app/ui/review/ReviewHeaderTextTest.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
private var originalTimeZone: TimeZone? = null

@Before
fun setUp() {
    originalTimeZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
}

@After
fun tearDown() {
    TimeZone.setDefault(originalTimeZone)
}

@Test
fun `header text shows timestamp and gps when available`() {
    val header = reviewHeaderText(
        recordedAtUtcMs = 1_715_664_000_000L,
        latitude = 51.5074,
        longitude = -0.1278,
    )

    assertThat(header.titleLine).isEqualTo("2024-05-14 00:00")
    assertThat(header.subtitleLine).isEqualTo("GPS: 51.5074, -0.1278")
}

@Test
fun `header text falls back when gps is missing`() {
    val header = reviewHeaderText(
        recordedAtUtcMs = 1_715_664_000_000L,
        latitude = null,
        longitude = null,
    )

    assertThat(header.subtitleLine).isEqualTo("Location unavailable")
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --no-daemon --tests 'com.sound2inat.app.ui.review.ReviewHeaderTextTest'`
Expected: FAIL because `ReviewHeaderText.kt` does not exist yet.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
internal fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

data class ReviewHeaderText(
    val titleLine: String,
    val subtitleLine: String,
)

internal fun reviewHeaderText(
    recordedAtUtcMs: Long,
    latitude: Double?,
    longitude: Double?,
): ReviewHeaderText {
    val titleLine = formatTimestamp(recordedAtUtcMs)
    val subtitleLine = if (latitude != null && longitude != null) {
        "GPS: %.4f, %.4f".format(latitude, longitude)
    } else {
        "Location unavailable"
    }
    return ReviewHeaderText(titleLine = titleLine, subtitleLine = subtitleLine)
}
```

- [ ] **Step 4: Update the top bar and action row**

Use the new header helper in `ReviewScreen.kt`:

```kotlin
TopAppBar(
    title = {
        Column {
            Text(header.titleLine)
            Text(header.subtitleLine, style = MaterialTheme.typography.bodySmall)
        }
    },
    navigationIcon = { /* back arrow stays */ },
    actions = {
        IconButton(onClick = { /* play/pause */ }) { /* icon-only play */ }
        IconButton(onClick = { /* share */ }) { /* share icon */ }
        IconButton(onClick = { /* download */ }) { /* download icon */ }
        IconButton(onClick = { processingSheetVisible = true }) { /* settings icon */ }
    },
)
```

Remove the old `Review` title text and remove the standalone `HeaderBlock` from the body so the metadata only appears in the top bar.

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --no-daemon --tests 'com.sound2inat.app.ui.review.ReviewHeaderTextTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/review/ReviewHeaderText.kt app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt app/src/test/java/com/sound2inat/app/ui/review/ReviewHeaderTextTest.kt
git commit -m "feat: move review metadata into the top bar"
```

---

### Task 2: Move all review settings into one tabbed bottom sheet

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/ui/review/ReviewSettingsTab.kt`
- Create: `app/src/main/java/com/sound2inat/app/ui/review/ReviewSettingsBottomSheet.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/WaveformAndSpectrogram.kt`
- Modify: `app/src/androidTest/java/com/sound2inat/app/EndToEndTest.kt`
- Delete after wiring is complete: `app/src/main/java/com/sound2inat/app/ui/review/ReviewProcessingBottomSheet.kt`

- [ ] **Step 1: Add the tab enum and make the sheet draft-based**

```kotlin
enum class ReviewSettingsTab {
    Audio,
    Visual,
}
```

In the sheet, keep local draft state for both configs and switch tabs without applying anything:

```kotlin
var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
var audioDraft by remember(state.processingProfile.audioProcessingConfig) {
    mutableStateOf(state.processingProfile.audioProcessingConfig)
}
var visualDraft by remember(state.spectrogramConfig) {
    mutableStateOf(state.spectrogramConfig)
}
```

`Apply` should call `onApply(audioDraft, visualDraft)` and then dismiss; `Cancel` should dismiss only.

- [ ] **Step 2: Move the current audio controls into the Audio tab**

Keep the existing one-profile controls together in the Audio tab:

```kotlin
Orig
Clean
Clear
Boost
high-pass chips
gain chips
normalize switch
```

These continue to feed the same shared audio-processing profile used by playback, reanalysis, and upload.

- [ ] **Step 3: Move the current visual controls into the Visual tab**

Move the existing spectrogram controls out of `WaveformAndSpectrogram.kt` and into the Visual tab:

```kotlin
palette chips
display range chips
visual gain chips
```

These stay preview-only and must not mutate the underlying audio source.

- [ ] **Step 4: Wire the settings icon to the sheet**

In `ReviewScreen.kt`, replace the old `Processing` entry point with a `Settings` icon button. The sheet should open with the Audio tab selected by default.

```kotlin
IconButton(onClick = { processingSheetVisible = true }) {
    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.cd_settings))
}
```

Remove the old inline `Processing` chip from the main card so the card stays focused on preview, playback, share, and download.

- [ ] **Step 5: Add a smoke test for the sheet surface**

Extend `app/src/androidTest/java/com/sound2inat/app/EndToEndTest.kt` with a review-specific smoke test that:

```kotlin
composeRule.onNodeWithContentDescription(BACK_DESC).performClick()
composeRule.onNodeWithContentDescription(SETTINGS_DESC).performClick()
composeRule.onNodeWithText("Audio").assertIsDisplayed()
composeRule.onNodeWithText("Visual").assertIsDisplayed()
```

The test should prove the new entry point exists and that the sheet exposes both tabs.

- [ ] **Step 6: Run the compile check for the instrumented test graph**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebugAndroidTest --no-daemon`
Expected: PASS, proving the new sheet/test wiring compiles.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/review/ReviewSettingsTab.kt app/src/main/java/com/sound2inat/app/ui/review/ReviewSettingsBottomSheet.kt app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt app/src/main/java/com/sound2inat/app/ui/review/WaveformAndSpectrogram.kt app/src/androidTest/java/com/sound2inat/app/EndToEndTest.kt app/src/main/java/com/sound2inat/app/ui/review/ReviewProcessingBottomSheet.kt
git commit -m "feat: move review settings into a tabbed sheet"
```

---

### Task 3: Keep the one-profile workflow intact while applying settings atomically

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Test: `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt`

- [ ] **Step 1: Add an atomic apply method**

Add a single ViewModel entry point that commits both settings together:

```kotlin
fun applyReviewSettings(
    audioConfig: ReviewAudioProcessingConfig,
    spectrogramConfig: ReviewSpectrogramConfig,
) {
    updateProcessingProfile(
        ReviewProcessingProfile(
            spectrogramConfig = spectrogramConfig,
            audioProcessingConfig = audioConfig,
        ),
    )
}
```

Keep the existing audio-specific and visual-specific setters only as wrappers if other call sites still need them.

- [ ] **Step 2: Preserve the current source of truth model**

Do not introduce a second audio-processing state. The active profile still has to control:

```kotlin
playback
reanalyze
upload
```

Visual config remains preview-only and should continue to re-render the preview when applied.

- [ ] **Step 3: Write the failing ViewModel test**

```kotlin
@Test
fun `applyReviewSettings commits audio and visual draft together`() = runTest {
    val vm = reviewViewModelWithFakes()

    vm.applyReviewSettings(
        audioConfig = ReviewAudioProcessingConfig.BirdClean,
        spectrogramConfig = ReviewSpectrogramConfig.BirdDefault.copy(
            palette = SpectrogramPalette.GRAY,
        ),
    )

    assertThat(vm.processingProfile.value.audioProcessingConfig)
        .isEqualTo(ReviewAudioProcessingConfig.BirdClean)
    assertThat(vm.spectrogramConfig.value.palette)
        .isEqualTo(SpectrogramPalette.GRAY)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --no-daemon --tests 'com.sound2inat.app.ui.review.ReviewViewModelTest'`
Expected: PASS.

- [ ] **Step 5: Update `ReviewScreen.kt` to apply both drafts at once**

When the settings sheet taps `Apply`, pass both draft configs into the ViewModel in one call instead of separate audio and visual updates.

```kotlin
onApply = { audioDraft, visualDraft ->
    vm.applyReviewSettings(audioDraft, visualDraft)
}
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt
git commit -m "feat: apply review settings atomically"
```

---

### Task 4: Final verification and cleanup

**Files:**
- Verify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Verify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewSettingsBottomSheet.kt`
- Verify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
- Verify: `app/src/androidTest/java/com/sound2inat/app/EndToEndTest.kt`
- Verify: `app/src/test/java/com/sound2inat/app/ui/review/ReviewHeaderTextTest.kt`
- Verify: `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt`

- [ ] **Step 1: Run the focused unit tests**

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --no-daemon --tests 'com.sound2inat.app.ui.review.ReviewHeaderTextTest' --tests 'com.sound2inat.app.ui.review.ReviewViewModelTest' --tests 'com.sound2inat.app.ui.spectrogram.SpectrogramColorMapTest' --tests 'com.sound2inat.app.ui.review.ReviewSpectrogramTimelineTest'
```

Expected: PASS.

- [ ] **Step 2: Run the instrumented compile check**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebugAndroidTest --no-daemon`
Expected: PASS.

- [ ] **Step 3: Clean up any dead code**

Remove any now-unused inline processing chip entry points or old sheet names that are no longer referenced after the tabbed settings sheet lands.

- [ ] **Step 4: Final commit if cleanup touched files**

```bash
git add <only the cleanup files you changed>
git commit -m "chore: clean up review settings surface"
```
