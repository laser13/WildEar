# sound2inat — Architecture Refactor (4-PR Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Per-task self-check:** before starting any task, agent MUST: (1) re-read THIS task only — not the whole plan, (2) open every file the task references and confirm referenced lines/symbols still exist, (3) run the failing test step **before** writing the fix, (4) flag any drift between plan and current code in the result message instead of silently adapting.

**Goal:** Address all 20 findings from the 4-agent code review (architecture, UI, audio/ML, storage/iNat/tests) without regressing existing behavior.

**Architecture:**
- Extract production inference + persistence out of `ReviewViewModel` into a dedicated `InferenceUseCase` and a `mergeAndPersist` repository method.
- Replace per-VM and singleton-factory in-memory caches with explicit repositories (`RegionalStatusRepository`, `TaxonPhotoRepository`).
- Tighten audio pipeline invariants (backpressure, cancel-race, lifecycle scope) and network/auth boundaries (timeouts, stale-token, atomic migration).
- Migrate UI strings to `strings.xml`, lift state out of `LazyColumn` items, switch to lifecycle-aware Flow collection, dedupe theme constants.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, OkHttp, Coil, kotlinx.coroutines, JUnit + Truth + MockWebServer + Robolectric + in-memory Room.

**PR breakdown (independent, in this order):**
1. **PR1 — Critical bug fixes** (audio race, backpressure invariant, network timeouts, auth correctness)
2. **PR2 — `ReviewViewModel` decomposition** (use-case, repo, model-id source of truth)
3. **PR3 — Storage hygiene & perf** (migration tests, suspend repo, transactional iNat sync, aggregator)
4. **PR4 — UI hygiene & localization** (strings.xml, lifecycle-aware Flows, state hoisting, theme, cleanup)

---

## File Structure (lock-in for all 4 PRs)

| Path | Action | Responsibility |
|------|--------|----------------|
| `inference/LiveInferenceEngine.kt` | modify | enforce backpressure invariant |
| `app/recording/RecordingController.kt` | modify | fix cancel-race; bind scope to ApplicationScope/lifecycle |
| `app/di/AppModule.kt` | modify | configure OkHttp timeouts; provide ApplicationScope |
| `inat/INatAuthRepository.kt` | modify | drop stale token on refresh fail; mutex-guard `ensureMigrated` |
| `inference/InferenceUseCase.kt` | **create** | move `ProductionInferenceJob` + `ProductionPerchAnalysisJob` out of VM |
| `storage/DraftRepository.kt` | modify | add `suspend mergeAndPersist`; convert mutating fns to `suspend` + `withContext(io)` |
| `inat/RegionalStatusRepository.kt` | **create** | singleton repo replacing `ReviewViewModelFactory.sharedRegionalStatusCache` |
| `inat/TaxonPhotoRepository.kt` | **create** | repo replacing `HomeViewModelHilt.photoFlows` (Coil-backed) |
| `inference/ModelIds.kt` | **create** | single source of truth for model identifiers |
| `app/src/test/.../storage/MigrationTest.kt` | **create** | tests for Room migrations 1→2→3→4→5 |
| `inat/INatSubmitter.kt` | modify | wrap `deleteForDraft + insertAll` in DB transaction |
| `inference/DetectionAggregator.kt` | modify | incremental aggregation (no full recompute per window) |
| `inference/SourceStats.kt` | modify | validate keys at encode time |
| `inference/InferenceRunner.kt` | modify | parse WAV `data` chunk magic instead of fixed-44 header |
| `audio/Spectrogram.kt` | modify | size `ArrayList` to expected column count |
| `app/src/main/res/values/strings.xml` | modify | populate with all UI strings (en) |
| `app/src/main/res/values/colors.xml` | modify | add `inat_green` |
| `app/.../theme/Color.kt` (or wherever theme lives) | modify | expose `inat_green` via `ColorScheme` extension |
| `app/.../ui/home/HomeScreen.kt` | modify | `collectAsStateWithLifecycle`; `@Immutable DraftRow`; lift Flow subscriptions to VM |
| `app/.../ui/recording/RecordingScreen.kt` | modify | `collectAsStateWithLifecycle` |
| `app/.../ui/review/ReviewScreen.kt` | modify | `collectAsStateWithLifecycle`; delete-confirmation dialog; remove `Modifier.size(20.dp)` from Checkbox |
| `app/.../ui/settings/SettingsScreen.kt` | modify | strings.xml usage |
| `app/.../ui/home/HomeViewModel.kt` | modify | enriched `DraftRow` flow; remove `photoFlows`; merge Hilt wrapper into single class |
| `app/.../ui/settings/SettingsViewModel.kt` | modify | merge Hilt wrapper into single class |
| `app/src/test/.../ui/home/HomeCopyTest.kt` | delete | constant-string tests |
| `app/src/test/.../ui/recording/RecordingCopyTest.kt` | delete | constant-string tests |

---

# PR1 — Critical bug fixes

**Branch:** `refactor/pr1-critical-fixes`
**Goal:** Eliminate four classes of bugs that can corrupt audio sessions, hang network calls, or surface stale auth.

## Task 1.1: Backpressure invariant in `LiveInferenceEngine`

**Context:** Review §4 found that when `queue.trySend(...)` returns `false`, the **current** window is silently dropped while `nextEmitAt` is still advanced and `_backlog` is not incremented — leaving UI blind. With `Channel.BUFFERED + DROP_OLDEST` this branch should be effectively unreachable; we want either an assertion or correct accounting if it ever fires.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt:108-130`
- Test: `app/src/test/java/com/sound2inat/inference/LiveInferenceEngineTest.kt`

- [ ] **Step 1: Self-check current code.** Open `LiveInferenceEngine.kt`, locate the emit loop (around the `trySend` call). Confirm: `nextEmitAt += hopSamples` runs unconditionally, and `_backlog` only increments on `sent == true`. If layout differs significantly, **STOP and report** instead of guessing.

- [ ] **Step 2: Write failing test.** Add a test that simulates many windows arriving back-to-back faster than the consumer can drain, asserts that **either** every emitted window appears in the consumer **or** `backlog` reflects the drop count. Use a hand-rolled slow consumer (a `Channel.receive` with `delay(50)`).

```kotlin
@Test
fun `no window is silently lost on backpressure`() = runTest {
    val engine = LiveInferenceEngine(/* args, queueCapacity = 2 */)
    // produce 100 windows, consumer sleeps 50ms per window
    // assert: received.size + backlog.dropped == 100
}
```

- [ ] **Step 3: Run test, expect fail.**

```bash
./gradlew :app:testDebugUnitTest --tests com.sound2inat.inference.LiveInferenceEngineTest
```
Expected: FAIL.

- [ ] **Step 4: Fix.** In the emit branch, if `trySend(...).isSuccess` is `false`, increment a `_droppedNew` counter (add to backlog or a sibling `MutableStateFlow<Int>`). Also add an `assert(false) { "BUFFERED+DROP_OLDEST should never reject" }` in `BuildConfig.DEBUG` to surface architectural drift if `Channel` config ever changes.

- [ ] **Step 5: Run tests, expect pass.** Same command. PASS.

- [ ] **Step 6: Update KDoc** of the engine to reflect actual behavior (no longer claim "oldest queued windows are dropped" without qualifier).

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt \
        app/src/test/java/com/sound2inat/inference/LiveInferenceEngineTest.kt
git commit -m "fix(inference): account for dropped windows on backpressure"
```

---

## Task 1.2: `RecordingController.cancel()` race + scope lifecycle

**Context:** Review §2 — `cancel()` sets `_state.value = Idle` *before* `engine.stop()` finishes; review §6 — `private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)` is never cancelled. Together this means a quick stop→start can run two BirdNET interpreters in parallel, and orphan coroutines survive `RecordingService.onDestroy`.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/recording/RecordingController.kt`
- Modify: `app/src/main/java/com/sound2inat/app/di/AppModule.kt` (provide `ApplicationScope`)
- Test: `app/src/test/java/com/sound2inat/app/recording/RecordingControllerTest.kt`

- [ ] **Step 1: Self-check.** Open `RecordingController.kt`, find `private val scope` (≈ line 87) and the `cancel()` body (≈ lines 251–263). Confirm scope is created in-place and `_state.value = Idle` is set before `engine.stop()` returns.

- [ ] **Step 2: Provide ApplicationScope in DI.** Add to `AppModule.kt`:

```kotlin
@Provides @Singleton
fun provideApplicationScope(@AppDispatcher.Default default: CoroutineDispatcher): CoroutineScope =
    CoroutineScope(SupervisorJob() + default)
```

- [ ] **Step 3: Inject `ApplicationScope` into `DefaultRecordingController`.** Replace the in-class `scope` field with the injected one. Keep job-tracking (`feedJob`, `predictionsJob` etc.) as before — they are still cancelled via `cancelJobs()`.

- [ ] **Step 4: Write failing test for cancel-race.** Use a fake engine whose `stop()` suspends on a `Mutex`. Call `cancel()` then `start()` immediately; assert `start()` waits until previous `stop()` completes (use a counter of overlapping engines).

```kotlin
@Test
fun `start after cancel waits for engine stop`() = runTest {
    val engineMutex = Mutex(locked = true)
    val engine = FakeEngine(stopGate = engineMutex)
    val ctl = DefaultRecordingController(/* ... */, engineFactory = { engine })
    ctl.start(...); ctl.cancel()
    val starting = launch { ctl.start(...) }
    advanceUntilIdle()
    assertThat(engine.activeInstances).isEqualTo(0)  // no overlap
    engineMutex.unlock()
    starting.join()
}
```

- [ ] **Step 5: Run test, expect fail.**

```bash
./gradlew :app:testDebugUnitTest --tests com.sound2inat.app.recording.RecordingControllerTest
```

- [ ] **Step 6: Fix race in `cancel()`.** Make `cancel()` itself `suspend` (it already runs from VM scopes), `await` `engine.stop()` before clearing `activeEngine` and writing `Idle`. If callers cannot easily switch to suspend, expose `suspend fun cancelAndWait()` and have `start()` internally call `mutex.withLock` + `previousStopJob?.join()`.

- [ ] **Step 7: Run tests, expect pass.** Same command.

- [ ] **Step 8: Commit.**

```bash
git add app/src/main/java/com/sound2inat/app/recording/RecordingController.kt \
        app/src/main/java/com/sound2inat/app/di/AppModule.kt \
        app/src/test/java/com/sound2inat/app/recording/RecordingControllerTest.kt
git commit -m "fix(recording): await engine.stop before reusing controller; bind scope to ApplicationScope"
```

---

## Task 1.3: OkHttp timeouts

**Context:** Review §3 — `OkHttpClient()` is created with defaults at `AppModule.kt:68`. Long uploads can hang. We add explicit `connectTimeout`, `readTimeout`, `writeTimeout`, `callTimeout`.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/di/AppModule.kt`
- Test: `app/src/test/java/com/sound2inat/app/di/AppModuleTest.kt` (new, tiny)

- [ ] **Step 1: Self-check.** Confirm `provideHttp()` exists and currently returns `OkHttpClient()` (no builder).

- [ ] **Step 2: Failing test.** Use MockWebServer with a never-responding dispatcher; call any iNat client method through the injected client; assert it fails within `callTimeout + slack` (e.g. ≤ 65s for a 60s callTimeout). Skip if test would be brittle in CI; instead, assert builder timeouts via reflection (`client.callTimeoutMillis`).

```kotlin
@Test
fun `OkHttpClient has explicit timeouts`() {
    val client = AppModule.provideHttp()
    assertThat(client.connectTimeoutMillis).isEqualTo(15_000)
    assertThat(client.readTimeoutMillis).isEqualTo(30_000)
    assertThat(client.writeTimeoutMillis).isEqualTo(60_000)
    assertThat(client.callTimeoutMillis).isEqualTo(120_000)
}
```

- [ ] **Step 3: Run test, expect fail.**

- [ ] **Step 4: Fix.**

```kotlin
@Provides @Singleton
fun provideHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS)
    .build()
```

- [ ] **Step 5: Run test, expect pass.**

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/com/sound2inat/app/di/AppModule.kt \
        app/src/test/java/com/sound2inat/app/di/AppModuleTest.kt
git commit -m "fix(net): set explicit OkHttp timeouts"
```

---

## Task 1.4: `INatAuthRepository` correctness — stale token + atomic migration

**Context:** Review §5 — `getValidToken()` returns the stale `cached` token when `trySilentRefresh` fails, surfacing 401 instead of "login required". Review §15 — `ensureMigrated()` uses `@Volatile` check-then-act, allowing the migration body to run twice under concurrent calls.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inat/INatAuthRepository.kt`
- Test: `app/src/test/java/com/sound2inat/inat/INatAuthRepositoryTest.kt`

- [ ] **Step 1: Self-check.** Open `INatAuthRepository.kt`. Locate (a) `getValidToken()` body around lines 55–75, (b) `ensureMigrated()` body around lines 155–165, (c) the `@Volatile var migrationChecked` field.

- [ ] **Step 2: Failing tests (two).**

```kotlin
@Test
fun `getValidToken returns null when refresh fails for expired token`() = runTest {
    val repo = INatAuthRepository(
        settings = fakeSettingsWith(token = "old", ageMs = TOKEN_TTL_MS + 1),
        refresh = { null },  // simulates failed refresh
    )
    assertThat(repo.getValidToken()).isNull()
}

@Test
fun `ensureMigrated runs at most once under concurrent calls`() = runTest {
    val migrationCalls = AtomicInteger(0)
    val repo = INatAuthRepository(
        legacyTokenStore = { migrationCalls.incrementAndGet(); null },
    )
    coroutineScope {
        repeat(50) { launch { repo.getValidToken() } }
    }
    assertThat(migrationCalls.get()).isEqualTo(1)
}
```

- [ ] **Step 3: Run tests, expect fail.**

```bash
./gradlew :app:testDebugUnitTest --tests com.sound2inat.inat.INatAuthRepositoryTest
```

- [ ] **Step 4: Fix stale token.** In `getValidToken()`, when `age > TOKEN_TTL_MS`, return `trySilentRefresh()` directly (no fallback to `cached`).

```kotlin
return if (age > TOKEN_TTL_MS) trySilentRefresh(refreshDispatcher) else cached
```

- [ ] **Step 5: Fix migration atomicity.** Replace `@Volatile var migrationChecked` with a `Mutex` + boolean inside `withLock`:

```kotlin
private val migrationMutex = Mutex()
private var migrationChecked = false

private suspend fun ensureMigrated() = migrationMutex.withLock {
    if (migrationChecked) return@withLock
    // ... migration body ...
    migrationChecked = true
}
```

- [ ] **Step 6: Run tests, expect pass.**

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/com/sound2inat/inat/INatAuthRepository.kt \
        app/src/test/java/com/sound2inat/inat/INatAuthRepositoryTest.kt
git commit -m "fix(auth): drop stale token on refresh fail; mutex-guard migration"
```

---

## PR1 acceptance & merge

- [ ] All four tasks committed.
- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest detekt` passes.
- [ ] Manual smoke: start recording → cancel → start again immediately → no crash, no double-progress in UI.
- [ ] Open PR titled `fix: critical audio/network/auth correctness (PR1/4)`. Body lists tasks 1.1–1.4 with the exact symptoms each addresses.

---

# PR2 — `ReviewViewModel` decomposition

**Branch:** `refactor/pr2-review-vm-decomposition`
**Goal:** Move production inference, persistence and cross-VM caches out of `ReviewViewModel` (~1400 LOC) into focused use-case + repositories. Establish single source of truth for the BirdNET model identifier.

> **Architectural note for the executing agent:** this is the heaviest PR. You may extract step-by-step in a single branch but **commit after each task** so reviewers can bisect.

## Task 2.1: Extract `InferenceUseCase` (`ProductionInferenceJob` + `ProductionPerchAnalysisJob`)

**Context:** Review §1 — `ReviewViewModel.kt:1160-1397` contains TFLite orchestration, BirdNET priors application and Perch analysis. Belongs in `inference/`.

**Files:**
- Create: `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` (delete extracted blocks; inject `InferenceUseCase`)
- Modify: `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt` (provide `InferenceUseCase`)
- Test: `app/src/test/java/com/sound2inat/inference/InferenceUseCaseTest.kt`

- [ ] **Step 1: Self-check.** Re-read `ReviewViewModel.kt` lines 1160–1397. Note **all** dependencies referenced inside `ProductionInferenceJob`/`ProductionPerchAnalysisJob` (interpreter factory, settings flows, label resolver, region filter). Build a dependency list **before** extracting.

- [ ] **Step 2: Define interface.** In new file `InferenceUseCase.kt`:

```kotlin
interface InferenceUseCase {
    suspend fun runProduction(input: InferenceInput, onProgress: (Float) -> Unit): InferenceOutput
    suspend fun runPerchAnalysis(input: InferenceInput, onProgress: (Float) -> Unit): InferenceOutput
}

data class InferenceInput(val draftId: String, val audioPath: String, val locale: String?)
data class InferenceOutput(val detections: List<AggregatedDetection>, val modelId: String, val modelVersion: String)
```

- [ ] **Step 3: Write failing test.** Use a fake `BioacousticModel` returning canned predictions; assert `runProduction()` produces the expected `InferenceOutput.detections` count and modelId.

- [ ] **Step 4: Run test, expect fail (interface only — no impl).**

- [ ] **Step 5: Implement `DefaultInferenceUseCase`.** Move bodies of `ProductionInferenceJob` and `ProductionPerchAnalysisJob` verbatim into the use-case. Keep all priors / region-filter / aggregation logic intact — this is a **mechanical extraction**, not a redesign.

- [ ] **Step 6: Wire DI.** In `SwappableModule.kt`, replace `provideLiveInferenceEngineFactory` callsites that produced these jobs with `@Provides fun provideInferenceUseCase(...) : InferenceUseCase`.

- [ ] **Step 7: Update `ReviewViewModel`.** Replace `ProductionInferenceJob(...)` instantiations with `inferenceUseCase.runProduction(...)`. Delete the extracted classes from the VM. After extraction VM should drop ≥ 200 LOC.

- [ ] **Step 8: Run all tests.**

```bash
./gradlew :app:testDebugUnitTest detekt
```
Existing `ReviewViewModelTest` and end-to-end tests must still pass.

- [ ] **Step 9: Commit.**

```bash
git commit -m "refactor(inference): extract InferenceUseCase from ReviewViewModel"
```

---

## Task 2.2: `DraftRepository.mergeAndPersist` + `persistMutex`

**Context:** Review §1 — `mergeAndPersist` (ReviewViewModel.kt:658–708) writes to DB and serializes BirdNET vs Perch via `persistMutex`. Belongs in repo.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
- Test: `app/src/test/java/com/sound2inat/storage/DraftRepositoryMergeTest.kt`

- [ ] **Step 1: Self-check.** Open `ReviewViewModel.kt:658-708` and note exact merge semantics (per-source stats, fragment ranges, max confidence, taxonId resolution). Reproduce *bit-for-bit* — this is the hottest correctness path.

- [ ] **Step 2: Failing test.** In the new test, seed an in-memory Room with detections from BirdNET, call `repo.mergeAndPersist(...)` with overlapping Perch detections, and assert the resulting rows have unioned `sources`, max-of-max confidence, and concatenated fragment ranges.

- [ ] **Step 3: Run test, expect fail (method does not exist).**

```bash
./gradlew :app:testDebugUnitTest --tests com.sound2inat.storage.DraftRepositoryMergeTest
```

- [ ] **Step 4: Implement `suspend fun mergeAndPersist(...)`.** In `DraftRepository`:

```kotlin
private val persistMutex = Mutex()

suspend fun mergeAndPersist(
    draftId: String,
    incoming: List<AggregatedDetection>,
    newModelId: String,
    newModelVersion: String,
): List<DetectionEntity> = persistMutex.withLock {
    db.withTransaction {
        val existing = detectionDao.getForDraft(draftId)
        val merged = mergeDetections(existing, incoming, newModelId, newModelVersion)
        detectionDao.deleteForDraft(draftId)
        detectionDao.insertAll(merged)
        merged
    }
}
```

(Move the helper `mergeDetections` from VM to repo as a `private fun` or top-level in `storage/`.)

- [ ] **Step 5: Run tests, expect pass.**

- [ ] **Step 6: Update `ReviewViewModel`.** Replace `mergeAndPersist(...)` callsites with `draftRepository.mergeAndPersist(...)`. Delete the VM-side method and its `persistMutex`.

- [ ] **Step 7: Run full test suite.**

```bash
./gradlew :app:testDebugUnitTest
```

- [ ] **Step 8: Commit.**

```bash
git commit -m "refactor(storage): move mergeAndPersist + persistMutex into DraftRepository"
```

---

## Task 2.3: `RegionalStatusRepository`

**Context:** Review §10 — `ReviewViewModelFactory.sharedRegionalStatusCache` is a `@Singleton ConcurrentHashMap` accumulating data across user sessions and locations forever. Replace with explicit repo: scoped cache keyed by `(taxon, lat-lon-bucket)` with TTL.

**Files:**
- Create: `app/src/main/java/com/sound2inat/inat/RegionalStatusRepository.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` (`Factory` loses cache field; VM uses repo)
- Modify: `app/src/main/java/com/sound2inat/app/di/AppModule.kt` (`@Provides @Singleton`)
- Test: `app/src/test/java/com/sound2inat/inat/RegionalStatusRepositoryTest.kt`

- [ ] **Step 1: Self-check.** Open `ReviewViewModel.kt:962-988` and note exact key format used today (`scientificName -> RegionalStatus?`). Confirm key format.

- [ ] **Step 2: Failing test.** Cover: (a) cache hit returns same value, (b) entries older than `TTL_MS = 24h` are refetched, (c) different lat-lon buckets do not collide.

- [ ] **Step 3: Run test, expect fail.**

- [ ] **Step 4: Implement.**

```kotlin
@Singleton
class RegionalStatusRepository @Inject constructor(
    private val client: INaturalistClient,
    private val clock: Clock,
) {
    private data class Entry(val status: RegionalStatus?, val storedAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun get(taxonName: String, lat: Double, lon: Double): RegionalStatus? {
        val key = "$taxonName|${roundBucket(lat)}|${roundBucket(lon)}"
        cache[key]?.let { if (clock.nowMs() - it.storedAt < TTL_MS) return it.status }
        val fresh = client.lookupRegional(taxonName, lat, lon)
        cache[key] = Entry(fresh, clock.nowMs())
        return fresh
    }

    private fun roundBucket(d: Double): Int = (d * 10).toInt() // ~10 km bucket
    companion object { const val TTL_MS = 24L * 60 * 60 * 1000 }
}
```

- [ ] **Step 5: Run tests, expect pass.**

- [ ] **Step 6: Migrate `ReviewViewModel`.** Inject `RegionalStatusRepository`. Delete `sharedRegionalStatusCache` field from `ReviewViewModelFactory`. Replace cache lookups in `annotateNewSpecies` with `regionalStatusRepository.get(...)`.

- [ ] **Step 7: Run full test suite.**

- [ ] **Step 8: Commit.**

```bash
git commit -m "refactor(inat): replace factory cache with RegionalStatusRepository (TTL + geo-bucketed)"
```

---

## Task 2.4: `TaxonPhotoRepository` — replace `HomeViewModelHilt.photoFlows`

**Context:** Review §11 — `HomeViewModelHilt` keeps a `ConcurrentHashMap<String, MutableStateFlow<String?>>` that grows unbounded session-long. Coil already caches images on disk. Replace with a thin repo (LRU memory + delegate URL fetch to iNat client).

**Files:**
- Create: `app/src/main/java/com/sound2inat/inat/TaxonPhotoRepository.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/di/AppModule.kt`
- Test: `app/src/test/java/com/sound2inat/inat/TaxonPhotoRepositoryTest.kt`

- [ ] **Step 1: Self-check.** `HomeViewModel.kt:154-185` — confirm field `photoFlows` and method `observeTaxonPhoto`.

- [ ] **Step 2: Failing test.** Asserts: same taxon hit twice → 1 network call; LRU evicts beyond `maxEntries = 256`.

- [ ] **Step 3: Run test, expect fail.**

- [ ] **Step 4: Implement.**

```kotlin
@Singleton
class TaxonPhotoRepository @Inject constructor(private val client: INaturalistClient) {
    private val lru = object : LinkedHashMap<String, String?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String?>) = size > 256
    }
    private val flows = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val mutex = Mutex()

    fun observe(taxonName: String): Flow<String?> = flows.getOrPut(taxonName) {
        MutableStateFlow(synchronized(lru) { lru[taxonName] })
            .also { CoroutineScope(Dispatchers.IO).launch { ensureLoaded(taxonName, it) } }
    }

    private suspend fun ensureLoaded(taxonName: String, sink: MutableStateFlow<String?>) =
        mutex.withLock {
            val cached = synchronized(lru) { lru[taxonName] }
            if (cached != null) { sink.value = cached; return }
            val url = runCatching { client.lookupDefaultPhoto(taxonName) }.getOrNull()
            synchronized(lru) { lru[taxonName] = url }
            sink.value = url
        }
}
```

- [ ] **Step 5: Run tests, expect pass.**

- [ ] **Step 6: Migrate `HomeViewModel`.** Replace `photoFlows` and `observeTaxonPhoto` with delegation to `TaxonPhotoRepository.observe(...)`.

- [ ] **Step 7: Commit.**

```bash
git commit -m "refactor(inat): TaxonPhotoRepository (LRU 256) replaces HomeVM in-memory cache"
```

---

## Task 2.5: Single source of truth for model identifiers

**Context:** Review §17 — string `"birdnet_v2_4"` is hard-coded in three places: DI, `ProductionInferenceJob` (now `InferenceUseCase`), and `RecordingController`.

**Files:**
- Create: `app/src/main/java/com/sound2inat/inference/ModelIds.kt`
- Modify: `SwappableModule.kt`, `InferenceUseCase.kt`, `RecordingController.kt`

- [ ] **Step 1: Find all literal hits.** Run:

```bash
rg -n '"birdnet_v2_4"|"perch_v[0-9_]+"' app/src/main/
```
Record exact line numbers. If you find more than 3 hits, **stop and report** before extracting.

- [ ] **Step 2: Create `ModelIds.kt`.**

```kotlin
object ModelIds {
    const val BIRDNET = "birdnet_v2_4"
    const val PERCH = "perch_v8" // or whatever current Perch id is — verify before substituting
}
```

- [ ] **Step 3: Replace each literal with `ModelIds.BIRDNET` / `ModelIds.PERCH`.** Do not change Perch id without verifying current value via grep first.

- [ ] **Step 4: Run tests + detekt.** Existing tests must still pass.

```bash
./gradlew :app:testDebugUnitTest detekt
```

- [ ] **Step 5: Commit.**

```bash
git commit -m "refactor(inference): centralize model identifiers in ModelIds"
```

---

## PR2 acceptance & merge

- [ ] `ReviewViewModel.kt` LOC reduced by ≥ 300 (from ~1400 to ≤ 1100).
- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest detekt` passes.
- [ ] No new `ConcurrentHashMap` / `mutableMapOf` fields in any `ViewModel` or `*Factory`.
- [ ] Manual smoke: open Review → species list still loads; switch to Perch → results still merge correctly.

---

# PR3 — Storage hygiene & perf

**Branch:** `refactor/pr3-storage-hygiene`
**Goal:** Cover Room migrations with tests, force IO discipline on repository mutations, make iNat sync transactional, and stop the `O(n²)` aggregator hot path.

## Task 3.1: Room migration tests (1→2→3→4→5)

**Context:** Review §7 — no existing test exercises the migrations. We have the schema JSONs in `app/schemas/com.sound2inat.storage.Sound2iNatDb/`.

**Files:**
- Create: `app/src/androidTest/java/com/sound2inat/storage/MigrationTest.kt` (instrumented — `androidTest`, not `test`, since Room migration helper requires Android)
- Modify: `app/build.gradle.kts` if `room.testing` is missing

- [ ] **Step 1: Self-check.** Confirm `app/schemas/com.sound2inat.storage.Sound2iNatDb/{1..5}.json` exist (they do — verified pre-plan).

- [ ] **Step 2: Add room-testing dependency** (skip if already present):

```kotlin
androidTestImplementation("androidx.room:room-testing:<roomVersion>")
```

- [ ] **Step 3: Write failing test (one per migration step + one full chain).**

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        Sound2iNatDb::class.java,
    )

    @Test fun migrate1to2() {
        helper.createDatabase("test-db", 1).apply {
            execSQL("INSERT INTO drafts(id, ...) VALUES('d1', ...)")
            close()
        }
        val db = helper.runMigrationsAndValidate("test-db", 2, true, MIGRATION_1_2)
        // assert seeded row survives + new column has expected default
    }

    @Test fun migrate2to3() { /* ... */ }
    @Test fun migrate3to4() { /* ... */ }
    @Test fun migrate4to5() { /* assert sources DEFAULT '' */ }

    @Test fun migrateAllWayThrough() {
        helper.createDatabase("test-db", 1).apply { /* seed */ ; close() }
        helper.runMigrationsAndValidate(
            "test-db", 5, true,
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
        )
    }
}
```

- [ ] **Step 4: Run, expect fail or compile error if room-testing not configured.**

```bash
./gradlew :app:connectedDebugAndroidTest --tests com.sound2inat.storage.MigrationTest
```

- [ ] **Step 5: Iterate until each migration passes.** If a migration is buggy (e.g., `MIGRATION_4_5` `DEFAULT ''` mismatch), fix the migration body — that's the whole point of the test.

- [ ] **Step 6: Commit.**

```bash
git commit -m "test(storage): cover Room migrations 1→5 with helper-based tests"
```

---

## Task 3.2: `DraftRepository` — `suspend` + `withContext(io)` on mutations; transactional iNat delete+insert

**Context:** Review §12 — mutating methods are sync; review §16 — `INatSubmitter` does `deleteForDraft + insertAll` outside any DB transaction.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`
- Modify: `app/src/main/java/com/sound2inat/inat/INatSubmitter.kt`
- Modify: every caller of the changed methods (compile errors will surface them)
- Test: `app/src/test/java/com/sound2inat/storage/DraftRepositoryTest.kt` (extend)

- [ ] **Step 1: Self-check.** `rg -n "fun create|fun attachDetections|fun delete|fun bulkDelete" app/src/main/java/com/sound2inat/storage/DraftRepository.kt`. Note current signatures.

- [ ] **Step 2: Write failing test.** Test that mutating ops do **not** block the main dispatcher (use `Dispatchers.setMain(StandardTestDispatcher())`, call the mutating fn from the main dispatcher; expect it to suspend, not throw `IllegalStateException` from Room).

- [ ] **Step 3: Run, expect fail.**

- [ ] **Step 4: Convert.** Add `suspend` to every mutating fn; wrap body in `withContext(ioDispatcher) { db.withTransaction { ... } }`.

```kotlin
suspend fun create(...): Draft = withContext(ioDispatcher) {
    db.withTransaction { ... }
}
suspend fun attachDetections(...) = withContext(ioDispatcher) {
    db.withTransaction { ... }
}
suspend fun delete(...) = withContext(ioDispatcher) { ... }
suspend fun bulkDelete(...) = withContext(ioDispatcher) { db.withTransaction { ... } }
```

- [ ] **Step 5: Update all callers.** Compile errors point to them. Most are already in coroutine scopes — just add `suspend` where needed.

- [ ] **Step 6: Wrap iNat sync in transaction.** In `INatSubmitter.kt:73`, replace `inatObservations.deleteForDraft(...)` + `insertAll(...)` with a single `db.withTransaction { ... }`. If `INatSubmitter` does not have a `Sound2iNatDb` reference, inject it.

- [ ] **Step 7: Failing test for atomicity.** Force `insertAll` to throw mid-list; assert pre-existing rows are still there (not nuked).

- [ ] **Step 8: Run all tests.**

- [ ] **Step 9: Commit.**

```bash
git commit -m "refactor(storage): suspend+IO mutations; transactional iNat sync"
```

---

## Task 3.3: `DetectionAggregator` — incremental aggregation

**Context:** Review §13 — `addWindow` does `aggregate(incremental.toList())` per window, O(n²) and a long allocation chain.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt`
- Test: `app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt` (extend)

- [ ] **Step 1: Self-check.** Read full `DetectionAggregator.kt`. Note grouping key (likely `taxonId`/`scientificName`) and aggregation fields (max confidence, count, first/last ms).

- [ ] **Step 2: Write equivalence test.** For 1000 random windows, assert `incremental(window)` results match `batch(allWindows)` results bit-for-bit.

- [ ] **Step 3: Run, expect pass on legacy code (sanity check).**

- [ ] **Step 4: Implement incremental.** Maintain a `Map<Key, AggregatedDetection.Builder>` updated on each `addWindow` in `O(k)` where `k` = predictions in the window. `snapshot()` produces a sorted list from the map.

- [ ] **Step 5: Run equivalence test, expect pass.**

- [ ] **Step 6: Add micro-benchmark assertion** (optional): 10k windows, assert wall time < 200ms. Skip if flaky in CI.

- [ ] **Step 7: Commit.**

```bash
git commit -m "perf(inference): incremental aggregation in DetectionAggregator (O(n*k) → O(k))"
```

---

## Task 3.4: Audio + storage micro-fixes (`SourceStats` encode, `WavReader` magic, `Spectrogram` capacity)

**Context:** Three small but real correctness/perf items grouped to avoid PR fragmentation:
- Review §4 (storage) — `SourceStats.encode` does not validate `modelId` for `=`, `;`, `:`.
- Review §5 (audio) — `WavReader` parses fixed 44-byte header; should at least verify `data` magic at offset 36.
- Review §8 (audio) — `Spectrogram.process` initializes `ArrayList<FloatArray>(2)` causing reallocs in hot path.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/SourceStats.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt` (the `WavReader`)
- Modify: `app/src/main/java/com/sound2inat/audio/Spectrogram.kt`
- Test: `app/src/test/java/com/sound2inat/inference/SourceStatsTest.kt` (extend), `WavReaderTest.kt` (new), `SpectrogramTest.kt` (extend if exists)

- [ ] **Step 1: SourceStats — failing test.** `assertThrows<IllegalArgumentException> { encode(mapOf("bad=key" to stats)) }` and similar for `;`, `:`.

- [ ] **Step 2: SourceStats — fix.** Add `require(!key.contains('=' / ';' / ':')) { ... }` at top of `encode`.

- [ ] **Step 3: WavReader — failing test.** Construct a WAV header where bytes 36..39 are not `"data"`; expect `WavReader` to throw with a clear message instead of silently reading junk `dataSize`.

- [ ] **Step 4: WavReader — fix.** Read bytes 36..39, verify `== 'd','a','t','a'`. If not, throw `IllegalStateException("WAV: 'data' chunk not at offset 36 — file uses extended header, not supported")`.

- [ ] **Step 5: Spectrogram — fix capacity.** `val out = ArrayList<FloatArray>(((block.size - fftSize) / hopSize + 1).coerceAtLeast(1))`.

- [ ] **Step 6: Run all tests.**

```bash
./gradlew :app:testDebugUnitTest
```

- [ ] **Step 7: Commit.**

```bash
git commit -m "fix(audio,storage): validate SourceStats keys; verify WAV data magic; right-size Spectrogram buffer"
```

---

## PR3 acceptance & merge

- [ ] All 3.x tasks committed.
- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest detekt` passes (instrumented for migration tests).
- [ ] Manual smoke: existing recordings still open and decode; new recordings still merge predictions.

---

# PR4 — UI hygiene & localization

**Branch:** `refactor/pr4-ui-hygiene-l10n`
**Goal:** strings.xml migration, lifecycle-aware Flow collection, state hoisting in `LazyColumn` items, Review delete confirmation, theme dedup, and dead-code cleanup.

## Task 4.1: Migrate all UI strings to `strings.xml` (en)

**Context:** Review UI §5 — every screen has hard-coded strings. Decision: migrate everything to `values/strings.xml` (English only for now).

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: every Compose screen file in `app/src/main/java/com/sound2inat/app/ui/`

- [ ] **Step 1: Inventory all literal strings.** Run:

```bash
rg -n '"[A-Z][a-zA-Z .,!?:0-9'"'"'-]{2,}"' app/src/main/java/com/sound2inat/app/ui/ \
  | grep -vE 'KDoc|@Suppress|import|TAG|Log\.|"%' \
  | tee /tmp/ui-strings.txt | wc -l
```
Record total count for the PR description. Expect 100–250 hits.

- [ ] **Step 2: Author resource ids systematically.** Pattern: `<screen>_<purpose>_<role>`, e.g. `home_empty_state_title`, `review_delete_dialog_message`.

- [ ] **Step 3: Add all entries to `strings.xml`.** Group by screen with comments:

```xml
<!-- Home -->
<string name="app_name">WildEar</string>
<string name="home_record_button">Record</string>
<string name="home_empty_state_title">No recordings yet</string>
<!-- Review -->
<string name="review_species_section_title">Detected wildlife</string>
<!-- Recording -->
<string name="recording_listening_label">Listening for wildlife…</string>
...
```

- [ ] **Step 4: Replace literal usage in Composables.** Use `stringResource(R.string.home_record_button)`. For strings used outside Composables (e.g. in copy tests), keep a `const val` mirror or move the test to assert via `Resources.getString`.

- [ ] **Step 5: Update copy tests still alive.** `ReviewCopyTest` references `REVIEW_SPECIES_SECTION_TITLE`. Two choices: (a) delete the trivial constant assertion (preferred — covered by translation review), (b) keep the const as `internal val` next to its `stringResource` usage. Pick (a) per "PR4 cleanup" below.

- [ ] **Step 6: Build.**

```bash
./gradlew :app:assembleDebug
```
No build errors, no missing resource warnings.

- [ ] **Step 7: Lint check unused/missing.**

```bash
./gradlew :app:lintDebug
```
Address `MissingTranslation`/`UnusedResources` only if directly introduced by this PR.

- [ ] **Step 8: Commit.**

```bash
git commit -m "i18n: migrate all UI strings to strings.xml (en)"
```

---

## Task 4.2: Compose hygiene — `collectAsStateWithLifecycle`, theme `inat_green`, formatter caching

**Context:** UI §1 — `collectAsState()` everywhere; UI §4 — `INAT_GREEN` duplicated in 3 files; UI §7 — `SimpleDateFormat` allocated per recomposition. One coherent UI-cleanliness commit.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt` (and `SpeciesDetailsSheet.kt`)
- Modify: `app/src/main/res/values/colors.xml`
- Modify: theme color file under `app/.../ui/theme/`

- [ ] **Step 1: Replace `collectAsState()` with `collectAsStateWithLifecycle()`.** Run:

```bash
rg -l "\.collectAsState\(" app/src/main/java/com/sound2inat/app/ui/
```
Replace import + every callsite.

- [ ] **Step 2: Add `inat_green` to theme.**

```xml
<!-- colors.xml -->
<color name="inat_green">#FF74AC00</color>
```
And in `Color.kt`:

```kotlin
val Color.Companion.InatGreen: Color get() = Color(0xFF74AC00)
// or extend MaterialTheme.colorScheme via custom @Composable get
```

- [ ] **Step 3: Replace 3 `Color(0xFF74AC00)` literals.** `rg -n '0xFF74AC00' app/src/main/` — should produce exactly 3 hits before, 0 after.

- [ ] **Step 4: Cache formatters.** Top-level in `HomeScreen.kt`:

```kotlin
private val timestampFormatter = SimpleDateFormat("MMM d, HH:mm", Locale.US)
private val dayFormatter = SimpleDateFormat("MMM d, yyyy", Locale.US)
```
Replace local `SimpleDateFormat(...)` constructions with these.

- [ ] **Step 5: Build + screen-by-screen smoke.**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 6: Commit.**

```bash
git commit -m "ui: lifecycle-aware Flow collection; centralize INAT_GREEN; cache date formatters"
```

---

## Task 4.3: `HomeScreen` state hoisting (`@Immutable DraftRow`, enriched VM flow)

**Context:** UI §2 + §3 — `RecordingCard(vm: HomeViewModelHilt, ...)` opens 3 Flows per item; `DraftSummary` is not `@Immutable`. Lift state up.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt`

- [ ] **Step 1: Define enriched row.**

```kotlin
@Immutable
data class DraftRow(
    val draft: DraftSummary,
    val topLabel: String?,
    val topSpecies: SpeciesPreview?,
    val detectionCount: Int,
    val inatCount: Int,
    val taxonPhotoUrl: String?,
)
```

- [ ] **Step 2: Build `enrichedDrafts: StateFlow<List<DraftRow>>` in VM.** Use `combine(...)` over the existing flows; throttle if needed via `debounce(50)`.

- [ ] **Step 3: Failing test.** A VM unit test asserts that `enrichedDrafts.first()` returns the right composition for a fixture set.

- [ ] **Step 4: Run, expect fail (flow does not yet exist).**

- [ ] **Step 5: Implement enrichedDrafts.** Replace `vm.observeTopLabel` etc. callsites in `HomeScreen.kt` with reads of the row.

- [ ] **Step 6: `RecordingCard` signature change.**

```kotlin
@Composable
private fun RecordingCard(
    row: DraftRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
)
```
No `vm` parameter. All flow subscriptions removed from the item body.

- [ ] **Step 7: Annotate `DraftSummary` with `@Immutable`** (it already is a `data class` of primitives — Compose may infer, but explicit annotation removes inference cost).

- [ ] **Step 8: Run tests + manual smoke.** Scrolling Home should not stutter; recompose count for an unchanged row should be 0 in Layout Inspector.

- [ ] **Step 9: Commit.**

```bash
git commit -m "ui(home): lift Flow subscriptions to VM; @Immutable DraftRow; stable RecordingCard"
```

---

## Task 4.4: UX fixes — Review delete confirmation + Checkbox 48dp

**Context:** UI §6 — `Modifier.size(20.dp)` on Checkbox kills accessibility; UI §8 — Review delete fires immediately without confirmation while Home requires it (asymmetric UX).

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`

- [ ] **Step 1: Failing UI test (optional).** If you have Compose testing wired up, write a `composeRule` test asserting that clicking Delete in TopAppBar shows a dialog. Otherwise, manual verification suffices.

- [ ] **Step 2: Add confirmation dialog in `ReviewScreen`.** Mirror the `SingleDeleteDialog` pattern from `HomeScreen`.

```kotlin
var showDeleteConfirm by remember { mutableStateOf(false) }
if (showDeleteConfirm) {
    AlertDialog(
        onDismissRequest = { showDeleteConfirm = false },
        title = { Text(stringResource(R.string.review_delete_dialog_title)) },
        text = { Text(stringResource(R.string.review_delete_dialog_message)) },
        confirmButton = {
            TextButton(onClick = { showDeleteConfirm = false; vm.delete(onDeleted = onBack) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) {
            Text(stringResource(R.string.action_cancel)) } },
    )
}
```
Then `IconButton(onClick = { showDeleteConfirm = true })`.

- [ ] **Step 3: Remove `Modifier.size(20.dp)` from Checkbox** at `ReviewScreen.kt:663`. Material default is 48dp tap target with built-in 20dp visual.

- [ ] **Step 4: Build + smoke.**

- [ ] **Step 5: Commit.**

```bash
git commit -m "ui(review): confirm before delete; restore Checkbox 48dp tap target"
```

---

## Task 4.5: Cleanup — drop trivial copy tests + merge ViewModelHilt wrappers

**Context:** Storage/tests §8 — `HomeCopyTest` and `RecordingCopyTest` test constants only. Architecture §4 — `HomeViewModel` + `HomeViewModelHilt` and `SettingsViewModel` + `SettingsViewModelHilt` use a delegate pattern where the inner VM's `viewModelScope` is never properly cleared.

**Files:**
- Delete: `app/src/test/java/com/sound2inat/app/ui/home/HomeCopyTest.kt`
- Delete: `app/src/test/java/com/sound2inat/app/ui/recording/RecordingCopyTest.kt`
- Modify: `app/src/test/java/com/sound2inat/app/ui/review/ReviewCopyTest.kt` (drop `REVIEW_SPECIES_SECTION_TITLE` const-equality test; keep `reviewSubmitLabel` and `speciesRowTrailingLabel` logic tests)
- Modify: `app/src/main/java/com/sound2inat/app/ui/home/HomeViewModel.kt` (merge `HomeViewModelHilt` into `HomeViewModel`)
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt` (same)

- [ ] **Step 1: Delete copy tests.**

```bash
rm app/src/test/java/com/sound2inat/app/ui/home/HomeCopyTest.kt
rm app/src/test/java/com/sound2inat/app/ui/recording/RecordingCopyTest.kt
```

- [ ] **Step 2: Trim `ReviewCopyTest`.** Keep only the two methods that test logic, not constants.

- [ ] **Step 3: Merge VMs.** For `HomeViewModel`: make it `@HiltViewModel` directly; `@Inject constructor`; delete `HomeViewModelHilt`. Update every `hiltViewModel<HomeViewModelHilt>()` callsite. Same for `SettingsViewModel` (be careful: `SettingsViewModel` constructor was given default-valued `themeModeFlow`/`writeThemeMode` for tests — keep them).

- [ ] **Step 4: Compile + run all unit tests + detekt.**

```bash
./gradlew :app:testDebugUnitTest detekt
```

- [ ] **Step 5: Commit.**

```bash
git commit -m "refactor(ui): drop copy tests; merge Hilt-wrapper VMs into single classes"
```

---

## PR4 acceptance & merge

- [ ] All 4.x tasks committed.
- [ ] No remaining `Color(0xFF74AC00)` literal in `app/src/main/`.
- [ ] No remaining `.collectAsState()` in `ui/` (verified via `rg`).
- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug detekt` passes.
- [ ] Manual smoke: every screen renders identically (modulo dialogs); long-press delete on Home and tap delete in Review both prompt; scrolling Home is smooth.

---

# Cross-PR final verification

After PR4 lands:

- [ ] `./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug detekt` — green.
- [ ] `git log --oneline master..` shows ≤ ~25 focused commits.
- [ ] Open the original review report in `docs/superpowers/specs/` (or this plan's preamble); cross-check that every numbered finding (1–20) has a corresponding commit message that references it. Mark any unaddressed item explicitly as deferred with rationale.
- [ ] **Investigate `Settings.inatToken` (architecture review §7):** run `rg -n 'settings\.inatToken|setInatToken' app/src/main/`. If only legacy migration code references it, delete the field + key. If the app still reads it on startup, file a follow-up task (do **not** silently drop — token loss = forced re-login). This is intentionally deferred from the 4-PR plan because it requires a small investigation.

---

## Self-review checklist (planning artifact, not for executors)

**Spec coverage:**
- §1 (ReviewVM size) → Task 2.1 + 2.2.
- §2 (cancel race) → Task 1.2.
- §3 (OkHttp) → Task 1.3.
- §4 (engine backpressure) → Task 1.1.
- §5 (stale token) → Task 1.4.
- §6 (controller scope) → Task 1.2 (combined).
- §7 (migration tests) → Task 3.1.
- §8 (collectAsStateWithLifecycle) → Task 4.2.
- §9 (RecordingCard hoisting) → Task 4.3.
- §10 (regional cache) → Task 2.3.
- §11 (photoFlows) → Task 2.4.
- §12 (DraftRepository suspend) → Task 3.2.
- §13 (Aggregator O(n²)) → Task 3.3.
- §14 (Review delete confirmation) → Task 4.4.
- §15 (ensureMigrated atomicity) → Task 1.4 (combined).
- §16 (iNat sync transaction) → Task 3.2 (combined).
- §17 (modelId hard-coded ×3) → Task 2.5.
- §18 (Checkbox 48dp) → Task 4.4 (combined).
- §19 (strings.xml) → Task 4.1.
- §20 (INAT_GREEN dedup) → Task 4.2 (combined).
- Less-critical: SimpleDateFormat → 4.2; Spectrogram capacity → 3.4; WavReader magic → 3.4; SourceStats encode → 3.4; copy tests → 4.5; ViewModelHilt delegates → 4.5.

All 20 numbered items + 6 less-critical items mapped. No gaps.
