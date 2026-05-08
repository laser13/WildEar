# iNat Observation Detail Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a species card on the Review screen has already been uploaded to iNaturalist, tapping it fetches and shows observation details (quality grade, agreeing IDs count, latest comments) in an expandable inline section.

**Architecture:** Three layers — (1) `INaturalistClient.getObservation` fetches raw data from iNat API; (2) `ReviewViewModel.loadObservationDetail` drives lazy loading with an in-memory cache keyed on the URL segment, stores state in `SpeciesRow.observationDetailState`; (3) `SpeciesListItem` in `ReviewScreen` renders the expandable section and dispatches expand/collapse callbacks to the VM. Collapse resets state to `NotLoaded`; re-expand is instant if the cache hit is already populated.

**Tech Stack:** Kotlin, OkHttp + JSONObject (same as INaturalistClient), Coroutines, Jetpack Compose Material3, JUnit4 + Truth + MockWebServer (Robolectric for client tests), UnconfinedTestDispatcher (VM tests).

---

## File Map

| File | Change |
|---|---|
| `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt` | Add `getObservation(idOrUuid)`, `ObservationDetail`, `ObservationComment` data classes |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt` | Add `ObservationDetailLoadState` sealed interface, `observationDetailState` field to `SpeciesRow` |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` | Add `observationFetcher` constructor param, `observationDetailCache`, `loadObservationDetail()`, `collapseObservationDetail()`; wire in `ReviewViewModelFactory` |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt` | Expand/collapse tap logic in `SpeciesListItem` for uploaded cards; expandable detail section; `qualityGradeLabel()` helper |
| `app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt` | Tests for `getObservation` |
| `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt` | Tests for `loadObservationDetail` and `collapseObservationDetail` |

---

### Task 1: INaturalistClient.getObservation + data classes

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt`
- Modify: `app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt`

---

- [ ] **Step 1: Add failing tests for `getObservation`**

Append to `INaturalistClientTest.kt` (before the closing `}`):

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest \
  --tests "com.sound2inat.inat.INaturalistClientTest.getObservation*" 2>&1 | tail -20
```

Expected: FAILED with "Unresolved reference: getObservation" or "ObservationDetail".

- [ ] **Step 3: Add data classes and `getObservation` to `INaturalistClient.kt`**

At the bottom of `INaturalistClient.kt`, after `class INatException`, add:

```kotlin
data class ObservationDetail(
    val qualityGrade: String,
    val agreeingIdCount: Int,
    val commentsCount: Int,
    val comments: List<ObservationComment>,
)

data class ObservationComment(
    val username: String,
    val body: String,
)
```

Inside the companion object of `INaturalistClient`, add:

```kotlin
private const val MAX_PREVIEW_COMMENTS = 3
```

Inside the `INaturalistClient` class body (after `addIdentification`, before the private helpers), add:

```kotlin
/**
 * Fetches details for a single observation. [idOrUuid] is either the numeric
 * id or uuid — both are accepted by the iNat v1 API.
 * Throws [INatException] on network or HTTP errors.
 */
suspend fun getObservation(idOrUuid: String): ObservationDetail = withContext(ioDispatcher) {
    val json = executeJson(anonGet("/observations/$idOrUuid"))
    val results = json.optJSONArray("results")
        ?: throw INatException(-1, "Missing results array")
    if (results.length() == 0) throw INatException(-1, "Observation not found: $idOrUuid")
    val obs = results.getJSONObject(0)

    val qualityGrade = obs.optString("quality_grade", "needs_id")
    val commentsCount = obs.optInt("comments_count", 0)

    val idsArray = obs.optJSONArray("identifications")
    val agreeingIdCount = if (idsArray != null) {
        (0 until idsArray.length()).count {
            idsArray.getJSONObject(it).optBoolean("current", false)
        }
    } else 0

    val commentsArray = obs.optJSONArray("comments")
    val comments = if (commentsArray != null) {
        (0 until minOf(commentsArray.length(), MAX_PREVIEW_COMMENTS)).map { i ->
            val c = commentsArray.getJSONObject(i)
            ObservationComment(
                username = c.optJSONObject("user")?.optString("login", "?") ?: "?",
                body = c.optString("body", ""),
            )
        }
    } else emptyList()

    ObservationDetail(
        qualityGrade = qualityGrade,
        agreeingIdCount = agreeingIdCount,
        commentsCount = commentsCount,
        comments = comments,
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest \
  --tests "com.sound2inat.inat.INaturalistClientTest*" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all INaturalistClientTest tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/inat/INaturalistClient.kt \
        app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt
git commit -m "feat(inat): add getObservation + ObservationDetail parsing"
```

---

### Task 2: ObservationDetailLoadState + ViewModel lazy fetch with cache

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
- Modify: `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt`

---

- [ ] **Step 1: Add `ObservationDetailLoadState` and `observationDetailState` field to `ReviewUiState.kt`**

Add the import at the top of `ReviewUiState.kt`:

```kotlin
import com.sound2inat.inat.ObservationDetail
```

Add the sealed interface after `InatSubmissionState`:

```kotlin
sealed interface ObservationDetailLoadState {
    data object NotLoaded : ObservationDetailLoadState
    data object Loading : ObservationDetailLoadState
    data class Loaded(val detail: ObservationDetail) : ObservationDetailLoadState
    data class Error(val message: String) : ObservationDetailLoadState
}
```

Add field to `SpeciesRow` (at the end, with default so existing callers don't break):

```kotlin
val observationDetailState: ObservationDetailLoadState = ObservationDetailLoadState.NotLoaded,
```

The complete updated `SpeciesRow` data class (only showing new field — add it as the last field):

```kotlin
data class SpeciesRow(
    val detectionId: Long,
    val taxonScientificName: String,
    val taxonCommonName: String?,
    val maxConfidence: Float,
    val detectedWindows: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val isSelected: Boolean,
    val confidenceBySource: Map<String, Float> = emptyMap(),
    val taxonPhotoUrl: String? = null,
    val regionalStatus: RegionalStatus? = null,
    val observationDetailState: ObservationDetailLoadState = ObservationDetailLoadState.NotLoaded,
)
```

- [ ] **Step 2: Verify it compiles**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Write failing ViewModel tests**

Open `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt` and append before the closing `}` of the class:

```kotlin
@Test
fun `loadObservationDetail sets Loading then Loaded on success`() =
    runTest(UnconfinedTestDispatcher()) {
        val draftId = "obs1"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val detectionDao = FakeDetectionDao().apply {
            insert(detectionFor(draftId, detectionId = 99L, scientificName = "Parus major"))
        }
        val repo = repo(draftDao, detectionDao)
        val expectedDetail = com.sound2inat.inat.ObservationDetail(
            qualityGrade = "research",
            agreeingIdCount = 3,
            commentsCount = 1,
            comments = listOf(com.sound2inat.inat.ObservationComment("alice", "Nice!")),
        )
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = FakeAudioPlayer(),
            inference = InferenceJob { _, _, _, _, _ -> InferenceOutcome.Failure("skip") },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
            observationFetcher = { expectedDetail },
        )
        vm.loadObservationDetail(
            detectionId = 99L,
            observationUrl = "https://www.inaturalist.org/observations/12345",
        )
        val row = vm.state.value.species.firstOrNull { it.detectionId == 99L }
        assertThat(row).isNotNull()
        val detailState = row!!.observationDetailState
        assertThat(detailState).isInstanceOf(ObservationDetailLoadState.Loaded::class.java)
        assertThat((detailState as ObservationDetailLoadState.Loaded).detail.qualityGrade)
            .isEqualTo("research")
        assertThat(detailState.detail.agreeingIdCount).isEqualTo(3)
    }

@Test
fun `loadObservationDetail sets Error when fetcher throws`() =
    runTest(UnconfinedTestDispatcher()) {
        val draftId = "obs2"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val detectionDao = FakeDetectionDao().apply {
            insert(detectionFor(draftId, detectionId = 77L, scientificName = "Apus apus"))
        }
        val repo = repo(draftDao, detectionDao)
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = FakeAudioPlayer(),
            inference = InferenceJob { _, _, _, _, _ -> InferenceOutcome.Failure("skip") },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
            observationFetcher = { throw com.sound2inat.inat.INatException(500, "Server error") },
        )
        vm.loadObservationDetail(
            detectionId = 77L,
            observationUrl = "https://www.inaturalist.org/observations/99999",
        )
        val row = vm.state.value.species.firstOrNull { it.detectionId == 77L }
        assertThat(row).isNotNull()
        assertThat(row!!.observationDetailState)
            .isInstanceOf(ObservationDetailLoadState.Error::class.java)
    }

@Test
fun `loadObservationDetail returns cached result on second call`() =
    runTest(UnconfinedTestDispatcher()) {
        val draftId = "obs3"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val detectionDao = FakeDetectionDao().apply {
            insert(detectionFor(draftId, detectionId = 55L, scientificName = "Turdus merula"))
        }
        val repo = repo(draftDao, detectionDao)
        var fetchCount = 0
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = FakeAudioPlayer(),
            inference = InferenceJob { _, _, _, _, _ -> InferenceOutcome.Failure("skip") },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
            observationFetcher = {
                fetchCount++
                com.sound2inat.inat.ObservationDetail("research", 1, 0, emptyList())
            },
        )
        vm.loadObservationDetail(55L, "https://www.inaturalist.org/observations/111")
        vm.collapseObservationDetail(55L)
        vm.loadObservationDetail(55L, "https://www.inaturalist.org/observations/111")
        assertThat(fetchCount).isEqualTo(1)
        val row = vm.state.value.species.first { it.detectionId == 55L }
        assertThat(row.observationDetailState).isInstanceOf(ObservationDetailLoadState.Loaded::class.java)
    }

@Test
fun `collapseObservationDetail resets state to NotLoaded`() =
    runTest(UnconfinedTestDispatcher()) {
        val draftId = "obs4"
        val draftDao = FakeDraftDao().apply {
            insert(draftFor(draftId, status = DraftStatus.PENDING_REVIEW))
        }
        val detectionDao = FakeDetectionDao().apply {
            insert(detectionFor(draftId, detectionId = 33L, scientificName = "Hirundo rustica"))
        }
        val repo = repo(draftDao, detectionDao)
        val vm = ReviewViewModel(
            draftId = draftId,
            repo = repo,
            player = FakeAudioPlayer(),
            inference = InferenceJob { _, _, _, _, _ -> InferenceOutcome.Failure("skip") },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            externalScope = backgroundScope,
            observationFetcher = {
                com.sound2inat.inat.ObservationDetail("needs_id", 0, 0, emptyList())
            },
        )
        vm.loadObservationDetail(33L, "https://www.inaturalist.org/observations/222")
        val rowAfterLoad = vm.state.value.species.first { it.detectionId == 33L }
        assertThat(rowAfterLoad.observationDetailState)
            .isInstanceOf(ObservationDetailLoadState.Loaded::class.java)

        vm.collapseObservationDetail(33L)
        val rowAfterCollapse = vm.state.value.species.first { it.detectionId == 33L }
        assertThat(rowAfterCollapse.observationDetailState)
            .isEqualTo(ObservationDetailLoadState.NotLoaded)
    }
```

Note: the tests above assume a `detectionFor` helper exists in the test file. Check the existing test helpers at the bottom of `ReviewViewModelTest.kt`. The helper likely creates a `DetectionEntity`. If it doesn't accept `detectionId`, look at `FakeDetectionDao.insert` and pass the entity directly. Adapt as needed to match the existing fake infrastructure.

- [ ] **Step 4: Run tests to verify they fail**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest \
  --tests "com.sound2inat.app.ui.review.ReviewViewModelTest.loadObservationDetail*" \
  --tests "com.sound2inat.app.ui.review.ReviewViewModelTest.collapseObservationDetail*" \
  2>&1 | tail -20
```

Expected: FAILED with "Unresolved reference: loadObservationDetail" or "observationFetcher".

- [ ] **Step 5: Add `observationFetcher`, `observationDetailCache`, `loadObservationDetail`, `collapseObservationDetail` to `ReviewViewModel`**

Add import at top of `ReviewViewModel.kt`:

```kotlin
import com.sound2inat.inat.ObservationDetail
```

Add `observationFetcher` parameter to `ReviewViewModel` constructor (add after `regionRadiusKmProvider`, before `externalScope`):

```kotlin
private val observationFetcher: suspend (idOrUuid: String) -> ObservationDetail = {
    throw com.sound2inat.inat.INatException(-1, "No observation fetcher configured")
},
```

Add private field inside `ReviewViewModel` class body (near the other caches, e.g. after `regionalStatusCache`):

```kotlin
private val observationDetailCache = mutableMapOf<String, ObservationDetail?>()
```

Add the two public functions inside `ReviewViewModel` class body (e.g. after `toggle()`):

```kotlin
fun loadObservationDetail(detectionId: Long, observationUrl: String) {
    val idOrUuid = observationUrl.trimEnd('/').substringAfterLast('/')
        .takeIf { it.isNotBlank() } ?: return

    if (observationDetailCache.containsKey(idOrUuid)) {
        val cached = observationDetailCache[idOrUuid]
        _state.update { s ->
            s.copy(species = s.species.map { row ->
                if (row.detectionId == detectionId) row.copy(
                    observationDetailState = if (cached != null)
                        ObservationDetailLoadState.Loaded(cached)
                    else
                        ObservationDetailLoadState.Error("Observation not available"),
                ) else row
            })
        }
        return
    }

    _state.update { s ->
        s.copy(species = s.species.map { row ->
            if (row.detectionId == detectionId) row.copy(
                observationDetailState = ObservationDetailLoadState.Loading,
            ) else row
        })
    }

    scope.launch {
        val detail = runCatching { observationFetcher(idOrUuid) }.getOrNull()
        observationDetailCache[idOrUuid] = detail
        _state.update { s ->
            s.copy(species = s.species.map { row ->
                if (row.detectionId == detectionId) row.copy(
                    observationDetailState = if (detail != null)
                        ObservationDetailLoadState.Loaded(detail)
                    else
                        ObservationDetailLoadState.Error("Could not load observation details"),
                ) else row
            })
        }
    }
}

fun collapseObservationDetail(detectionId: Long) {
    _state.update { s ->
        s.copy(species = s.species.map { row ->
            if (row.detectionId == detectionId) row.copy(
                observationDetailState = ObservationDetailLoadState.NotLoaded,
            ) else row
        })
    }
}
```

- [ ] **Step 6: Wire `observationFetcher` in `ReviewViewModelFactory.create()`**

In `ReviewViewModelFactory.create()`, add the following parameter to the `ReviewViewModel(...)` call (after `regionRadiusKmProvider`, before the closing `)` of the constructor call):

```kotlin
observationFetcher = { id -> inatClient.getObservation(id) },
```

- [ ] **Step 7: Run all ViewModel tests**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest \
  --tests "com.sound2inat.app.ui.review.ReviewViewModelTest*" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all ReviewViewModelTest tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt \
        app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt \
        app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt
git commit -m "feat(review): lazy-load iNat observation details with in-memory cache"
```

---

### Task 3: Expandable detail section in ReviewScreen

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`

---

- [ ] **Step 1: Add `CircularProgressIndicator` import and `ObservationDetailLoadState` import**

Add to the imports block in `ReviewScreen.kt`:

```kotlin
import androidx.compose.material3.CircularProgressIndicator
import com.sound2inat.inat.ObservationDetail
```

`ObservationDetailLoadState` is in the same package (`com.sound2inat.app.ui.review`) — no import needed.

- [ ] **Step 2: Add `qualityGradeLabel` helper function**

At the bottom of `ReviewScreen.kt`, after `confidenceLabel`, add:

```kotlin
private fun qualityGradeLabel(grade: String): String = when (grade) {
    "research" -> "Research Grade"
    "needs_id" -> "Needs ID"
    "casual" -> "Casual"
    else -> grade.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
```

- [ ] **Step 3: Add `onExpandDetail` and `onCollapseDetail` parameters to `SpeciesListItem`**

Find the `SpeciesListItem` function signature and add two new callbacks after `onCheckedChange`:

```kotlin
private fun SpeciesListItem(
    row: SpeciesRow,
    isHighlighted: Boolean,
    uploadedUrl: String?,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onExpandDetail: () -> Unit,
    onCollapseDetail: () -> Unit,
)
```

- [ ] **Step 4: Update the `clickable` modifier in `SpeciesListItem` to dispatch expand/collapse**

Replace the existing clickable modifier:

```kotlin
modifier = Modifier
    .clickable {
        onClick()
        if (uploadedUrl == null) onCheckedChange(!row.isSelected)
    }
    .background(containerColor),
```

With:

```kotlin
modifier = Modifier
    .clickable {
        if (uploadedUrl != null) {
            when (row.observationDetailState) {
                is ObservationDetailLoadState.NotLoaded -> onExpandDetail()
                else -> onCollapseDetail()
            }
        } else {
            onClick()
            onCheckedChange(!row.isSelected)
        }
    }
    .background(containerColor),
```

- [ ] **Step 5: Replace the "Already uploaded" inline section in `SpeciesListItem.supportingContent`**

Find the `if (uploadedUrl != null)` branch inside `supportingContent` and replace the entire block:

```kotlin
if (uploadedUrl != null) {
    // Row 1: status + View link
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            "Already uploaded · ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "View observation",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { uriHandler.openUri(uploadedUrl) },
        )
    }
    // Row 2: expandable detail section
    when (val detailState = row.observationDetailState) {
        is ObservationDetailLoadState.NotLoaded -> {}
        is ObservationDetailLoadState.Loading -> {
            Spacer(Modifier.height(4.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
        }
        is ObservationDetailLoadState.Loaded -> {
            val d = detailState.detail
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${qualityGradeLabel(d.qualityGrade)} · ${d.agreeingIdCount} IDs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                d.comments.forEach { c ->
                    Text(
                        "\"${c.body}\" — ${c.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is ObservationDetailLoadState.Error -> {
            Spacer(Modifier.height(4.dp))
            Text(
                detailState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
```

- [ ] **Step 6: Pass `onExpandDetail` and `onCollapseDetail` from all `SpeciesListItem` call sites in `ReviewPage`**

There are two call sites (one for `likelySpecies`, one for `unlikelySpecies`). Update both:

```kotlin
SpeciesListItem(
    row = row,
    isHighlighted = highlight == row.detectionId,
    uploadedUrl = uploadedUrls[row.taxonScientificName],
    onClick = {
        vm.seekTo(row.firstSeenMs)
        vm.highlight(row.detectionId)
    },
    onCheckedChange = { checked -> vm.toggle(row.detectionId, checked) },
    onExpandDetail = {
        uploadedUrls[row.taxonScientificName]?.let { url ->
            vm.loadObservationDetail(row.detectionId, url)
        }
    },
    onCollapseDetail = { vm.collapseObservationDetail(row.detectionId) },
)
```

- [ ] **Step 7: Compile**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. If there are "Unresolved reference" errors, check the import for `ObservationDetail` and `CircularProgressIndicator`.

- [ ] **Step 8: Run all unit tests**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt
git commit -m "feat(review): expandable iNat observation detail card for uploaded species"
```

---

## Self-Review

### 1. Spec coverage

| Requirement | Task |
|---|---|
| quality_grade | Task 1 (parsing) + Task 3 (display) |
| agreeing identifications count | Task 1 (parsing `current=true`) + Task 3 (display) |
| up to 3 comments (text + username) | Task 1 (`MAX_PREVIEW_COMMENTS = 3`) + Task 3 |
| "View on iNaturalist" button (opens app or browser) | Already implemented in previous session via `uriHandler.openUri` — no change needed |
| Lazy loading on first tap | Task 2 (`loadObservationDetail` only fetches when `!cache.containsKey`) |
| In-memory cache for VM lifetime | Task 2 (`observationDetailCache` map) |
| Tap to expand, tap again to collapse | Task 3 (`onExpandDetail`/`onCollapseDetail` callbacks) |

### 2. Placeholder scan

No TBD, TODO, or "add appropriate handling" phrases. All code blocks are complete.

### 3. Type consistency

- `ObservationDetail` and `ObservationComment` defined in Task 1, used in Tasks 2 and 3 — consistent.
- `ObservationDetailLoadState.{NotLoaded, Loading, Loaded, Error}` defined in Task 2, used in Task 3 — consistent.
- `loadObservationDetail(detectionId: Long, observationUrl: String)` defined and tested in Task 2, called in Task 3 — consistent.
- `collapseObservationDetail(detectionId: Long)` defined and tested in Task 2, called in Task 3 — consistent.
- `observationDetailState` field added to `SpeciesRow` in Task 2 (`ReviewUiState.kt`), accessed in Task 3 — consistent.
