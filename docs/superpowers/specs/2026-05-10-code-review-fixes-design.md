# Design: Code Review Fixes — Wild Ear

**Date:** 2026-05-10  
**Scope:** All issues found in architectural review (11 CRITICAL · 22 MAJOR · 16 MINOR)  
**Source:** `CODE_REVIEW_2026-05-10.md` + Codex review

---

## Overview

Fix all issues found during the automated multi-agent code review. Work is split into 9 tasks across 3 phases ordered by severity. Each task is self-contained, runs in an isolated context, and ends with a full CI pass:

```bash
./gradlew detekt lint --no-daemon
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebug --no-daemon
```

---

## Phase 1: CRITICAL

### Task 1 — CRITICAL: Inference pipeline

**Files:** `InterpreterFactory.kt`, `BirdNetMetaModel.kt`, `YamNetTfliteGate.kt`, `LiveInferenceEngine.kt`

**Changes:**

**InterpreterFactory.kt** (строки 43–68):  
`RandomAccessFile` и `FileChannel` открываются до `try`-блока и не защищены `finally`. Если оба конструктора `Interpreter` (NNAPI + CPU fallback) бросают исключение, файловые дескрипторы утекают.  
Fix: обернуть в `try/catch(t: Throwable) { raf.close(); throw t }`.

**BirdNetMetaModel.kt** (строки 49–61):  
`ensureLoaded()` защищена `Mutex`, но сам `interp.run(input, output)` вызывается снаружи. При параллельных вызовах (два черновика на `Dispatchers.IO`) два потока одновременно используют не-thread-safe TFLite интерпретатор.  
Fix: вынести весь блок `val interp = interpreter ... interp.run(...)` внутрь `mutex.withLock { }`.

**YamNetTfliteGate.kt** (строки 34–67):  
— Та же проблема: `interp.run` вне Mutex при параллельном пути `runParallel` (parallelism=2).  
— Нет метода `close()` — интерпретатор удерживает нативную TFLite-память до GC при каждом создании `InferenceRunner`.  
Fix: добавить `Mutex` вокруг `classifyInternal`; реализовать `Closeable` с `close()`, вызывающим `interpreter.close()`.

**LiveInferenceEngine.kt** (строки 231–238):  
— `private val model: BioacousticModel` нигде не закрывается — resource leak при каждой записи.  
— Нет защиты от повторного вызова `start()`: второй `start()` откроет модель поверх первой и создаст утечку `Job`.  
Fix: вызывать `model.close()` в `stop()`; добавить `AtomicBoolean started` и `check(!started.getAndSet(true))`.

**Также в InferenceRunner.kt**: добавить вызов `gate?.close()` при закрытии моделей в sequential и parallel путях — после добавления `Closeable` к `YamNetTfliteGate`.

---

### Task 2 — CRITICAL: UI layer

**Files:** `LiveSpectrogramView.kt`, `ReviewViewModel.kt`, `ReviewScreen.kt`

**Changes:**

**LiveSpectrogramView.kt** (строки 85–126):  
Bitmap мутируется напрямую через `setPixels()` из suspend-кода, уведомление Compose — через хак `revision++`. GPU может читать текстуру одновременно с записью CPU.  
Fix: использовать `mutableStateOf<ImageBitmap?>` с двойной буферизацией — рисовать в back-buffer `Bitmap`, после завершения атомарно заменять `MutableState<ImageBitmap>`. Compose видит только законченные кадры.

**ReviewViewModel.kt** (строки 383–407, 653–679, 719–722):  
Паттерн `_state.value = _state.value.copy(...)` в конкурентных корутинах — read-modify-write не атомарен. Три корутины (position, isPlaying, lastError) конкурируют, последняя запись затирает изменения других.  
Fix: заменить **все** `_state.value = _state.value.copy(...)` на `_state.update { it.copy(...) }` в файле.

**ReviewViewModel.kt** (строка 182) + **ReviewScreen.kt**:  
`ReviewViewModel` создаётся через `ReviewViewModelFactory.create()` вне `ViewModelStore`. `onCleared()` не вызывается автоматически, корутины на `viewModelScope` не отменяются при конфиг-изменении.  
Fix: привязать `ReviewViewModel` к `ViewModelStore` с ключом `"review_$draftId"` через кастомный `ViewModelStoreOwner`, либо убрать `ownsScope` и всегда требовать `externalScope` от `ReviewPagerViewModel`.

---

### Task 3 — CRITICAL: Data layer + Audio

**Files:** `DraftRepository.kt`, `ModelManager.kt`, `WavWriter.kt`, `WavTrimmer.kt`

**Changes:**

**DraftRepository.kt** (строки 241–242):  
`observeWithDetections(draftId).first()` вызывается внутри `persistMutex.withLock {}`. Если черновик удалён в момент гонки, `mapNotNull` отфильтрует результат, `first()` зависнет навсегда, заморозив весь inference pipeline для всех черновиков.  
Fix:
```kotlin
val draft = drafts.getById(draftId) ?: error("draft $draftId missing")
val detList = detections.listForDraft(draftId)
val dwd = DraftWithDetections(draft, detList)
```

**ModelManager.kt** (строки 60–62):  
`File.renameTo()` возвращает `Boolean`, который игнорируется. При кросс-filesystem rename тихо вернёт `false`, файл не переместится, но `emit(Ready)` будет отправлен.  
Fix:
```kotlin
check(modelTmp.renameTo(mFinal)) { "Failed to rename model file to ${mFinal.name}" }
check(labelsTmp.renameTo(lFinal)) { "Failed to rename labels file to ${lFinal.name}" }
```

**WavWriter.kt** (строка 15):  
`var dataBytesWritten: Int = 0` переполнится через ~6.2ч при 48 kHz/16-bit/mono → отрицательный размер в RIFF-заголовке → битый WAV.  
Fix: `var dataBytesWritten: Long = 0L`; адаптировать `patchHeader()` для записи `Long` в 4-байтовые поля (WAV ограничен 4 ГБ, добавить `require(dataBytesWritten <= 0xFFFFFFFFL)`).

**WavTrimmer.kt** (строки 82–87):  
`leU32` возвращает `Int` → знаковое переполнение для файлов >2 ГБ → отрицательный `dataBytes` → crash в `check(endSample > startSample)`. Также `msToSamples` с `Int` переполнится ~через 12 минут при 48 kHz.  
Fix: вернуть `Long` из `leU32` (маска `and 0xFFFFFFFFL`); `msToSamples` → `Long` арифметика.

---

## Phase 2: MAJOR

### Task 4 — MAJOR: Inference pipeline

**Files:** `InferenceRunner.kt`, `LiveInferenceEngine.kt`, `InferenceUseCase.kt`, `InferenceQueue.kt`, `BirdNetTfliteModel.kt`, `PerchTfliteModel.kt`, `YamNetGateResult.kt`

**Changes:**

**Дедублирование soft-gate логики** (InferenceRunner.kt:125–127, 178–180; LiveInferenceEngine.kt:224–226):  
Одинаковый блок в 3 местах + константа `HIGH_CONFIDENCE_OVERRIDE = 0.7f` в 2 companion object.  
Fix: добавить в `YamNetGateResult.kt` (или отдельный файл):
```kotlin
fun YamNetGateResult?.suppressesPredictions(predictions: List<WindowPrediction>): Boolean =
    this?.recommendation == GateRecommendation.DOWNRANK &&
    predictions.none { it.confidence >= HIGH_CONFIDENCE_OVERRIDE }
```
Заменить три блока на вызов этой функции. Удалить дублирующую константу.

**InferenceQueue.kt** (job.invoke() без try/catch):  
При непойманном `Throwable` Channel-цикл завершается, все последующие задачи «замерзают».  
Fix: обернуть в `try/catch(t: Throwable) { Log.e(TAG, "Job failed", t); _failedJobs.value = ... }`, продолжать цикл.

**BirdNetTfliteModel.kt:37, PerchTfliteModel.kt:43** (load() без guard):  
Повторный `load()` без `close()` перезаписывает `interp`, оставляя предыдущий интерпретатор открытым.  
Fix: добавить в начало `load()`:
```kotlin
interp?.close()
interp = null
```

**InferenceRunner.kt — WavReader.readLeUint32** (строки 236–240):  
Возвращает `Int` → `NegativeArraySizeException` для WAV >2 ГБ.  
Fix: вернуть `Long` (маска `and 0xFFFFFFFFL`).

**InferenceUseCase.kt** (строки 294–297) — perch ownership:  
DI-синглтон `perch` передаётся в `InferenceRunner` как owned-объект — нарушение контракта.  
Fix: `repeat(parallelism) { add(perch.newInstance()) }` — всегда создавать свежие экземпляры.

**InferenceRunner.kt** — `runParallel` completedWindows:  
`var completedWindows` может быть общей переменной с data race при параллельном инкременте.  
Fix: проверить реализацию; если `var`, заменить на `AtomicInteger`.

---

### Task 5 — MAJOR: UI — точечные исправления

**Files:** `RecordingScreen.kt`, `RecordingViewModel.kt`, `WaveformAndSpectrogram.kt`, `RadarScreen.kt`, `SettingsViewModel.kt`, `ReviewScreen.kt`, `HomeScreen.kt`, `Color.kt` (или `Theme.kt`)

**Changes:**

**collectAsState → collectAsStateWithLifecycle** (WaveformAndSpectrogram.kt:44):  
Flow собирается в фоне каждые 50 мс.  
Fix: заменить на `collectAsStateWithLifecycle()`. Проверить и исправить аналогичные места в `RadarScreen.kt`.

**SettingsViewModel.init** (строки 69–126):  
13 параллельных `scope.launch` → 13 перекомпозиций + не атомарные обновления.  
Fix: объединить в `combine(flow1, flow2, ...) { ... }.launchIn(scope)` с единым обновлением `_state`.

**HorizontalPager beyondViewportPageCount** (ReviewScreen.kt:133–155):  
При свайпе Compose уничтожает соседние страницы → `player.release()` во время анимации.  
Fix: добавить `beyondViewportPageCount = 1`.

**Дедупликация INAT_GREEN и форматирования** (HomeScreen.kt:482, ReviewScreen.kt:911, SpeciesDetailsSheet.kt:315):  
Fix: добавить в `Theme.kt` (или `Color.kt`): `val iNatGreen = Color(0xFF74AC00)`. Создать `UiUtils.kt` с `fun formatDurationMs(ms: Long): String`. Заменить 3 константы и 4 функции.

**ReviewScreen — LaunchedEffect(draftId)** (строки ~90):  
При recomposition может запуститься дважды.  
Fix: перенести инициализацию загрузки в `init { }` ViewModel, draftId получать из `SavedStateHandle`.

**RecordingViewModel.hasSeenRecording**:  
Не сбрасывается при повторной навигации на экран записи — показывает Done вместо нового сеанса.  
Fix: сбрасывать `hasSeenRecording = false` в `onCleared()` или при старте нового сеанса.

---

### Task 6 — MAJOR: UI — рефакторинг ViewModel

**Files:** `HomeViewModel.kt`, `HomeViewModelHilt.kt`, `RecordingViewModel.kt`, `RecordingViewModelHilt.kt` (и аналоги для Settings, Radar), соответствующие Screen-файлы

**Цель:** устранить архитектурный антипаттерн "двойного ViewModel" — тестируемый класс + Hilt-обёртка-делегат.

**Подход:** использовать `@HiltViewModel` + `@AssistedInject` (для параметров, требующих runtime-значений) + существующий `SwappableModule` для тестовых подмен.

**Порядок:**
1. `HomeViewModel` + `HomeViewModelHilt` → один `@HiltViewModel HomeViewModel`
2. `SettingsViewModel` + `SettingsViewModelHilt` → один `@HiltViewModel SettingsViewModel`
3. `RadarViewModel` + `RadarViewModelHilt` → один `@HiltViewModel RadarViewModel`
4. `RecordingViewModel` + `RecordingViewModelHilt` → один `@HiltViewModel RecordingViewModel` с `@AssistedInject` для `PermissionsController`

Убрать `hilt.delegate` из `SettingsScreen.kt` и `RecordingScreen.kt`.

---

### Task 7 — MAJOR: Data layer

**Files:** `DraftRepository.kt`, `DraftDao.kt`, `DraftEntity.kt`, `DraftDatabase.kt` (миграция), `AggregatedDetection.kt` или `DraftRepository.kt`

**Changes:**

**DraftRepository — mergeBySpecies внутри транзакции** (строки ~240–270):  
Вычисление происходит вне `runInTransaction` → при OOM между вычислением и транзакцией детекции задублируются.  
Fix: перенести весь вычислительный блок `mergeBySpecies` внутрь `db.runInTransaction { }`.

**DraftDao — условный updateStatus**:  
Нет валидации допустимых переходов статуса.  
Fix:
```kotlin
@Query("UPDATE drafts SET status = :newStatus WHERE id = :id AND status = :expectedStatus")
suspend fun updateStatusConditional(id: String, newStatus: DraftStatus, expectedStatus: DraftStatus): Int
```
В репозитории проверять, что `affected > 0`.

**DraftEntity — удалить orphaned поля** (строки 22–23):  
`inatObservationId: Long?` и `inatObservationUrl: String?` — замена таблицей `inat_observations` в v2→v3, но поля остались.  
Fix: удалить поля + добавить миграцию v7 (пересоздать таблицу `drafts` без этих колонок, т.к. SQLite не поддерживает `DROP COLUMN` до API 35).

**DraftRepository.delete — логировать ошибки** (строки 212–216):  
`File.delete()` игнорирует результат.  
Fix: добавить `if (!result) Log.w(TAG, "Failed to delete file: $path")` для каждого удаления.

**Дедупликация маппинга AggregatedDetection → DetectionEntity**:  
24 строки скопированы в `createWithDetections` и `attachDetections`.  
Fix: извлечь `private fun AggregatedDetection.toEntity(draftId: String): DetectionEntity`.

---

### Task 8 — MAJOR: Audio + iNat

**Files:** `Recorder.kt`, `AndroidAudioRecordSource.kt`, `WavWriter.kt`, `INatAuthRepository.kt`, `INatSubmitter.kt`, `INatTokenStorage.kt`, `INaturalistClient.kt`, `RegionalStatusRepository.kt`

**Changes:**

**Recorder.kt** (строки 147–155):  
`job?.cancel()` без `job?.join()` перед `writer?.close()` → гонка pump/close → NPE.  
Fix: `job?.cancelAndJoin()` (или `job?.cancel(); job?.join()`).  
Также: в `cancel()`-пути вызывать `audioSource.stop()` перед `audioSource.close()`/`release()`.

**AndroidAudioRecordSource.kt** (строка ~75):  
MIC-fallback не проверяет `STATE_INITIALIZED` → `IllegalStateException` при `startRecording()`.  
Также: `getMinBufferSize()` без проверки на `ERROR`/`ERROR_BAD_VALUE`.  
Fix: `check(minBufSize > 0) { "AudioRecord: unsupported config" }`; добавить `check(record.state == AudioRecord.STATE_INITIALIZED)` для MIC-fallback.

**WavWriter.kt** — crash recovery:  
При force-stop файл имеет нулевой размер в заголовке → `WavReader` читает 0 фреймов.  
Fix: периодически (например, каждые 10 секунд или каждые N байт) перезаписывать заголовок через `seek(0)` + patching.

**INatAuthRepository.kt** (строки 134–158):  
— При успехе токена `WebView.destroy()` не вызывается — 15 секунд утечки.  
— Нет защиты от конкурентного refresh: два параллельных 401 → двойной refresh → разлогин.  
Fix: вызвать `webView.destroy()` перед `cont.resume(token)`. Добавить `Mutex` или `singletonRefreshJob` (если refresh уже идёт — ждать его результата).

**INatSubmitter.kt** (строка 183) + retry:  
— `taxonId = null` при создании наблюдения — всегда genus-level. Это архитектурное решение: iNat сообщество уточняет таксон через идентификации. Добавить комментарий объясняющий намерение (сейчас код выглядит как баг).  
— Нет retry при сетевых ошибках.  
Fix: добавить exponential backoff retry (3 попытки, 1s/2s/4s) для `IOException` и 5xx; не retry на 4xx (кроме 401 с refresh).

**INatTokenStorage.kt**:  
OAuth-токены в обычных `SharedPreferences`.  
Fix: перейти на `EncryptedSharedPreferences` из `androidx.security.crypto`.

**INaturalistClient.kt** (строки ~79, 349, 620+):  
`ResponseBody` не закрывается при ошибках парсинга → исчерпание OkHttp connection pool.  
Fix: обернуть все `response.body()` в `?.use { }`.

**RegionalStatusRepository.kt** (строки 51–53):  
Check-then-act без блокировки → дублирующие сетевые запросы.  
Fix: добавить `Mutex` или `ConcurrentHashMap<taxonId, Deferred<Status>>` (одна корутина на ключ).

---

## Phase 3: MINOR

### Task 9 — MINOR: Все мелкие исправления

**Inference:**
- `InferenceRunner.kt` — прогресс при `runParallel`: убедиться, что `_progress` обновляется монотонно; если нет — добавить `max` при обновлении.
- `PostRecordingProcessor.kt:35–39` — диапазон Float→Short: умножать на `32768f` вместо `Short.MAX_VALUE` (32767) для симметричного диапазона.

**UI:**
- `RecordingViewModel.kt:109`, `ReviewViewModel.kt:530,549` — FileProvider authority `"com.sound2inat.app.fileprovider"` вынести в константу в `AppModule` или `BuildConfig`.
- `RecordingScreen.kt:76` — `LaunchedEffect(Unit)` → `LaunchedEffect(vm)`.
- `HomeScreen.kt:508–509` — `SimpleDateFormat` → `DateTimeFormatter` (API 26+, minSdk=28).
- `ReviewScreen.kt:181–182` — удалить мёртвый код `val uploadedIds = remember(...)`.

**Data:**
- `ModelInstallState.kt:7` — `data class Verifying(val ready: Boolean)`: убрать параметр `ready` (никогда не `true`) → `data object Verifying`.
- `MigrationTest.kt` — добавить chain-тест v1→v6 (существует только v1→v5 и отдельный v5→v6).
- `ModelManager.kt:41` — `withContext(Dispatchers.IO)` → `withContext(ioDispatcher)`.

**Audio / iNat:**
- `INatObservationsRepository.kt:33` — `@Inject`-конструктор жёстко привязан к `System.currentTimeMillis()`; передавать `clock` из Hilt через `@Named`.
- `FusedLocationProvider.kt:36–52` — обернуть `lastLocation`-fallback в `withTimeoutOrNull(timeoutMs)`.
- `INaturalistClient.kt:79,349` — использовать `HttpUrl.Builder` вместо конкатенации строк для корректного encoding.

**Общее:**
- `LiveInferenceEngine.kt` — показывать предупреждение в UI когда `_droppedOnFull > 0` при завершении записи (`RecordingViewModel` → `RecordingUiState`).
- Создать `AudioConstants.kt` с `object AudioConstants` — вынести magic numbers: sample rates, window sizes, shared между `LiveInferenceEngine`, `BirdNetTfliteModel`, `PerchTfliteModel`, `WavWriter`.

---

## CI требования для каждой задачи

```bash
./gradlew detekt lint --no-daemon        # статический анализ
./gradlew testDebugUnitTest --no-daemon  # unit тесты
./gradlew assembleDebug --no-daemon      # сборка APK
```

Все три шага должны пройти без ошибок. При детект/линт нарушениях — исправлять, а не подавлять через `@Suppress` без обоснования.

---

## Зависимости между задачами

- Задача 2 зависит частично от задачи 1 (gate.close() в InferenceRunner добавляется после Task 1)
- Задача 5 частично пересекается с задачей 6 (RecordingViewModel scope): Task 6 делает полный рефакторинг, Task 5 делает минимальный fix — выполнять Task 5 до Task 6
- Задача 7 (DraftEntity миграция v7) должна быть выполнена до любых изменений схемы Room

Все остальные задачи независимы между собой.
