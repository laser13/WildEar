# Live Recording Screen — Design

**Date:** 2026-05-01
**Branch:** feat/live-recording (to be created)
**Author:** brainstormed with @simon

## Goal

Превратить экран записи из «таймер + RMS-полоска» в Merlin-style живой экран: скроллящаяся спектрограмма сверху + накопительный список карточек видов, которые BirdNET находит прямо во время записи.

**User-visible outcome:** юзер открывает запись, видит «горящую» спектрограмму, через несколько секунд первый вид всплывает в списке. К моменту нажатия Stop у него уже готовый список — не надо ждать пост-инференса.

## Non-goals

- Перерисовка [HomeScreen.kt](../../../app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt) — список записей трогаем только из-за изменения статуса draft (live → `PENDING_REVIEW`).
- Live для Perch v2 — слишком тяжёлый для real-time на телефоне. Perch остаётся как отдельная on-demand кнопка в Review.
- iNaturalist regional filter и BirdNET meta-model — применяем как есть (post-inference, в `ProductionInferenceJob`-эквиваленте). Live-pipeline этого не делает (нет post-processing).

## Decisions (locked)

| # | Решение |
|---|---|
| 1 | Перерабатываем [RecordingScreen.kt](../../../app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt) (не Home). |
| 2 | BirdNET-инференс крутится **во время записи**, не после Stop. |
| 3 | Основная зона экрана — только спектрограмма (без waveform-полоски). |
| 4 | Hop **1.5 с**, окно **3 с** (50% overlap). Темп BirdNET-инференса. |
| 5 | Карточки видов — накопительный список, бадж `×N` (число окон) + пиковая уверенность. Tap по карточке подсвечивает соответствующие окна на спектрограмме. |
| 6 | Threshold = текущий `Settings.minConfidenceDisplay` (по умолчанию 0.25). Без отдельной live-настройки. |
| 7 | Препроцессинг live = ровно тот же, что в `ProductionInferenceJob`: HPF → spectral subtraction → YamNet gate → BirdNET. |
| 8 | На Stop переходим в Review **без перезапуска инференса** — live-детекции и есть финальные. |
| 9 | Backpressure: очередь окон + warning в logcat если backlog > 3. |
| 10 | Play-кнопка на карточке вида в live-режиме **не нужна** — работает только в Review (как сейчас). |
| 11 | Длина видимой спектрограммы — последние **~10 секунд**. Старое уходит влево (но остаётся в WAV и в Review). |
| 12 | Live = только BirdNET. Perch — кнопка в Review (отдельная задача в этом же спринте). |

## Architecture

### Data flow

```
                 ┌─────────────────────┐
                 │  AudioRecordSource  │  (Android AudioRecord, 48 kHz mono short[])
                 └──────────┬──────────┘
                            │  ShortArray frames (~85ms blocks)
                            ▼
                 ┌─────────────────────┐
                 │  Recorder.pump()    │  существующий tap на WAV (без изменений)
                 │  ─────┬──────────── │
                 └───────┴─────────────┘
                         │ short→float convert
                         ▼ SharedFlow<FloatArray>      <- НОВЫЙ tap
              ┌──────────┴────────────┐
              │                       │
              ▼                       ▼
   ┌──────────────────┐    ┌──────────────────────┐
   │ LiveSpectrogram  │    │ LiveInferenceEngine  │
   │ (STFT → bitmap)  │    │ ring buffer 3 sec    │
   └────────┬─────────┘    │ hop 1.5 sec          │
            │              │ HPF → SS → YAMNet    │
            │              │   → BirdNET tflite    │
            │              └──────────┬───────────┘
            │                         │ Flow<List<WindowPrediction>>
            │                         ▼
            │              ┌──────────────────────┐
            │              │ DetectionAggregator  │ (incremental)
            │              │ Map<species, agg>    │
            │              └──────────┬───────────┘
            │                         │ Flow<List<AggregatedDetection>>
            ▼                         ▼
   ┌─────────────────────────────────────────────┐
   │  RecordingViewModel                         │
   │  spectrogramFrames + liveCards + elapsed    │
   └─────────────────────┬───────────────────────┘
                         ▼
   ┌─────────────────────────────────────────────┐
   │  RecordingScreen (Compose)                  │
   │  TopAppBar | Spectrogram | Cards | StopFAB  │
   └─────────────────────────────────────────────┘
```

### File structure

| Файл | Что делает | Действие |
|---|---|---|
| `app/src/main/java/com/sound2inat/recorder/Recorder.kt` | Запись WAV + RMS history. | **Modify**: добавить `audioBlocks: SharedFlow<FloatArray>`. Не ломать существующий путь. |
| `app/src/main/java/com/sound2inat/audio/Spectrogram.kt` | — | **New**: STFT + power-в-dB. Pure-Kotlin, тестируемый. |
| `app/src/main/java/com/sound2inat/audio/SpectrogramRingBuffer.kt` | — | **New**: кольцевой буфер dB-колонок ширины ~10 сек. |
| `app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt` | — | **New**: `AndroidView` с mutable `Bitmap` + drawing thread. Рисует ring buffer, скроллится по мере добавления. |
| `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt` | — | **New**: ring-buffer аудио + scheduler + worker coroutine. Использует существующие `AudioPreprocessor`, `Resampler`, `YamNetGate`, `BirdNetTfliteModel`. |
| `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt` | Группировка предсказаний по виду. | **Modify** (минимально): добавить incremental API `addWindow(pred): List<AggregatedDetection>` поверх существующего batch-метода. |
| `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt` | State экрана записи. | **Modify**: добавить `spectrogram`, `liveCards`, `liveBacklog` flows. На Stop — записать live-детекции в DB + перейти в Review со статусом `PENDING_REVIEW`. |
| `app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt` | UI экрана записи. | **Rewrite UI**: убрать `LiveWaveform`, добавить spectrogram + cards. |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt` | UI ревью. | **Modify**: кнопка «Analyze with Perch». |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` | Логика ревью. | **Modify**: метод `analyzeWithPerch()` запускает Perch-only inference на сохранённом WAV. |
| `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt` | Hilt graph. | **Modify**: provide `LiveInferenceEngine` factory (зависит от `BirdNetTfliteModel`, `YamNetGate`, `AudioPreprocessor`). |

### Key types

```kotlin
// audio/Spectrogram.kt
class Spectrogram(
    val fftSize: Int = 2048,
    val hopSize: Int = 512,
    val sampleRate: Int = 48_000,
) {
    /** Adds a block of samples; emits one dB column per [hopSize] samples consumed. */
    fun process(block: FloatArray): List<FloatArray>  // each FloatArray = fftSize/2+1 dB values
}

// inference/LiveInferenceEngine.kt
interface LiveInferenceEngine {
    val predictions: SharedFlow<WindowPrediction>
    val backlog: StateFlow<Int>
    fun start(scope: CoroutineScope)
    fun feed(block: FloatArray, recordingTimeMs: Long)
    fun stop()
}

// app/ui/recording/RecordingUiState.kt (existing — add fields)
sealed interface RecordingUiState {
    data class Recording(
        // … existing fields (elapsedMs, gps, warningSoftLimit) …
        val liveCards: List<LiveCard>,        // NEW
        val backlogWindows: Int,              // NEW (>0 means inference is behind)
    ) : RecordingUiState
}

data class LiveCard(
    val scientificName: String,
    val commonName: String?,
    val count: Int,
    val peakConfidence: Float,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)
```

### Stop flow

1. User taps Stop → `vm.stop()`.
2. `Recorder.stop()` закрывает WAV, возвращает `RecordingResult`.
3. `LiveInferenceEngine.stop()` — текущая очередь окон **дочисляется** (≤ ~3 сек ожидания, чтобы не потерять последние детекции). Если backlog большой, ждём до taймаута 5 сек, потом обрезаем.
4. `RecordingViewModel` собирает финальный список `WindowPrediction` из engine, конвертирует в `DetectionEntity` и пишет в Room через `DraftRepository`.
5. Draft создаётся со статусом **`PENDING_REVIEW`** (а не `PENDING_INFERENCE`).
6. UI переходит в Review.

`ProductionInferenceJob` для BirdNET в этом сценарии **не запускается**. Старый offline-путь остаётся в коде (на случай если live engine не доступен — fallback при отсутствии ready-модели или критической ошибке).

### Perch-on-demand

ReviewScreen получает кнопку:

```
[Analyze with Perch]    (если perch_v2 модель installed и Perch-детекций ещё нет)
```

Тап → отдельный inference job (`PerchOnlyInferenceJob` или флаг в существующем) на уже сохранённом WAV. Прогресс в виде небольшой полоски/спиннера. Результаты добавляются к текущим detections в DB, ReviewScreen реактивно обновляется через существующий flow.

### Concurrency / threading

- `Recorder.audioBlocks` эмитится из `pump()` (Dispatchers.IO). Buffer = `Channel.BUFFERED` или `MutableSharedFlow(extraBufferCapacity = 8)` с `BufferOverflow.DROP_OLDEST` (для spectrogram, чтобы UI не отставал).
- LiveInferenceEngine — отдельный single-thread `Dispatchers.Default` worker; внутренняя очередь окон с лимитом capacity = 8 (чтобы не выжрать память при долгом backlog).
- LiveSpectrogramView рисует на UI-потоке через `invalidate()`, но heavy STFT — на Default-диспетчере.

### Backpressure handling

```kotlin
// LiveInferenceEngine
private val queue = Channel<Window>(capacity = 8, BufferOverflow.DROP_OLDEST)

private suspend fun worker() {
    for (window in queue) {
        if (queue.size > 3) {
            Log.w(TAG, "Inference behind real time: ${queue.size} windows queued")
        }
        runWindow(window)
    }
}
```

Юзер видит индикатор `backlogWindows > 0` в UI — мелким текстом «Analysis catching up…» под спектрограммой. По достижении 8 окон новые дропаются (DROP_OLDEST), старые тоже потеряны — это намеренно, чтобы избежать out-of-memory.

### Memory budget

| Что | Размер |
|---|---|
| Spectrogram ring buffer (10 сек, hop 512, FFT 2048 → 1025 bins × 4 байта) | ~3.8 МБ |
| Bitmap для спектрограммы (940 × 256 px ARGB) | ~960 КБ |
| Live audio ring buffer (3 сек × 48000 × 4 байта) | ~580 КБ |
| LiveInferenceEngine queue (max 8 окон × 3 сек × 48k × 4) | ~4.6 МБ |
| **Итого** | **~10 МБ** |

Допустимо.

## Testing

### Unit tests

- `SpectrogramTest` — STFT на синусоиде → пик на ожидаемой частотной bin, dB-нормализация в разумных пределах.
- `SpectrogramRingBufferTest` — добавление колонок вытесняет старые при достижении capacity.
- `LiveInferenceEngineTest` — фейк-blocks → worker эмитит predictions с правильными timestamps; backlog корректно растёт; stop() drain'ит очередь до таймаута.
- `DetectionAggregator.addWindow` — incremental API даёт тот же результат, что batch на тех же входах.
- `RecordingViewModelTest` — на Stop draft создаётся со статусом `PENDING_REVIEW`, live-детекции сохранены в DB.
- `ReviewViewModelTest` — `analyzeWithPerch()` запускает Perch-only job, мерджит результаты с существующими detections, не дублирует.

### Manual / device tests

- Записать 30 сек на микрофон → проверить что:
  - Спектрограмма скроллится плавно (визуально без рывков).
  - Через ~3 сек появляется первая карточка вида (если пел реальная птица).
  - Tap по карточке подсвечивает окна на спектрограмме.
  - Stop → Review открывается мгновенно, детекции совпадают с live-списком.
  - Кнопка «Analyze with Perch» в Review запускает Perch и добавляет лягушек/насекомых.
- Записать 5 минут на слабом устройстве → проверить:
  - Backlog не растёт безостановочно (через DROP_OLDEST стабилизируется).
  - Память приложения не растёт линейно (профайлером).
- Прервать запись (back, lock screen) → live engine корректно стопается, не утекает coroutine.

## Open risks

1. **Bitmap-rendering performance** — на старых телефонах `setPixels` с большой колонкой может тормозить. План B: использовать `ImageBitmap` через Compose Canvas с pre-built `Bitmap` через `drawImage`. Мониторим FPS на dev-устройствах.
2. **Inference на Pixel 3a (наш baseline)** — BirdNET ~150-300ms за окно. При hop 1.5 сек это ~10-20% CPU duty. На более слабых железках может уходить в backpressure-режим. Acceptable per decision #9.
3. **Sample-rate mismatch** — Recorder может эмитить не 48 kHz (зависит от устройства). YamNet требует 16 kHz. Reuse существующего `Resampler` из inference/ — он уже работает в продакшене.
4. **Координаты GPS пока ещё не пришли** — BirdNET meta-фильтр работает только в post-inference. Live-результаты не фильтруются по региону. После Stop, при сохранении в DB, можно опционально применить regional filter ретроспективно. Решено: **не применяем** — live-детекции = что показали юзеру, чтобы UI не «терял» виды задним числом.

## Sub-projects → tasks (5 штук)

Распишутся в writing-plans, тут — список:

1. **Recorder audio tap** — добавить `audioBlocks: SharedFlow<FloatArray>` в `Recorder` без изменения WAV-write поведения. Тесты на эмиссию блоков.
2. **Live spectrogram pipeline** — `Spectrogram` + `SpectrogramRingBuffer` + `LiveSpectrogramView` (Compose-friendly). Тесты STFT и ring-buffer; manual test через standalone preview.
3. **LiveInferenceEngine** — full pipeline (HPF → SS → YamNet → BirdNET) на sliding window 3 сек / hop 1.5 сек. Reuse `AudioPreprocessor`, `Resampler`, `YamNetTfliteGate`, `BirdNetTfliteModel`. Тесты на правильные timestamps, backlog, drain on stop.
4. **RecordingScreen rebuild + Stop flow** — UI redesign (Merlin-style), `RecordingViewModel` подключает все три потока (spectrogram, live cards, backlog), на Stop сохраняет live-detections и переводит draft сразу в `PENDING_REVIEW`. ProductionInferenceJob становится опциональным.
5. **«Analyze with Perch» в Review** — `PerchOnlyInferenceJob` (или флаг в существующем job), кнопка в `ReviewScreen`, прогресс UI, мердж результатов в существующие detections.
