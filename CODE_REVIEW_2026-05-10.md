# Code Review — Wild Ear — 2026-05-10

Автоматический архитектурный ревью по 4 доменам (параллельные агенты).
Итого: **11 CRITICAL · 22 MAJOR · 16 MINOR**

---

## CRITICAL

### I-1 · Resource leak в TfliteInterpreterFactory
**Файл:** `app/src/main/java/com/sound2inat/inference/InterpreterFactory.kt`, строки 43–68  
**Проблема:** `RandomAccessFile` и `FileChannel` открываются до блока `try`, но не обёрнуты в `try-finally`. Если первый `Interpreter(buffer, opts)` (NNAPI) упал, а второй (CPU fallback) тоже упал — `raf` и `channel` никогда не закрываются.  
**Исправление:**
```kotlin
val raf = RandomAccessFile(modelFile, "r")
try {
    val channel = raf.channel
    val buffer = channel.map(...)
    val interpreter = buildInterpreter(buffer, threads)
    return wrapInterpreter(interpreter, raf, channel, nnapi)
} catch (t: Throwable) {
    raf.close()
    throw t
}
```

---

### I-2 · Гонка данных в BirdNetMetaModel — interp.run без мьютекса
**Файл:** `app/src/main/java/com/sound2inat/inference/BirdNetMetaModel.kt`, строки 49–61  
**Проблема:** `ensureLoaded()` защищена Mutex, но сам вызов `interp.run(input, output)` происходит снаружи мьютекса. Два параллельных вызова `priorsByScientificName` (разные черновики на IO-диспетчере) одновременно вызовут не-thread-safe TFLite интерпретатор.  
**Исправление:** вынести весь блок `val interp = interpreter ... interp.run(...)` под `mutex.withLock { }`.

---

### I-3 · Гонка данных в YamNetTfliteGate — interp.run без мьютекса
**Файл:** `app/src/main/java/com/sound2inat/inference/YamNetTfliteGate.kt`, строки 34–67  
**Проблема:** та же структура что I-2. При `parallelism=2` оба `async`-воркера `runParallel` вызывают единственный `YamNetGate`-инстанс без синхронизации — гарантированная гонка данных в production.  
**Исправление:** защитить `classifyInternal` мьютексом, либо задокументировать как инвариант "не допускает параллельных вызовов" и создавать по одному инстансу на воркер.

---

### I-4 · Resource leak — LiveInferenceEngine не закрывает model
**Файл:** `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`, строки 231–238  
**Проблема:** `private val model: BioacousticModel` нигде не вызывает `close()` — ни в `stop()`, ни в каком-либо другом методе. Каждая запись протекает TFLite-интерпретатором.  
**Исправление:** добавить `model.close()` в `stop()`.

---

### U-1 · Race condition с GPU в LiveSpectrogramView
**Файл:** `app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt`, строки 85–126  
**Проблема:** `bitmap` создаётся через `remember`, но мутируется напрямую через `bitmap.setPixels(...)` из suspend-кода `LaunchedEffect`. Уведомление Compose об изменении — хак `revision++`. GPU может читать текстуру в тот же момент, когда CPU её перезаписывает.  
**Исправление:** использовать двойную буферизацию (`mutableStateOf<Bitmap>` с атомарной заменой), либо перейти на Compose `Canvas` с прямым рисованием без пиксельного буфера.

---

### U-2 · Не атомарный read-modify-write в ReviewViewModel
**Файл:** `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`, строки 383–407  
**Проблема:** паттерн `_state.value = _state.value.copy(...)` в трёх конкурентных корутинах (position каждые 50 мс, isPlaying, lastError). Read-modify-write не атомарен — последняя запись затирает изменения параллельной корутины. Та же проблема в строках 653–679 и 719–722.  
**Исправление:** заменить все `_state.value = _state.value.copy(...)` на `_state.update { it.copy(...) }`.

---

### U-3 · ReviewViewModel вне ViewModelStore — утечка корутин
**Файл:** `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`, строка 182; `ReviewScreen.kt`  
**Проблема:** `ReviewViewModel` создаётся через `ReviewViewModelFactory.create()` и не регистрируется в `ViewModelStore`. `onCleared()` не вызывается автоматически. `release()` должен приходить из `DisposableEffect`, но при конфигурационном изменении до попадания страницы в кэш — корутины на `viewModelScope` остаются живыми.  
**Исправление:** привязать к `ViewModelStore` через кастомный ключ по draftId, либо всегда передавать `externalScope` и убрать `ownsScope`.

---

### D-1 · Потенциальный дедлок в mergeAndPersist
**Файл:** `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`, строки 241–242  
**Проблема:**
```kotlin
suspend fun mergeAndPersist(...) = persistMutex.withLock {
    val dwd = observeWithDetections(draftId).first()  // внутри мьютекса
```
Если черновик удалён в момент гонки, `mapNotNull` отфильтрует результат, `first()` зависнет навсегда, держа `persistMutex` заблокированным для всех черновиков.  
**Исправление:**
```kotlin
val dwd = withContext(ioDispatcher) {
    val draft = drafts.getById(draftId) ?: error("draft $draftId missing")
    val detList = detections.listForDraft(draftId)
    DraftWithDetections(draft, detList)
}
```

---

### D-2 · ModelManager.install игнорирует результат renameTo
**Файл:** `app/src/main/java/com/sound2inat/modelmanager/ModelManager.kt`, строки 60–62  
**Проблема:** `File.renameTo()` возвращает `Boolean`, который игнорируется. При кросс-filesystem rename тихо вернёт `false`, файл не переместится, но `emit(ModelInstallState.Ready(...))` будет отправлен. Пользователь видит "модель установлена", TFLite не сможет загрузить несуществующий файл.  
**Исправление:**
```kotlin
check(modelTmp.renameTo(mFinal)) { "Failed to rename model file" }
check(labelsTmp.renameTo(lFinal)) { "Failed to rename labels file" }
```

---

### A-1 · WavWriter: dataBytesWritten: Int переполнится за ~6 часов
**Файл:** `app/src/main/java/com/sound2inat/recorder/WavWriter.kt`, строка 15  
**Проблема:** `var dataBytesWritten: Int = 0`. При 48 kHz, 16 bit, моно — 96 000 байт/с. `Int.MAX_VALUE` (~2.1 ГБ) достигается через ~6.2 часа. При переполнении `patchHeader()` запишет отрицательный размер в RIFF-заголовок → битый WAV.  
**Исправление:** `var dataBytesWritten: Long = 0L`, привести `patchHeader()` к записи `Long`.

---

### A-2 · WavTrimmer: leU32 возвращает Int → знаковое переполнение
**Файл:** `app/src/main/java/com/sound2inat/inat/WavTrimmer.kt`, строки 82–87  
**Проблема:** `leU32` возвращает `Int`. Для файлов >2 ГБ старший бит установлен → отрицательный `dataBytes` → `check(endSample > startSample)` → `IllegalStateException` с непонятным сообщением. Кроме того, `msToSamples` использует `Int`: переполнение уже ~12 минут при 48 кГц.  
**Исправление:** вернуть из `leU32` тип `Long` (маска `0xFFFFFFFFL`); `msToSamples` → `Long`.

---

## MAJOR

### Inference pipeline

#### I-M1 · Дублирование soft-gate логики в 3 местах
**Файлы:** `InferenceRunner.kt:125–127` (sequential), `InferenceRunner.kt:178–180` (parallel), `LiveInferenceEngine.kt:224–226`  
Идентичный блок:
```kotlin
if (!hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
    if (predictions.none { it.confidence >= HIGH_CONFIDENCE_OVERRIDE }) continue
}
```
Константа `HIGH_CONFIDENCE_OVERRIDE = 0.7f` объявлена в двух `companion object`. При изменении порога — два места.  
**Исправление:** вынести в extension-функцию на `YamNetGateResult?`.

#### I-M2 · runParallel передаёт единственный YamNetGate двум воркерам
**Файл:** `InferenceRunner.kt:155–165`  
Прямое следствие I-3: при parallelism=2 оба `async`-блока вызывают `yamNetGate?.classify(...)` на одном инстансе без защиты.

#### I-M3 · Двойная запись в _failedJobs при сбое BirdNET + Perch
**Файл:** `app/src/main/java/com/sound2inat/app/inference/InferenceQueue.kt`, строки 127–165  
При сбое BirdNET записывается первая ошибка; Perch запускается независимо; при сбое Perch затирает первую ошибку. Пользователь видит только ошибку Perch.

#### I-M4 · load() не защищает от двойной загрузки без close()
**Файлы:** `BirdNetTfliteModel.kt:37`, `PerchTfliteModel.kt:43`  
Повторный `load()` без предшествующего `close()` перезаписывает `interp` новым значением, оставляя предыдущий интерпретатор открытым (resource leak).  
**Исправление:** в начале `load()` вызывать `close()` или `check(interp == null)`.

#### I-M5 · WAV dataSize > 2 ГБ → NegativeArraySizeException в InferenceRunner
**Файл:** `InferenceRunner.kt:236–240` (WavReader)  
`readLeUint32` возвращает `Int` → при `dataSize > Int.MAX_VALUE` → `ByteArray(dataSize)` → `NegativeArraySizeException`.

#### I-M6 · Ownership нарушение: DI-синглтон perch передаётся в InferenceRunner как owned
**Файл:** `InferenceUseCase.kt:294–297`  
`buildList { add(perch); repeat(parallelism - 1) { add(perch.newInstance()) } }` — оригинальный `perch` из DI передаётся в `InferenceRunner`, который его закроет. Следующий запуск берёт тот же закрытый объект и снова вызывает `load()`. Работает, но нарушает ownership-контракт.  
**Исправление:** `repeat(parallelism) { add(perch.newInstance()) }`.

---

### UI

#### U-M1 · Архитектурный антипаттерн двойного ViewModel
**Файлы:** `HomeViewModel.kt`, `RecordingViewModel.kt`, `SettingsViewModel.kt`, `RadarViewModel.kt`  
Каждая VM имеет "тестируемый" класс + Hilt-обёртку. `SettingsScreen` обращается к `hilt.delegate` напрямую (нарушение инкапсуляции). Hilt поддерживает тестирование через `@TestInstallIn` / `SwappableModule` — двойной ViewModel не нужен.

#### U-M2 · RecordingViewModel через remember — не переживает конфиг-изменения
**Файл:** `RecordingScreen.kt:72–73`  
```kotlin
val hilt: RecordingViewModelHilt = hiltViewModel()
val vm = remember(hilt) { hilt.factory(perms) }
```
При повороте экрана `remember` сбрасывается, создаётся новый `RecordingViewModel`, `vm.start()` вызывается снова, `pendingPhotos` теряются.

#### U-M3 · N×4 Room Flow-подписок в LazyColumn
**Файлы:** `HomeScreen.kt:291–300`, `HomeViewModel.kt:152–176`  
Для каждого черновика в списке — 4 отдельных Room Flow (`topLabel`, `topSpecies`, `detectionCount`, `inatCount`). При 20 записях — 80 активных Room-курсоров.  
**Исправление:** единый `Flow<List<EnrichedDraftSummary>>` с JOIN или `combine` в ViewModel.

#### U-M4 · collectAsState() вместо collectAsStateWithLifecycle() в WaveformAndSpectrogram
**Файл:** `WaveformAndSpectrogram.kt:44`  
Flow продолжает собираться и пересоздавать composable при свёрнутом приложении (каждые 50 мс от MediaPlayer). Все остальные файлы используют `collectAsStateWithLifecycle()`.

#### U-M5 · 13 параллельных scope.launch в SettingsViewModel.init
**Файл:** `SettingsViewModel.kt:69–126`  
13 отдельных корутин собирают settings-флоу и вызывают `_state.value = _state.value.copy(...)` — 13 отдельных перекомпозиций + проблема атомарности (U-2).  
**Исправление:** объединить через `combine(...)`.

#### U-M6 · HorizontalPager без beyondViewportPageCount=1
**Файл:** `ReviewScreen.kt:133–155`  
По умолчанию кэш = 0 страниц. При свайпе Compose уничтожит соседнюю страницу → `onDispose` → `vm.release()` → `player.release()` во время анимации → аудио-артефакты или краш MediaPlayer.

#### U-M7 · Дублирование INAT_GREEN и функций форматирования
**Файлы:** `HomeScreen.kt:482`, `ReviewScreen.kt:911`, `SpeciesDetailsSheet.kt:315`
```kotlin
private val INAT_GREEN = Color(0xFF74AC00)  // x3 файла
```
Плюс 4 идентичные функции форматирования (`formatDuration`, `formatElapsed`, `formatMs`, `formatSheetMs`) — все делают `%d:%02d` из миллисекунд.  
**Исправление:** вынести в `Theme.kt` / `UiUtils.kt`.

---

### Data layer

#### D-M1 · Дублирование маппинга AggregatedDetection → DetectionEntity
**Файл:** `DraftRepository.kt`  
Идентичный блок маппинга (24 строки) скопирован в `createWithDetections` и `attachDetections`. При добавлении нового поля нужно менять оба места.  
**Исправление:** `private fun AggregatedDetection.toEntity(draftId: String): DetectionEntity`.

#### D-M2 · Orphaned поля в DraftEntity
**Файл:** `DraftEntity.kt:22–23`  
`inatObservationId: Long?` и `inatObservationUrl: String?` — остатки схемы до миграции v2→v3. Заменены таблицей `inat_observations`, но поля остались в entity, вводя в заблуждение.

#### D-M3 · delete() не логирует ошибки удаления файлов
**Файл:** `DraftRepository.kt:212–216`  
`File.delete()` внутри `WavFileStore.deleteAllFor` и `File.deleteRecursively()` в `PhotoFileStore.deletePhotosFor` игнорируют результат без логирования.

#### D-M4 · observeWithDetections: flowOn не покрывает mapNotNull (отозван)
*Пункт отозван после уточнения — flowOn применяется до mapNotNull в цепочке, всё корректно.*

---

### Audio / iNat

#### A-M1 · Recorder.stop() без job.join() — гонка pump/close
**Файл:** `Recorder.kt:147–155`  
`stop()` вызывает `job?.cancel()` без `job?.join()` перед `writer?.close()`. Pump-корутина может продолжить запись после `close()`, вызвав NPE на `out!!` в `writeBytes`.  
**Исправление:** добавить `job?.join()` после `job?.cancel()`.

#### A-M2 · INatSubmitter: taxonId = null при создании наблюдения
**Файл:** `INatSubmitter.kt:183`  
Наблюдение создаётся без `taxon_id`, идентификация добавляется только на уровне рода. Если пользователь выбрал конкретный вид — это снижает ценность данных для iNat-сообщества.

#### A-M3 · AndroidAudioRecordSource: MIC-fallback без проверки STATE_INITIALIZED
**Файл:** `AndroidAudioRecordSource.kt:75`  
Если MIC-fallback тоже не инициализировался, `record!!.startRecording()` → `IllegalStateException` без понятного сообщения об ошибке.

#### A-M4 · RegionalStatusRepository: check-then-act без блокировки
**Файл:** `RegionalStatusRepository.kt:51–53`  
Параллельные корутины могут одновременно промахнуться в кеш и сделать дублирующие сетевые запросы для одного и того же таксона.

#### A-M5 · INatAuthRepository: WebView не уничтожается после успешного refresh
**Файл:** `INatAuthRepository.kt:134–158`  
При успешном `onTokenCaptured` → `cont.resume(token)` без `webView.destroy()`. WebView уничтожится только через 15 секунд по таймауту.  
**Исправление:** вызывать `webView.destroy()` перед `cont.resume(token)`.

---

## MINOR

### Inference

#### I-m1 · Non-monotonic progress при параллельной записи в StateFlow
**Файл:** `InferenceRunner.kt:163,177`  
`_progress.value = counter.incrementAndGet().toFloat() / frames` — между `incrementAndGet()` и присвоением другой поток может увеличить счётчик → прогресс-бар может "дёрнуться" назад.

#### I-m2 · Дублирование логики aggregate/Accumulator в DetectionAggregator
**Файл:** `DetectionAggregator.kt`  
Batch-метод `aggregate()` и инкрементальный `Accumulator` вычисляют одни и те же поля. Комментарий "Mirrors exactly" не является контрактом, проверяемым компилятором.

#### I-m3 · Асимметричный диапазон Float→Short в PostRecordingProcessor
**Файл:** `PostRecordingProcessor.kt:35–39`  
`denoised[i] * Short.MAX_VALUE` — сигнал -1.0 даст -32767, не -32768. Диапазон несимметричен. Технически некорректно, хотя практический эффект минимален.

---

### UI

#### U-m1 · Хардкод FileProvider authority в 3 местах
**Файлы:** `RecordingViewModel.kt:109`, `ReviewViewModel.kt:530,549`  
`"com.sound2inat.app.fileprovider"` продублирован. При изменении `applicationId` нужно менять во всех местах.

#### U-m2 · LaunchedEffect(Unit) вместо LaunchedEffect(vm)
**Файл:** `RecordingScreen.kt:76`  
После исправления U-M2 это стало бы некорректным: `LaunchedEffect(Unit)` перезапустится при recomposition.

#### U-m3 · SimpleDateFormat создаётся при каждом вызове groupDraftsByDate
**Файл:** `HomeScreen.kt:508–509`  
`SimpleDateFormat` — тяжёлый объект, создаётся при каждом изменении списка. Заменить на `DateTimeFormatter` (доступен с API 26, minSdk=28).

#### U-m4 · Мёртвый код: uploadedIds не используется
**Файл:** `ReviewScreen.kt:181–182`  
`val uploadedIds = remember(state.inatObservations) { ... }` объявлен, но нигде не используется.

#### U-m5 · hilt.delegate нарушает инкапсуляцию
**Файлы:** `SettingsScreen.kt:53`, `RecordingScreen.kt:72–73`  
`val vm = hilt.delegate` — прямое обращение к приватной реализации.

---

### Data layer

#### D-m1 · ModelInstallState.Verifying(ready: Boolean) — параметр никогда не true
**Файл:** `ModelInstallState.kt:7`  
Всегда вызывается как `emit(ModelInstallState.Verifying(false))`. Параметр `ready` мёртв.  
**Исправление:** `data object Verifying` или убрать состояние.

#### D-m2 · MigrationTest: отсутствует chain-тест v1→v6
**Файл:** `MigrationTest.kt:161–203`  
Тест проверяет только v1→v5. Миграция v5→v6 тестируется отдельно, но полного chain-теста нет.

#### D-m3 · ModelManager.install использует Dispatchers.IO напрямую
**Файл:** `ModelManager.kt:41`  
```kotlin
open suspend fun install(...): Unit = withContext(Dispatchers.IO) {  // не ioDispatcher
```
Все остальные методы используют инжектированный `ioDispatcher` — непоследовательность, усложняет тестирование.

---

### Audio / iNat

#### A-m1 · @Inject-конструктор INatObservationsRepository жёстко привязан к System.currentTimeMillis
**Файл:** `INatObservationsRepository.kt:33–46`  
Класс принимает `clock: () -> Long` для тестируемости, но `@Inject`-конструктор обходит его, жёстко используя `now()`.

#### A-m2 · Symmetric Hann-окно вместо periodic для STFT
**Файл:** `Spectrogram.kt:27`  
`cos(2π·i / (N-1))` — symmetric Hann. Для STFT принято periodic: `cos(2π·i / N)`. Влияет только на UI-визуализацию (ML-модели используют собственные окна).

#### A-m3 · WavTrimmer: двойной in-RAM буфер
**Файл:** `WavTrimmer.kt:54–61`  
`ByteArray(sliceLen * 2)` + `ShortArray(sliceLen)` = 3× размер данных в RAM одновременно. Для коротких клипов (~5–10с) приемлемо (~3 МБ), но при расширении `PADDING_MS` до полной записи может достичь ~500+ МБ.

#### A-m4 · Непоследовательный URL-encoding в INaturalistClient
**Файл:** `INaturalistClient.kt:79,349`  
`appendRadarParams` использует `URLEncoder.encode` для taxa/quality_grade, но строит URL query конкатенацией для `q` (замена пробела на `+`, без обработки других символов). Гибридные виды с `×` могут сломать запрос.

#### A-m5 · FusedLocationProvider: lastLocation без таймаута
**Файл:** `FusedLocationProvider.kt:36–52`  
`withTimeoutOrNull(timeoutMs)` применяется только к первой попытке. Fallback на `client.lastLocation` выполняется без ограничения времени — если клиент завис, suspend-функция зависнет навсегда.

---

## Статус

- [ ] Проверить и приоритизировать
- [ ] CRITICAL исправить в первую очередь
- [ ] MAJOR — следующий спринт
- [ ] MINOR — по возможности
