# Foreground Recording Service — Design

**Status:** Approved by user, ready for plan
**Date:** 2026-05-03
**Branch:** feat/live-recording

## Goal

Make audio recording and live BirdNET inference survive when the app is backgrounded, screen is locked, or another app takes focus. Field use case: user hits Record, leaves the phone in the field, walks away. Recording must keep running until the user explicitly stops it.

## Non-goals (deferred to follow-up specs)

- **Live regional / windows-count filter in notification.** Live aggregator currently filters only by `minConfidence`. Regional filter (uses async iNat taxon data) is a separate spec.
- **WAV rotation / chunking.** With hard limit removed, multi-hour recordings can produce multi-GB files. Rotation logic is a follow-up.
- **Crash recovery for unfinished WAV.** If the OS kills the process mid-recording, the WAV file is truncated and no draft is saved. Recovery is a follow-up.
- **Wake lock.** A foreground service with active `AudioRecord` already keeps the audio subsystem awake; we add `PARTIAL_WAKE_LOCK` only if field tests show CPU sleep interrupting the pump.
- **Background location.** We do one `getCurrent()` GPS fix at start, no periodic updates — `BACKGROUND_LOCATION` permission not needed.

## Architecture

### New components

```
MainActivity
  └─ RecordingScreen
       └─ RecordingViewModel  (UI state, permissions, navigation)
            ├─ RecordingController       (process-scoped state, owns Recorder + Engine)
            └─ RecordingServiceLauncher  (intents → Service)

RecordingService  ────── observes ──→ RecordingController.state
                                        ↑
                                        └── controls (start/stop/cancel)
```

#### `RecordingController` (`@Singleton` Hilt)

`app/src/main/java/com/sound2inat/app/recording/RecordingController.kt`

Owns: `Recorder`, `LiveInferenceEngine` (via factory), `LocationProvider`, `WavFileStore`, `DraftRepository`, `DetectionAggregator`.

State: `MutableStateFlow<RecordingSessionState>`:

```kotlin
sealed interface RecordingSessionState {
    data object Idle : RecordingSessionState
    data class Recording(
        val draftId: String,
        val recordingStartMs: Long,
        val elapsedMs: Long,
        val rms: Float,
        val gps: GpsStatus,
        val warningSoftLimit: Boolean,
        val backlogWindows: Int,
        val liveCards: List<LiveCard>,
        val lastDetection: LiveCard?,  // newest by lastSeenMs, or null
    ) : RecordingSessionState
    data class Done(val draftId: String) : RecordingSessionState
    data class Error(val message: String) : RecordingSessionState
}
```

Methods: `suspend fun start()`, `suspend fun stop()`, `fun cancel()`. Behaviour mirrors current `RecordingViewModel.start/stop/cancel`, but moved into singleton scope. Uses internal `CoroutineScope(SupervisorJob() + Dispatchers.IO)` so it survives ViewModel destruction.

`start()` is idempotent: if state is already `Recording`, returns immediately (no-op).

`SOFT_LIMIT_MS = 5min` and `HARD_LIMIT_MS` are removed. `warningSoftLimit` flag stays as informational signal at 5min mark, never auto-stops.

#### `RecordingService : Service`

`app/src/main/java/com/sound2inat/app/recording/RecordingService.kt`

Hilt-aware via `@AndroidEntryPoint`. Injects `RecordingController` and `RecordingNotificationBuilder`.

Intent actions:

| Action | Source | Behaviour |
|---|---|---|
| `ACTION_START` | `RecordingServiceLauncher.start()` | `startForeground(NOTIF_ID, initialNotification())`; `serviceScope.launch { controller.start() }`; subscribes to `controller.state.sample(1s)` to update notification or stop |
| `ACTION_STOP` | Notification "Stop" PendingIntent, `launcher.stop()` | `serviceScope.launch { controller.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }` |
| `ACTION_CANCEL` | `launcher.cancel()` (back button in UI) | `controller.cancel()` (sync); `stopForeground()`; `stopSelf()` |

`onStartCommand` returns `START_STICKY`. If process is restarted, no `ACTION_START` is delivered, so service stops itself shortly after — safe.

`onTaskRemoved` is NOT overridden — service keeps running when user swipes the app from recents.

Foreground service type: `microphone | location` (declared in manifest).

Service does NOT own Recorder/Engine — only orchestrates lifecycle and notifications.

#### `RecordingServiceLauncher` (`@Singleton`)

`app/src/main/java/com/sound2inat/app/recording/RecordingServiceLauncher.kt`

Thin wrapper around `Context` + `Intent`:

```kotlin
interface RecordingServiceLauncher {
    fun start(context: Context)
    fun stop(context: Context)
    fun cancel(context: Context)
}

class DefaultRecordingServiceLauncher @Inject constructor() : RecordingServiceLauncher {
    override fun start(ctx: Context) {
        val intent = Intent(ctx, RecordingService::class.java).setAction(ACTION_START)
        ContextCompat.startForegroundService(ctx, intent)
    }
    override fun stop(ctx: Context) { /* same with ACTION_STOP */ }
    override fun cancel(ctx: Context) { /* same with ACTION_CANCEL */ }
}
```

Exists for testability — `RecordingViewModel` injects this interface, never touches `Context`/`Intent` directly.

#### `RecordingNotificationBuilder`

`app/src/main/java/com/sound2inat/app/recording/RecordingNotificationBuilder.kt`

Builds the `Notification`. Splits content formatting into a pure function:

```kotlin
fun buildContentText(state: RecordingSessionState.Recording): String {
    val elapsed = formatElapsed(state.elapsedMs)
    val species = state.lastDetection?.let { it.commonName ?: it.scientificName }
    return if (species != null) "$elapsed · $species" else elapsed
}
```

Lets us unit-test formatting without Robolectric.

#### `RecordingViewModel` (refactored)

`app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt`

Constructor parameters reduced to: `PermissionsController`, `RecordingController`, `RecordingServiceLauncher`, `applicationContext: Context` (provided via Hilt `@ApplicationContext`).

`state: StateFlow<RecordingUiState>` — derived from `controller.state` via `map` (1-to-1 mapping to existing `RecordingUiState`). `RecordingUiState` interface is unchanged; UI does not need refactoring.

`fun start()`: requests permissions → on `RECORD_AUDIO` granted, calls `launcher.start(applicationContext)` → returns. Service handles the rest.

`fun stop()`: `launcher.stop(applicationContext)`.

`fun cancel()`: `launcher.cancel(applicationContext)`.

ViewModel no longer owns `Recorder`, `LiveInferenceEngine`, `DetectionAggregator`, or any record/inference jobs. All of those move into `RecordingController`.

### Removed/changed in `RecordingViewModel`

- All `private var ...Job: Job?` fields → moved to controller
- `tickLoop`, `startLiveInference`, `stopInternal` → moved to controller
- `SOFT_LIMIT_MS` constant kept (controller still emits `warningSoftLimit`); `HARD_LIMIT_MS` removed entirely
- `engineFactory`, `recorder`, `location`, `files`, `drafts`, `minConfidence`, `nowMs` parameters → moved to controller
- `RecordingViewModelHilt` simplified to inject controller + launcher

## Manifest changes

`app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Inside `<application>`:

```xml
<service
    android:name=".recording.RecordingService"
    android:exported="false"
    android:foregroundServiceType="microphone|location" />
```

## Permission flow

In `RecordingViewModel.start()`:

1. Request `RECORD_AUDIO` + `ACCESS_FINE_LOCATION` (existing).
2. Additionally request `POST_NOTIFICATIONS` on API 33+ (best-effort; do not block start if denied — system shows the foreground notification anyway, just without heads-up rights).
3. If `RECORD_AUDIO` granted → `launcher.start(context)`.

Permissions enum extended with `POST_NOTIFICATIONS` mapped to `Manifest.permission.POST_NOTIFICATIONS` and gated by `Build.VERSION.SDK_INT >= 33` in the runtime resolver. `minSdk = 28`, `targetSdk = 35` — no manifest version bumps needed.

## Notification

**Channel:** `recording_channel`, importance `IMPORTANCE_LOW`. Registered in `Sound2iNatApp.onCreate()` once per process.

**Notification properties:**
- `setOngoing(true)` — not swipeable
- `setOnlyAlertOnce(true)`
- `setSilent(true)` (API 29+)
- `setSmallIcon(R.drawable.ic_mic_recording)` — new vector drawable, 24dp white
- `setContentTitle("WildEar — recording")`
- `setContentText(builder.buildContentText(state))` — e.g. `"02:34 · Common Blackbird"`
- `setContentIntent(PendingIntent)` → `MainActivity` with extra to navigate to `RecordingScreen` after launch
- `addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)` → service `ACTION_STOP`

**Update throttling:** Service collects `controller.state.sample(1.seconds)` and rebuilds notification on each emission. Avoids ~10 updates/sec from RMS changes.

## Hilt wiring

`app/src/main/java/com/sound2inat/app/di/RecordingModule.kt` (new):

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RecordingModule {
    @Binds @Singleton
    abstract fun bindLauncher(impl: DefaultRecordingServiceLauncher): RecordingServiceLauncher
}
```

`RecordingController` itself is `@Singleton class @Inject constructor(...)` — no module needed.

`Recorder`, `LocationProvider`, `WavFileStore`, `DraftRepository`, `LiveInferenceEngineFactory?`, `Settings.minConfidenceDisplay` — all already provided by existing modules; controller injects them directly.

## Testing

### `RecordingControllerTest` (new, primary coverage)

`app/src/test/java/com/sound2inat/app/recording/RecordingControllerTest.kt`

Reuses fakes from existing `RecordingViewModelTest` (`FakeRecorder`, `FakeLocationProvider`, `FakeWavFileStore`, in-memory `DraftRepository`).

Test cases:
- `start sets state to Recording and assigns draftId`
- `stop saves draft with detections via DraftRepository when engine present`
- `stop saves draft without detections when engine factory returns null`
- `cancel deletes file and resets state to Idle`
- `rms updates flow into state.rms`
- `live predictions accumulate in state.liveCards`
- `lastDetection points at the newest aggregated detection by lastSeenMs`
- `start is idempotent — calling twice while Recording is no-op`
- `soft limit warning fires at 5 minutes but recording continues past it`

Dispatcher: `UnconfinedTestDispatcher` + `backgroundScope` (matches `ReviewViewModelTest` pattern).

### `RecordingViewModelTest` (rewritten, slimmer)

VM is now thin. Tests verify the routing only:
- `start requests RECORD_AUDIO + FINE_LOCATION + POST_NOTIFICATIONS and calls launcher.start when granted`
- `stop calls launcher.stop`
- `cancel calls launcher.cancel`
- `state mirrors controller.state`
- `start does not call launcher when RECORD_AUDIO denied`

Uses `FakeRecordingController` (in-memory `MutableStateFlow`) and `FakeRecordingServiceLauncher` (call counter). Tests that previously covered recording/inference logic — moved to `RecordingControllerTest`.

### `RecordingServiceTest` (Robolectric)

`app/src/test/java/com/sound2inat/app/recording/RecordingServiceTest.kt`

Uses `Robolectric.buildService(RecordingService::class.java)` + Hilt `@TestInstallIn` to swap `RecordingController` for fake.

Test cases:
- `onStartCommand ACTION_START calls startForeground and controller.start`
- `onStartCommand ACTION_STOP calls controller.stop and stopForeground`
- `onStartCommand ACTION_CANCEL calls controller.cancel and stopForeground`
- `state transition to Done triggers stopSelf`
- `state transition to Error triggers stopSelf`
- `notification updates throttled to once per second under burst of state changes`

### `RecordingNotificationBuilderTest` (pure unit)

Tests `buildContentText`:
- `returns elapsed only when no last detection`
- `returns elapsed + species common name when last detection has common name`
- `falls back to scientific name when common name is null`
- `formats elapsed as M:SS for under one hour`
- `formats elapsed as H:MM:SS for one hour and beyond` (verifies long-recording display)

### Out of scope for unit tests

- Live foreground service on device (manual / instrumented later)
- Battery / wake-lock behaviour (manual)
- OOM kill recovery (manual; deferred spec)

## Migration / backward compatibility

- DataStore schema unchanged
- Room schema unchanged
- `WavFileStore` usage unchanged
- Public `RecordingUiState` interface unchanged — UI does not need refactoring
- Existing instrumentation tests on `RecordingScreen` should still work (state shape preserved)

## Open risks

1. **Hilt + Service:** `@AndroidEntryPoint` on Service requires Hilt 2.42+ and `@HiltAndroidApp`. Both already in place (Hilt 2.52, `Sound2iNatApp` is `@HiltAndroidApp`). Should be smooth.
2. **`POST_NOTIFICATIONS` on API < 33:** runtime check guards the request; manifest-declared permission is fine on older APIs.
3. **`startForegroundService()` 5-second deadline:** must call `startForeground()` within 5s of service start. We do it in `onStartCommand` before any suspend call — well under deadline.
4. **State observer leak:** `serviceScope` (a `SupervisorJob` tied to Service lifetime) is cancelled in `onDestroy` — collectors stop when service stops.
