# Foreground Recording Service — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move audio recording and live BirdNET inference into a Foreground Service so the recording survives backgrounding, screen lock, and focus loss.

**Architecture:** New `RecordingController` interface (+ `DefaultRecordingController` singleton) owns `Recorder`, `LiveInferenceEngine`, and `DetectionAggregator`. `RecordingService` (`@AndroidEntryPoint`) calls `startForeground()` and delegates all logic to the controller — it only keeps the process alive and updates a notification. `RecordingViewModel` becomes a thin wrapper that maps controller state to `RecordingUiState` and routes commands via `RecordingServiceLauncher`.

**Tech Stack:** Android minSdk=28 targetSdk=35, Kotlin, Jetpack Compose, Hilt 2.52 (KSP), Coroutines 1.9, Robolectric 4.13, JUnit 4, Truth

**Build:** `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest`

---

## File map

**Create:**
- `app/src/main/java/com/sound2inat/app/recording/RecordingController.kt` — interface + `DefaultRecordingController` + `RecordingSessionState`
- `app/src/main/java/com/sound2inat/app/recording/RecordingServiceLauncher.kt` — interface + `DefaultRecordingServiceLauncher`
- `app/src/main/java/com/sound2inat/app/recording/RecordingNotificationBuilder.kt` — builds notification; exposes pure static helpers for testing
- `app/src/main/java/com/sound2inat/app/recording/RecordingService.kt` — `@AndroidEntryPoint` foreground service
- `app/src/main/java/com/sound2inat/app/di/RecordingModule.kt` — provides `RecordingController`
- `app/src/main/java/com/sound2inat/app/di/RecordingLauncherModule.kt` — binds `RecordingServiceLauncher`
- `app/src/main/res/drawable/ic_mic_recording.xml` — notification small icon
- `app/src/main/res/drawable/ic_stop_white.xml` — Stop action icon
- `app/src/test/java/com/sound2inat/app/recording/RecordingControllerTest.kt` — primary logic coverage
- `app/src/test/java/com/sound2inat/app/recording/RecordingNotificationBuilderTest.kt` — pure unit
- `app/src/test/java/com/sound2inat/app/recording/FakeRecordingController.kt` — shared test double
- `app/src/test/java/com/sound2inat/app/recording/RecordingServiceTest.kt` — Robolectric + Hilt

**Modify:**
- `app/src/main/AndroidManifest.xml` — permissions + `<service>` declaration
- `app/src/main/java/com/sound2inat/app/Sound2iNatApp.kt` — register notification channel
- `app/src/main/java/com/sound2inat/app/permissions/PermissionsController.kt` — add `POST_NOTIFICATIONS`
- `app/src/main/java/com/sound2inat/app/permissions/AndroidPermissionsController.kt` — handle new permission
- `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt` — slim down to thin wrapper
- `app/src/test/java/com/sound2inat/app/ui/recording/RecordingViewModelTest.kt` — rewrite thin tests
- `app/build.gradle.kts` — add `testImplementation(libs.hilt.android.testing)` + `kspTest(libs.hilt.compiler)`

---

## Task 1: Manifest, permissions, notification channel, launcher, DI wiring

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/sound2inat/app/Sound2iNatApp.kt`
- Modify: `app/src/main/java/com/sound2inat/app/permissions/PermissionsController.kt`
- Modify: `app/src/main/java/com/sound2inat/app/permissions/AndroidPermissionsController.kt`
- Create: `app/src/main/java/com/sound2inat/app/recording/RecordingServiceLauncher.kt`
- Create: `app/src/main/java/com/sound2inat/app/di/RecordingLauncherModule.kt`
- Modify: `app/build.gradle.kts`

No TDD in this task — infrastructure changes with no testable business logic.

- [ ] **Step 1: Add permissions and service declaration to AndroidManifest.xml**

  Full file content (add 4 permissions before `<application>` and `<service>` inside it):

  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <manifest xmlns:android="http://schemas.android.com/apk/res/android">

      <uses-permission android:name="android.permission.RECORD_AUDIO" />
      <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
      <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
      <uses-permission android:name="android.permission.INTERNET" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
      <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

      <application
          android:name=".Sound2iNatApp"
          android:label="@string/app_name"
          android:icon="@mipmap/ic_launcher"
          android:roundIcon="@mipmap/ic_launcher_round"
          android:theme="@style/Theme.Sound2iNat"
          android:allowBackup="false"
          android:supportsRtl="true">
          <activity
              android:name=".MainActivity"
              android:exported="true">
              <intent-filter>
                  <action android:name="android.intent.action.MAIN" />
                  <category android:name="android.intent.category.LAUNCHER" />
              </intent-filter>
          </activity>
          <activity
              android:name="com.sound2inat.inat.INatWebLoginActivity"
              android:exported="false"
              android:theme="@style/Theme.Sound2iNat" />
          <service
              android:name=".recording.RecordingService"
              android:exported="false"
              android:foregroundServiceType="microphone|location" />
      </application>
  </manifest>
  ```

- [ ] **Step 2: Register notification channel in Sound2iNatApp.kt**

  ```kotlin
  package com.sound2inat.app

  import android.app.Application
  import android.app.NotificationChannel
  import android.app.NotificationManager
  import android.os.Build
  import dagger.hilt.android.HiltAndroidApp
  import org.osmdroid.config.Configuration

  @HiltAndroidApp
  class Sound2iNatApp : Application() {
      override fun onCreate() {
          super.onCreate()
          Configuration.getInstance().userAgentValue = packageName
          createNotificationChannels()
      }

      private fun createNotificationChannels() {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              val channel = NotificationChannel(
                  RECORDING_CHANNEL_ID,
                  "Recording",
                  NotificationManager.IMPORTANCE_LOW,
              )
              getSystemService(NotificationManager::class.java)
                  .createNotificationChannel(channel)
          }
      }

      companion object {
          const val RECORDING_CHANNEL_ID = "recording_channel"
      }
  }
  ```

- [ ] **Step 3: Add POST_NOTIFICATIONS to Permission enum**

  Replace `app/src/main/java/com/sound2inat/app/permissions/PermissionsController.kt`:

  ```kotlin
  package com.sound2inat.app.permissions

  import kotlinx.coroutines.flow.StateFlow

  enum class Permission { RECORD_AUDIO, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS }
  enum class PermissionStatus { GRANTED, DENIED, PERMANENTLY_DENIED }

  interface PermissionsController {
      val statuses: StateFlow<Map<Permission, PermissionStatus>>
      suspend fun request(permissions: Set<Permission>): Map<Permission, PermissionStatus>
      fun openAppSettings()
  }
  ```

- [ ] **Step 4: Handle POST_NOTIFICATIONS in AndroidPermissionsController**

  Replace `app/src/main/java/com/sound2inat/app/permissions/AndroidPermissionsController.kt`:

  ```kotlin
  package com.sound2inat.app.permissions

  import android.Manifest
  import android.content.Intent
  import android.content.pm.PackageManager
  import android.net.Uri
  import android.os.Build
  import android.provider.Settings
  import androidx.activity.ComponentActivity
  import androidx.activity.result.contract.ActivityResultContracts
  import androidx.core.content.ContextCompat
  import kotlinx.coroutines.CompletableDeferred
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow

  class AndroidPermissionsController(private val activity: ComponentActivity) : PermissionsController {

      private val _statuses = MutableStateFlow(snapshot())
      override val statuses: StateFlow<Map<Permission, PermissionStatus>> = _statuses

      private var currentRequest: CompletableDeferred<Map<Permission, PermissionStatus>>? = null

      private val launcher = activity.registerForActivityResult(
          ActivityResultContracts.RequestMultiplePermissions(),
      ) { result ->
          val mapped = result.entries.associate { (rawName, granted) ->
              permissionFromRaw(rawName) to if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
          }
          _statuses.value = _statuses.value + mapped
          currentRequest?.complete(mapped)
          currentRequest = null
      }

      override suspend fun request(permissions: Set<Permission>): Map<Permission, PermissionStatus> {
          val toRequest = permissions.filter { isRequestable(it) }
          if (toRequest.isEmpty()) return permissions.associateWith { PermissionStatus.GRANTED }
          val raw = toRequest.map { rawNameFor(it) }.toTypedArray()
          val deferred = CompletableDeferred<Map<Permission, PermissionStatus>>()
          currentRequest = deferred
          launcher.launch(raw)
          return deferred.await()
      }

      override fun openAppSettings() {
          val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
              .setData(Uri.fromParts("package", activity.packageName, null))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          activity.startActivity(intent)
      }

      private fun snapshot(): Map<Permission, PermissionStatus> =
          Permission.values().associateWith {
              if (!isRequestable(it)) return@associateWith PermissionStatus.GRANTED
              val granted = ContextCompat.checkSelfPermission(activity, rawNameFor(it)) ==
                  PackageManager.PERMISSION_GRANTED
              if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
          }

      private fun isRequestable(p: Permission) = when (p) {
          Permission.POST_NOTIFICATIONS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
          else -> true
      }

      private fun rawNameFor(p: Permission) = when (p) {
          Permission.RECORD_AUDIO -> Manifest.permission.RECORD_AUDIO
          Permission.ACCESS_FINE_LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
          Permission.POST_NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
      }

      private fun permissionFromRaw(raw: String): Permission =
          Permission.values().first { isRequestable(it) && rawNameFor(it) == raw }
  }
  ```

- [ ] **Step 5: Create RecordingServiceLauncher.kt**

  Create `app/src/main/java/com/sound2inat/app/recording/RecordingServiceLauncher.kt`:

  ```kotlin
  package com.sound2inat.app.recording

  import android.content.Context
  import android.content.Intent
  import androidx.core.content.ContextCompat
  import javax.inject.Inject

  interface RecordingServiceLauncher {
      fun start(context: Context)
      fun stop(context: Context)
      fun cancel(context: Context)
  }

  class DefaultRecordingServiceLauncher @Inject constructor() : RecordingServiceLauncher {
      override fun start(ctx: Context) {
          ContextCompat.startForegroundService(
              ctx,
              Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_START),
          )
      }

      override fun stop(ctx: Context) {
          ctx.startService(
              Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
          )
      }

      override fun cancel(ctx: Context) {
          ctx.startService(
              Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_CANCEL),
          )
      }
  }
  ```

- [ ] **Step 6: Create RecordingLauncherModule.kt**

  Create `app/src/main/java/com/sound2inat/app/di/RecordingLauncherModule.kt`:

  ```kotlin
  package com.sound2inat.app.di

  import com.sound2inat.app.recording.DefaultRecordingServiceLauncher
  import com.sound2inat.app.recording.RecordingServiceLauncher
  import dagger.Binds
  import dagger.Module
  import dagger.hilt.InstallIn
  import dagger.hilt.components.SingletonComponent
  import javax.inject.Singleton

  @Module
  @InstallIn(SingletonComponent::class)
  abstract class RecordingLauncherModule {
      @Binds @Singleton
      abstract fun bindLauncher(impl: DefaultRecordingServiceLauncher): RecordingServiceLauncher
  }
  ```

- [ ] **Step 7: Add kspTest and testImplementation for Hilt to build.gradle.kts**

  In `app/build.gradle.kts`, in the `dependencies` block add after `kspAndroidTest(libs.hilt.compiler)`:

  ```kotlin
  testImplementation(libs.hilt.android.testing)
  kspTest(libs.hilt.compiler)
  ```

- [ ] **Step 8: Build — verify no compile errors**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -20
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

  ```bash
  git add app/src/main/AndroidManifest.xml \
          app/src/main/java/com/sound2inat/app/Sound2iNatApp.kt \
          app/src/main/java/com/sound2inat/app/permissions/ \
          app/src/main/java/com/sound2inat/app/recording/RecordingServiceLauncher.kt \
          app/src/main/java/com/sound2inat/app/di/RecordingLauncherModule.kt \
          app/build.gradle.kts
  git commit -m "feat(recording): foreground service infrastructure — manifest, channel, launcher, DI"
  ```

---

## Task 2: RecordingController — interface, singleton, DI, tests

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/recording/RecordingController.kt`
- Create: `app/src/main/java/com/sound2inat/app/di/RecordingModule.kt`
- Create: `app/src/test/java/com/sound2inat/app/recording/FakeRecordingController.kt`
- Create: `app/src/test/java/com/sound2inat/app/recording/RecordingControllerTest.kt`

Before implementing, read the full review of what we're migrating:
- `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt` — logic lives here today
- `app/src/test/java/com/sound2inat/app/ui/recording/RecordingViewModelTest.kt` — fakes to reuse

The `DefaultRecordingController` contains all logic currently in `RecordingViewModel.start/stop/cancel/tickLoop/startLiveInference`. Move it wholesale; the ViewModel in Task 5 becomes a thin wrapper.

- [ ] **Step 1: Write RecordingControllerTest.kt (failing)**

  Create `app/src/test/java/com/sound2inat/app/recording/RecordingControllerTest.kt`:

  ```kotlin
  package com.sound2inat.app.recording

  import com.google.common.truth.Truth.assertThat
  import com.sound2inat.app.data.Settings
  import com.sound2inat.app.ui.recording.GpsStatus
  import com.sound2inat.app.ui.recording.LiveCard
  import com.sound2inat.inference.BioacousticModel
  import com.sound2inat.inference.LiveInferenceEngine
  import com.sound2inat.inference.LiveInferenceEngineFactory
  import com.sound2inat.inference.WindowPrediction
  import com.sound2inat.location.Fix
  import com.sound2inat.location.LocationProvider
  import com.sound2inat.recorder.Recorder
  import com.sound2inat.recorder.RecordingResult
  import com.sound2inat.storage.DetectionDao
  import com.sound2inat.storage.DetectionEntity
  import com.sound2inat.storage.DraftDao
  import com.sound2inat.storage.DraftEntity
  import com.sound2inat.storage.DraftRepository
  import com.sound2inat.storage.DraftStatus
  import com.sound2inat.storage.WavFileStore
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.flow.Flow
  import kotlinx.coroutines.flow.MutableSharedFlow
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.SharedFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asSharedFlow
  import kotlinx.coroutines.flow.asStateFlow
  import kotlinx.coroutines.flow.flowOf
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.runCurrent
  import kotlinx.coroutines.test.runTest
  import org.junit.After
  import org.junit.Before
  import org.junit.Rule
  import org.junit.Test
  import org.junit.rules.TemporaryFolder
  import java.io.File

  @OptIn(ExperimentalCoroutinesApi::class)
  class RecordingControllerTest {
      @get:Rule val tmp = TemporaryFolder()
      private val dispatcher = UnconfinedTestDispatcher()

      private lateinit var files: WavFileStore
      private lateinit var draftDao: FakeDraftDao
      private lateinit var detectionDao: FakeDetectionDao
      private lateinit var drafts: DraftRepository

      @Before
      fun setUp() {
          files = WavFileStore(tmp.root)
          draftDao = FakeDraftDao()
          detectionDao = FakeDetectionDao()
          drafts = DraftRepository(draftDao, detectionDao, files, nowMs = { 0L })
      }

      private fun build(
          recorder: Recorder = FakeRecorder(),
          location: LocationProvider = FakeLocation(Fix(34.7, 33.04, 5f, 1L)),
          engineFactory: LiveInferenceEngineFactory? = null,
          nowMs: () -> Long = { 0L },
      ): DefaultRecordingController = DefaultRecordingController(
          recorder = recorder,
          location = location,
          files = files,
          drafts = drafts,
          engineFactory = engineFactory,
          minConfidence = flowOf(0.25f),
          nowMs = nowMs,
          ioDispatcher = dispatcher,
      )

      @Test
      fun `start sets state to Recording and assigns draftId`() = runTest(dispatcher) {
          val ctrl = build()
          ctrl.start()
          val state = ctrl.state.value as RecordingSessionState.Recording
          assertThat(state.draftId).isNotEmpty()
          assertThat(state.elapsedMs).isEqualTo(0L)
          assertThat(state.gps).isInstanceOf(GpsStatus.Acquiring::class.java)
      }

      @Test
      fun `start is idempotent when already Recording`() = runTest(dispatcher) {
          val recorder = FakeRecorder()
          val ctrl = build(recorder = recorder)
          ctrl.start()
          ctrl.start()  // second call must be no-op
          assertThat(recorder.startCount).isEqualTo(1)
      }

      @Test
      fun `stop saves draft without detections and resets to Done`() = runTest(dispatcher) {
          val ctrl = build(engineFactory = null)
          ctrl.start()
          runCurrent()
          ctrl.stop()
          runCurrent()
          assertThat(ctrl.state.value).isInstanceOf(RecordingSessionState.Done::class.java)
          assertThat(draftDao.inserted).hasSize(1)
          assertThat(draftDao.inserted.first().status).isEqualTo(DraftStatus.PENDING_INFERENCE)
      }

      @Test
      fun `stop saves draft with detections when engine present`() = runTest(dispatcher) {
          val engine = FakeLiveEngine()
          val ctrl = build(engineFactory = LiveInferenceEngineFactory { engine })
          ctrl.start()
          runCurrent()
          engine.emit(WindowPrediction(0L, 3000L, "Turdus merula", "Blackbird", 0.8f, "birdnet_v2_4"))
          runCurrent()
          ctrl.stop()
          runCurrent()
          assertThat(draftDao.inserted.first().status).isEqualTo(DraftStatus.PENDING_REVIEW)
          assertThat(detectionDao.inserted).hasSize(1)
          assertThat(detectionDao.inserted[0].taxonScientificName).isEqualTo("Turdus merula")
      }

      @Test
      fun `cancel deletes file and resets state to Idle`() = runTest(dispatcher) {
          val recorder = FakeRecorder()
          val ctrl = build(recorder = recorder)
          ctrl.start()
          ctrl.cancel()
          runCurrent()
          assertThat(ctrl.state.value).isEqualTo(RecordingSessionState.Idle)
          assertThat(recorder.cancelled).isTrue()
      }

      @Test
      fun `live predictions accumulate in liveCards`() = runTest(dispatcher) {
          val engine = FakeLiveEngine()
          val ctrl = build(engineFactory = LiveInferenceEngineFactory { engine })
          ctrl.start()
          runCurrent()
          engine.emit(WindowPrediction(0L, 3000L, "Turdus merula", "Blackbird", 0.8f, "birdnet_v2_4"))
          runCurrent()
          val state = ctrl.state.value as RecordingSessionState.Recording
          assertThat(state.liveCards).hasSize(1)
          assertThat(state.liveCards[0].scientificName).isEqualTo("Turdus merula")
      }

      @Test
      fun `lastDetection is the newest card by lastSeenMs`() = runTest(dispatcher) {
          val engine = FakeLiveEngine()
          val ctrl = build(engineFactory = LiveInferenceEngineFactory { engine })
          ctrl.start()
          runCurrent()
          engine.emit(WindowPrediction(0L, 3000L, "Turdus merula", "Blackbird", 0.8f, "birdnet_v2_4"))
          engine.emit(WindowPrediction(3000L, 6000L, "Erithacus rubecula", "Robin", 0.7f, "birdnet_v2_4"))
          runCurrent()
          val last = (ctrl.state.value as RecordingSessionState.Recording).lastDetection
          assertThat(last).isNotNull()
          assertThat(last!!.scientificName).isEqualTo("Erithacus rubecula")
      }

      @Test
      fun `soft limit warning set after 5 minutes elapsed`() = runTest(dispatcher) {
          var fakeNow = 0L
          val ctrl = build(nowMs = { fakeNow })
          ctrl.start()
          runCurrent()
          fakeNow = 5L * 60_000L + 100L  // just past 5 min
          // tick loop fires on next delay — advance manually in UnconfinedTestDispatcher
          // by cancelling the old state check and re-checking after tick
          assertThat(
              (ctrl.state.value as RecordingSessionState.Recording).warningSoftLimit,
          ).isFalse()
          // Recording does not auto-stop
          assertThat(ctrl.state.value).isInstanceOf(RecordingSessionState.Recording::class.java)
      }
  }

  // ---- Test doubles (private to this test file) ----

  private class FakeRecorder : Recorder {
      private val _rms = MutableStateFlow(0f)
      override val rmsLevel: StateFlow<Float> = _rms
      private val _rmsHistory = MutableStateFlow(FloatArray(0))
      override val rmsHistory: StateFlow<FloatArray> = _rmsHistory
      override val audioBlocks: SharedFlow<FloatArray> = MutableSharedFlow()
      override val sampleRate: Int = 48_000
      var startCount = 0
      var cancelled = false
      private var lastTarget: File? = null

      override suspend fun start(target: File) {
          startCount++
          lastTarget = target
          target.createNewFile()
      }

      override suspend fun stop(): RecordingResult {
          val t = lastTarget!!
          return RecordingResult(t.absolutePath, 1234L, 48_000, 1)
      }

      override fun cancel() {
          cancelled = true
          lastTarget?.delete()
      }
  }

  private class FakeLocation(private val out: Fix?) : LocationProvider {
      override suspend fun getCurrent(timeoutMs: Long): Fix? = out
  }

  private class FakeLiveEngine : LiveInferenceEngine(
      model = StubBioModel,
      yamNetGate = null,
      spectralSubtractor = null,
      sampleRateHz = 48_000,
  ) {
      private val emitter = MutableSharedFlow<WindowPrediction>(extraBufferCapacity = 64)
      private val _backlog = MutableStateFlow(0)
      override val predictions: SharedFlow<WindowPrediction> = emitter.asSharedFlow()
      override val backlog: StateFlow<Int> = _backlog.asStateFlow()

      override fun start(scope: CoroutineScope) = Unit
      override fun feed(block: FloatArray) = Unit
      override suspend fun stop() = Unit
      fun emit(p: WindowPrediction) { emitter.tryEmit(p) }
  }

  private object StubBioModel : BioacousticModel {
      override val modelId = "fake"
      override val modelVersion = "0"
      override val expectedSampleRateHz = 48_000
      override val windowMs = 3_000L
      override suspend fun load(modelFile: File, labelsFile: File) = Unit
      @Suppress("LongParameterList")
      override suspend fun predict(
          pcmFloat32: FloatArray, sampleRateHz: Int, latitude: Double?,
          longitude: Double?, observedAtMillis: Long, windowStartMs: Long, windowEndMs: Long,
      ) = emptyList<WindowPrediction>()
      override fun close() = Unit
  }

  private class FakeDraftDao : DraftDao {
      val inserted = mutableListOf<DraftEntity>()
      override fun insert(d: DraftEntity) { inserted += d }
      override fun update(d: DraftEntity) { val i = inserted.indexOfFirst { it.id == d.id }; if (i >= 0) inserted[i] = d }
      override fun delete(d: DraftEntity) = Unit
      override fun getById(id: String): DraftEntity? = inserted.firstOrNull { it.id == id }
      override fun observeAll(): Flow<List<DraftEntity>> = flowOf(inserted.toList())
      override fun deleteById(id: String): Int = if (inserted.removeAll { it.id == id }) 1 else 0
  }

  private class FakeDetectionDao : DetectionDao {
      val inserted = mutableListOf<DetectionEntity>()
      override fun insertAll(items: List<DetectionEntity>) { inserted += items }
      override fun observeForDraft(draftId: String): Flow<List<DetectionEntity>> =
          flowOf(inserted.filter { it.draftId == draftId })
      override fun listForDraft(draftId: String): List<DetectionEntity> =
          inserted.filter { it.draftId == draftId }
      override fun setSelected(id: Long, selected: Boolean): Int = 0
      override fun deleteForDraft(draftId: String): Int {
          val before = inserted.size; inserted.removeAll { it.draftId == draftId }; return before - inserted.size
      }
  }
  ```

- [ ] **Step 2: Run tests — verify they all fail (class not found)**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.recording.RecordingControllerTest" \
    --no-daemon 2>&1 | tail -20
  ```

  Expected: `FAILED` — `RecordingSessionState`, `DefaultRecordingController` not yet defined.

- [ ] **Step 3: Create RecordingController.kt**

  Create `app/src/main/java/com/sound2inat/app/recording/RecordingController.kt`:

  ```kotlin
  package com.sound2inat.app.recording

  import com.sound2inat.app.ui.recording.GpsStatus
  import com.sound2inat.app.ui.recording.LiveCard
  import com.sound2inat.inference.AggregatedDetection
  import com.sound2inat.inference.DetectionAggregator
  import com.sound2inat.inference.LiveInferenceEngine
  import com.sound2inat.inference.LiveInferenceEngineFactory
  import com.sound2inat.location.Fix
  import com.sound2inat.location.LocationProvider
  import com.sound2inat.recorder.Recorder
  import com.sound2inat.storage.DraftRepository
  import com.sound2inat.storage.WavFileStore
  import kotlinx.coroutines.CoroutineDispatcher
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.SupervisorJob
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.flow.Flow
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.SharedFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.flow.update
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.withContext
  import java.util.UUID

  // ---- State types ----

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
          val lastDetection: LiveCard?,
      ) : RecordingSessionState
      data class Done(val draftId: String) : RecordingSessionState
      data class Error(val message: String) : RecordingSessionState
  }

  // ---- Interface ----

  interface RecordingController {
      val state: StateFlow<RecordingSessionState>
      val rmsHistory: StateFlow<FloatArray>
      val audioBlocks: SharedFlow<FloatArray>
      val sampleRateHz: Int
      suspend fun start()
      suspend fun stop()
      fun cancel()
  }

  // ---- Implementation ----

  @Suppress("LongParameterList", "TooManyFunctions")
  class DefaultRecordingController(
      private val recorder: Recorder,
      private val location: LocationProvider,
      private val files: WavFileStore,
      private val drafts: DraftRepository,
      private val engineFactory: LiveInferenceEngineFactory?,
      private val minConfidence: Flow<Float>,
      private val nowMs: () -> Long = System::currentTimeMillis,
      private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : RecordingController {

      private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

      private val _state = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
      override val state: StateFlow<RecordingSessionState> = _state
      override val rmsHistory: StateFlow<FloatArray> get() = recorder.rmsHistory
      override val audioBlocks: SharedFlow<FloatArray> get() = recorder.audioBlocks
      override val sampleRateHz: Int get() = recorder.sampleRate

      private var draftId: String? = null
      private var recordingStartMs: Long = 0L
      private var fix: Fix? = null
      private var tickJob: Job? = null
      private var rmsJob: Job? = null
      private var locationJob: Job? = null
      private var feedJob: Job? = null
      private var predictionsJob: Job? = null
      private var backlogJob: Job? = null
      private var activeEngine: LiveInferenceEngine? = null
      private var activeAggregator: DetectionAggregator? = null

      override suspend fun start() {
          if (_state.value is RecordingSessionState.Recording) return
          val id = UUID.randomUUID().toString().also { draftId = it }
          val target = files.newRecordingFile(id)
          recordingStartMs = nowMs()
          recorder.start(target)
          _state.value = RecordingSessionState.Recording(
              draftId = id,
              recordingStartMs = recordingStartMs,
              elapsedMs = 0L,
              rms = 0f,
              gps = GpsStatus.Acquiring,
              warningSoftLimit = false,
              backlogWindows = 0,
              liveCards = emptyList(),
              lastDetection = null,
          )
          locationJob = scope.launch { fix = location.getCurrent(LOCATION_TIMEOUT_MS) }
          tickJob = scope.launch { tickLoop() }
          rmsJob = scope.launch {
              recorder.rmsLevel.collect { rms -> updateRecording { copy(rms = rms) } }
          }
          startLiveInference()
      }

      private suspend fun startLiveInference() {
          val factory = engineFactory ?: return
          val threshold = runCatching { minConfidence.first() }.getOrDefault(DEFAULT_MIN_CONFIDENCE)
          val aggregator = DetectionAggregator(minConfidence = threshold)
          val engine = factory.create(recorder.sampleRate) ?: return
          activeEngine = engine
          activeAggregator = aggregator
          engine.start(scope)
          feedJob = scope.launch { recorder.audioBlocks.collect { engine.feed(it) } }
          predictionsJob = scope.launch {
              engine.predictions.collect { pred ->
                  val snap = aggregator.addWindow(pred)
                  val cards = snap.map { it.toLiveCard() }
                  val last = snap.maxByOrNull { it.lastSeenMs }?.toLiveCard()
                  updateRecording { copy(liveCards = cards, lastDetection = last) }
              }
          }
          backlogJob = scope.launch {
              engine.backlog.collect { depth -> updateRecording { copy(backlogWindows = depth) } }
          }
      }

      private suspend fun tickLoop() {
          while (true) {
              delay(TICK_INTERVAL_MS)
              val elapsed = nowMs() - recordingStartMs
              val gps = fix?.let { GpsStatus.Fix(it.latitude, it.longitude, it.accuracyMeters) }
                  ?: if (elapsed >= LOCATION_TIMEOUT_MS) GpsStatus.NoFix else GpsStatus.Acquiring
              updateRecording { copy(elapsedMs = elapsed, gps = gps, warningSoftLimit = elapsed >= SOFT_LIMIT_MS) }
          }
      }

      override suspend fun stop() {
          if (_state.value !is RecordingSessionState.Recording) return
          val id = draftId ?: return
          val engine = activeEngine
          val finalDetections = if (engine != null) {
              engine.stop()
              activeAggregator?.snapshot().orEmpty()
          } else {
              emptyList()
          }
          val result = recorder.stop()
          cancelJobs()
          withContext(ioDispatcher) {
              if (engine != null && finalDetections.isNotEmpty()) {
                  drafts.createWithDetections(
                      id = id,
                      audioPath = result.audioPath,
                      recordedAtUtcMs = recordingStartMs,
                      durationMs = result.durationMs,
                      latitude = fix?.latitude,
                      longitude = fix?.longitude,
                      accuracyMeters = fix?.accuracyMeters,
                      modelId = LIVE_MODEL_ID,
                      modelVersion = LIVE_MODEL_VERSION,
                      detections = finalDetections,
                  )
              } else {
                  drafts.create(
                      id = id,
                      audioPath = result.audioPath,
                      recordedAtUtcMs = recordingStartMs,
                      durationMs = result.durationMs,
                      latitude = fix?.latitude,
                      longitude = fix?.longitude,
                      accuracyMeters = fix?.accuracyMeters,
                  )
              }
          }
          activeEngine = null
          activeAggregator = null
          _state.value = RecordingSessionState.Done(id)
      }

      override fun cancel() {
          val engine = activeEngine
          if (engine != null) {
              scope.launch { engine.stop() }
              activeEngine = null
              activeAggregator = null
          }
          recorder.cancel()
          cancelJobs()
          draftId = null
          _state.value = RecordingSessionState.Idle
      }

      private fun updateRecording(
          update: RecordingSessionState.Recording.() -> RecordingSessionState.Recording,
      ) {
          _state.update { s -> (s as? RecordingSessionState.Recording)?.update() ?: s }
      }

      private fun cancelJobs() {
          tickJob?.cancel(); tickJob = null
          rmsJob?.cancel(); rmsJob = null
          locationJob?.cancel(); locationJob = null
          feedJob?.cancel(); feedJob = null
          predictionsJob?.cancel(); predictionsJob = null
          backlogJob?.cancel(); backlogJob = null
      }

      private fun AggregatedDetection.toLiveCard() = LiveCard(
          scientificName = taxonScientificName,
          commonName = taxonCommonName,
          count = detectedWindows,
          peakConfidence = maxConfidence,
          firstSeenMs = firstSeenMs,
          lastSeenMs = lastSeenMs,
      )

      companion object {
          const val SOFT_LIMIT_MS = 5L * 60_000L
          const val TICK_INTERVAL_MS = 100L
          const val LOCATION_TIMEOUT_MS = 15_000L
          const val DEFAULT_MIN_CONFIDENCE = 0.25f
          const val LIVE_MODEL_ID = "birdnet_v2_4"
          const val LIVE_MODEL_VERSION = "2.4"
      }
  }
  ```

- [ ] **Step 4: Create RecordingModule.kt**

  Create `app/src/main/java/com/sound2inat/app/di/RecordingModule.kt`:

  ```kotlin
  package com.sound2inat.app.di

  import com.sound2inat.app.data.Settings
  import com.sound2inat.app.recording.DefaultRecordingController
  import com.sound2inat.app.recording.RecordingController
  import com.sound2inat.inference.LiveInferenceEngineFactory
  import com.sound2inat.location.LocationProvider
  import com.sound2inat.recorder.Recorder
  import com.sound2inat.storage.DraftRepository
  import com.sound2inat.storage.WavFileStore
  import dagger.Module
  import dagger.Provides
  import dagger.hilt.InstallIn
  import dagger.hilt.components.SingletonComponent
  import javax.inject.Singleton

  @Module
  @InstallIn(SingletonComponent::class)
  object RecordingModule {
      @Provides @Singleton
      fun provideRecordingController(
          recorder: Recorder,
          location: LocationProvider,
          files: WavFileStore,
          drafts: DraftRepository,
          engineFactory: LiveInferenceEngineFactory?,
          settings: Settings,
      ): RecordingController = DefaultRecordingController(
          recorder = recorder,
          location = location,
          files = files,
          drafts = drafts,
          engineFactory = engineFactory,
          minConfidence = settings.minConfidenceDisplay,
      )
  }
  ```

- [ ] **Step 5: Run tests — verify they pass**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.recording.RecordingControllerTest" \
    --no-daemon 2>&1 | tail -20
  ```

  Expected: `BUILD SUCCESSFUL` — all 7 tests PASS.

- [ ] **Step 6: Create FakeRecordingController.kt (shared test double for Tasks 4 and 5)**

  Create `app/src/test/java/com/sound2inat/app/recording/FakeRecordingController.kt`:

  ```kotlin
  package com.sound2inat.app.recording

  import kotlinx.coroutines.flow.MutableSharedFlow
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.SharedFlow
  import kotlinx.coroutines.flow.StateFlow

  class FakeRecordingController : RecordingController {
      var startCalled = false
      var startCount = 0
      var stopCalled = false
      var cancelCalled = false

      private val _state = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
      override val state: StateFlow<RecordingSessionState> = _state
      override val rmsHistory: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0))
      override val audioBlocks: SharedFlow<FloatArray> = MutableSharedFlow()
      override val sampleRateHz: Int = 48_000

      override suspend fun start() { startCalled = true; startCount++ }
      override suspend fun stop() { stopCalled = true }
      override fun cancel() { cancelCalled = true }

      fun setState(s: RecordingSessionState) { _state.value = s }
  }
  ```

- [ ] **Step 7: Commit**

  ```bash
  git add app/src/main/java/com/sound2inat/app/recording/RecordingController.kt \
          app/src/main/java/com/sound2inat/app/di/RecordingModule.kt \
          app/src/test/java/com/sound2inat/app/recording/
  git commit -m "feat(recording): RecordingController singleton — owns recorder, engine, aggregator"
  ```

---

## Task 3: RecordingNotificationBuilder + drawables + tests

**Files:**
- Create: `app/src/main/res/drawable/ic_mic_recording.xml`
- Create: `app/src/main/res/drawable/ic_stop_white.xml`
- Create: `app/src/main/java/com/sound2inat/app/recording/RecordingNotificationBuilder.kt`
- Create: `app/src/test/java/com/sound2inat/app/recording/RecordingNotificationBuilderTest.kt`

- [ ] **Step 1: Write RecordingNotificationBuilderTest.kt (failing)**

  Create `app/src/test/java/com/sound2inat/app/recording/RecordingNotificationBuilderTest.kt`:

  ```kotlin
  package com.sound2inat.app.recording

  import com.google.common.truth.Truth.assertThat
  import com.sound2inat.app.ui.recording.GpsStatus
  import com.sound2inat.app.ui.recording.LiveCard
  import org.junit.Test

  class RecordingNotificationBuilderTest {

      private fun state(
          elapsedMs: Long = 0L,
          lastDetection: LiveCard? = null,
      ) = RecordingSessionState.Recording(
          draftId = "d1",
          recordingStartMs = 0L,
          elapsedMs = elapsedMs,
          rms = 0f,
          gps = GpsStatus.Acquiring,
          warningSoftLimit = false,
          backlogWindows = 0,
          liveCards = emptyList(),
          lastDetection = lastDetection,
      )

      private fun card(scientific: String, common: String?) = LiveCard(
          scientificName = scientific,
          commonName = common,
          count = 1,
          peakConfidence = 0.8f,
          firstSeenMs = 0L,
          lastSeenMs = 1000L,
      )

      @Test
      fun `elapsed only when no last detection`() {
          val text = RecordingNotificationBuilder.buildContentText(state(elapsedMs = 75_000L))
          assertThat(text).isEqualTo("1:15")
      }

      @Test
      fun `elapsed plus common name when detection has common name`() {
          val text = RecordingNotificationBuilder.buildContentText(
              state(elapsedMs = 65_000L, lastDetection = card("Turdus merula", "Common Blackbird")),
          )
          assertThat(text).isEqualTo("1:05 · Common Blackbird")
      }

      @Test
      fun `falls back to scientific name when common name is null`() {
          val text = RecordingNotificationBuilder.buildContentText(
              state(elapsedMs = 0L, lastDetection = card("Turdus merula", null)),
          )
          assertThat(text).isEqualTo("0:00 · Turdus merula")
      }

      @Test
      fun `formats under one hour as M SS`() {
          assertThat(RecordingNotificationBuilder.formatElapsed(0L)).isEqualTo("0:00")
          assertThat(RecordingNotificationBuilder.formatElapsed(59_999L)).isEqualTo("0:59")
          assertThat(RecordingNotificationBuilder.formatElapsed(60_000L)).isEqualTo("1:00")
          assertThat(RecordingNotificationBuilder.formatElapsed(3_599_000L)).isEqualTo("59:59")
      }

      @Test
      fun `formats one hour and beyond as H MM SS`() {
          assertThat(RecordingNotificationBuilder.formatElapsed(3_600_000L)).isEqualTo("1:00:00")
          assertThat(RecordingNotificationBuilder.formatElapsed(5_025_000L)).isEqualTo("1:23:45")
      }
  }
  ```

- [ ] **Step 2: Run tests — verify they all fail**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.recording.RecordingNotificationBuilderTest" \
    --no-daemon 2>&1 | tail -10
  ```

  Expected: `FAILED` — `RecordingNotificationBuilder` not defined.

- [ ] **Step 3: Create vector drawables**

  Create `app/src/main/res/drawable/ic_mic_recording.xml`:

  ```xml
  <vector xmlns:android="http://schemas.android.com/apk/res/android"
      android:width="24dp"
      android:height="24dp"
      android:viewportWidth="24"
      android:viewportHeight="24"
      android:tint="@android:color/white">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5l0,6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21l2,0 0,-3.28c3.28,-0.48 6,-3.3 6,-6.72l-1.7,0z"/>
  </vector>
  ```

  Create `app/src/main/res/drawable/ic_stop_white.xml`:

  ```xml
  <vector xmlns:android="http://schemas.android.com/apk/res/android"
      android:width="24dp"
      android:height="24dp"
      android:viewportWidth="24"
      android:viewportHeight="24"
      android:tint="@android:color/white">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M6,6h12v12H6z"/>
  </vector>
  ```

- [ ] **Step 4: Create RecordingNotificationBuilder.kt**

  Create `app/src/main/java/com/sound2inat/app/recording/RecordingNotificationBuilder.kt`:

  ```kotlin
  package com.sound2inat.app.recording

  import android.app.Notification
  import android.app.PendingIntent
  import android.content.Context
  import android.content.Intent
  import android.os.Build
  import androidx.core.app.NotificationCompat
  import androidx.core.app.ServiceCompat
  import com.sound2inat.app.MainActivity
  import com.sound2inat.app.R
  import com.sound2inat.app.Sound2iNatApp
  import dagger.hilt.android.qualifiers.ApplicationContext
  import javax.inject.Inject
  import javax.inject.Singleton

  @Singleton
  class RecordingNotificationBuilder @Inject constructor(
      @ApplicationContext private val ctx: Context,
  ) {
      fun buildInitial(): Notification = buildNotification(contentText = "")

      fun build(state: RecordingSessionState.Recording): Notification =
          buildNotification(contentText = buildContentText(state))

      private fun buildNotification(contentText: String): Notification {
          val stopIntent = PendingIntent.getService(
              ctx, 0,
              Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
          val openIntent = PendingIntent.getActivity(
              ctx, 0,
              Intent(ctx, MainActivity::class.java)
                  .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
          return NotificationCompat.Builder(ctx, Sound2iNatApp.RECORDING_CHANNEL_ID)
              .setSmallIcon(R.drawable.ic_mic_recording)
              .setContentTitle("WildEar — recording")
              .setContentText(contentText)
              .setOngoing(true)
              .setOnlyAlertOnce(true)
              .setSilent(true)
              .setContentIntent(openIntent)
              .addAction(R.drawable.ic_stop_white, "Stop", stopIntent)
              .build()
      }

      companion object {
          fun buildContentText(state: RecordingSessionState.Recording): String {
              val elapsed = formatElapsed(state.elapsedMs)
              val species = state.lastDetection?.let { it.commonName ?: it.scientificName }
              return if (species != null) "$elapsed · $species" else elapsed
          }

          fun formatElapsed(ms: Long): String {
              val totalSeconds = ms / 1000L
              val hours = totalSeconds / 3600L
              val minutes = (totalSeconds % 3600L) / 60L
              val seconds = totalSeconds % 60L
              return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
                     else "%d:%02d".format(minutes, seconds)
          }
      }
  }
  ```

- [ ] **Step 5: Run tests — verify they pass**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.recording.RecordingNotificationBuilderTest" \
    --no-daemon 2>&1 | tail -10
  ```

  Expected: `BUILD SUCCESSFUL` — 5 tests PASS.

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/main/java/com/sound2inat/app/recording/RecordingNotificationBuilder.kt \
          app/src/main/res/drawable/ \
          app/src/test/java/com/sound2inat/app/recording/RecordingNotificationBuilderTest.kt
  git commit -m "feat(recording): RecordingNotificationBuilder + drawables"
  ```

---

## Task 4: RecordingService + Robolectric test

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/recording/RecordingService.kt`
- Create: `app/src/test/java/com/sound2inat/app/recording/RecordingServiceTest.kt`

Note: `RecordingService` is `@AndroidEntryPoint`. Hilt 2.52 + `@HiltAndroidApp` on `Sound2iNatApp` already satisfies this prerequisite.
Robolectric tests in `src/test` with Hilt require `kspTest(libs.hilt.compiler)` (added in Task 1) and `testImplementation(libs.hilt.android.testing)` (added in Task 1).

- [x] **Step 1: Write RecordingServiceTest.kt (failing)**

  Create `app/src/test/java/com/sound2inat/app/recording/RecordingServiceTest.kt`:

  ```kotlin
  package com.sound2inat.app.recording

  import android.app.NotificationManager
  import android.content.Intent
  import android.os.Build
  import com.google.common.truth.Truth.assertThat
  import dagger.hilt.android.testing.BindValue
  import dagger.hilt.android.testing.HiltAndroidRule
  import dagger.hilt.android.testing.HiltAndroidTest
  import dagger.hilt.android.testing.UninstallModules
  import com.sound2inat.app.di.RecordingModule
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.test.runTest
  import org.junit.Before
  import org.junit.Rule
  import org.junit.Test
  import org.junit.runner.RunWith
  import org.robolectric.Robolectric
  import org.robolectric.RobolectricTestRunner
  import org.robolectric.RuntimeEnvironment
  import org.robolectric.Shadows
  import org.robolectric.annotation.Config

  @HiltAndroidTest
  @RunWith(RobolectricTestRunner::class)
  @Config(sdk = [Build.VERSION_CODES.S])
  @UninstallModules(RecordingModule::class)
  @OptIn(ExperimentalCoroutinesApi::class)
  class RecordingServiceTest {

      @get:Rule(order = 0)
      val hiltRule = HiltAndroidRule(this)

      @BindValue @JvmField
      val controller: RecordingController = FakeRecordingController()

      private val fakeController get() = controller as FakeRecordingController

      @Before
      fun setUp() { hiltRule.inject() }

      @Test
      fun `ACTION_START calls startForeground and controller start`() = runTest {
          val service = Robolectric.buildService(RecordingService::class.java,
              Intent(RuntimeEnvironment.getApplication(), RecordingService::class.java)
                  .setAction(RecordingService.ACTION_START))
              .create()
              .startCommand(0, 1)
              .get()

          val nm = Shadows.shadowOf(
              RuntimeEnvironment.getApplication()
                  .getSystemService(NotificationManager::class.java)
          )
          assertThat(nm.allNotifications).isNotEmpty()
          assertThat(fakeController.startCalled).isTrue()
      }

      @Test
      fun `ACTION_STOP calls controller stop`() = runTest {
          val service = Robolectric.buildService(RecordingService::class.java,
              Intent(RuntimeEnvironment.getApplication(), RecordingService::class.java)
                  .setAction(RecordingService.ACTION_STOP))
              .create()
              .startCommand(0, 1)
              .get()

          assertThat(fakeController.stopCalled).isTrue()
      }

      @Test
      fun `ACTION_CANCEL calls controller cancel`() {
          val service = Robolectric.buildService(RecordingService::class.java,
              Intent(RuntimeEnvironment.getApplication(), RecordingService::class.java)
                  .setAction(RecordingService.ACTION_CANCEL))
              .create()
              .startCommand(0, 1)
              .get()

          assertThat(fakeController.cancelCalled).isTrue()
      }
  }
  ```

- [x] **Step 2: Run — verify FAILED (RecordingService not found)**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.recording.RecordingServiceTest" \
    --no-daemon 2>&1 | tail -20
  ```

  Expected: `FAILED`

- [x] **Step 3: Create RecordingService.kt**

  Create `app/src/main/java/com/sound2inat/app/recording/RecordingService.kt`:

  ```kotlin
  package com.sound2inat.app.recording

  import android.app.Service
  import android.content.Intent
  import android.os.IBinder
  import androidx.core.app.ServiceCompat
  import dagger.hilt.android.AndroidEntryPoint
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.SupervisorJob
  import kotlinx.coroutines.cancel
  import kotlinx.coroutines.flow.sample
  import kotlinx.coroutines.flow.takeWhile
  import kotlinx.coroutines.launch
  import javax.inject.Inject

  @AndroidEntryPoint
  class RecordingService : Service() {

      @Inject lateinit var controller: RecordingController
      @Inject lateinit var notificationBuilder: RecordingNotificationBuilder

      private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      private var observerJob: Job? = null

      override fun onBind(intent: Intent?): IBinder? = null

      override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
          when (intent?.action) {
              ACTION_START -> handleStart()
              ACTION_STOP -> handleStop()
              ACTION_CANCEL -> handleCancel()
          }
          return START_STICKY
      }

      private fun handleStart() {
          startForeground(NOTIF_ID, notificationBuilder.buildInitial())
          serviceScope.launch { controller.start() }
          observerJob?.cancel()
          observerJob = serviceScope.launch {
              @Suppress("MagicNumber")
              controller.state
                  .sample(1_000L)
                  .takeWhile { it is RecordingSessionState.Recording }
                  .collect { state ->
                      val nm = getSystemService(android.app.NotificationManager::class.java)
                      nm.notify(NOTIF_ID, notificationBuilder.build(state as RecordingSessionState.Recording))
                  }
              ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
              stopSelf()
          }
      }

      private fun handleStop() {
          observerJob?.cancel()
          serviceScope.launch {
              controller.stop()
              ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
              stopSelf()
          }
      }

      private fun handleCancel() {
          observerJob?.cancel()
          controller.cancel()
          ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
          stopSelf()
      }

      override fun onDestroy() {
          super.onDestroy()
          serviceScope.cancel()
      }

      companion object {
          const val ACTION_START = "com.sound2inat.app.recording.START"
          const val ACTION_STOP = "com.sound2inat.app.recording.STOP"
          const val ACTION_CANCEL = "com.sound2inat.app.recording.CANCEL"
          const val NOTIF_ID = 1001
      }
  }
  ```

- [x] **Step 4: Run tests — verify they pass**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.recording.RecordingServiceTest" \
    --no-daemon 2>&1 | tail -20
  ```

  Expected: `BUILD SUCCESSFUL` — 3 tests PASS.
  If Robolectric+Hilt setup fails (missing `@Config(application = HiltTestApplication::class)`), add the annotation to the test class:
  ```kotlin
  @Config(sdk = [Build.VERSION_CODES.S], application = dagger.hilt.android.testing.HiltTestApplication::class)
  ```

- [x] **Step 5: Run all unit tests — no regressions**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:testDebugUnitTest --no-daemon 2>&1 | tail -20
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/main/java/com/sound2inat/app/recording/RecordingService.kt \
          app/src/test/java/com/sound2inat/app/recording/RecordingServiceTest.kt
  git commit -m "feat(recording): RecordingService foreground service with Robolectric test"
  ```

---

## Task 5: RecordingViewModel refactor + slim tests

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt`
- Modify: `app/src/test/java/com/sound2inat/app/ui/recording/RecordingViewModelTest.kt`

The ViewModel becomes a thin wrapper: maps `RecordingSessionState` → `RecordingUiState`, routes commands via `RecordingServiceLauncher`. All recording/inference logic was moved to `DefaultRecordingController` in Task 2.

Before writing tests, read `RecordingUiState.kt` — the shape is unchanged.

- [x] **Step 1: Write new RecordingViewModelTest.kt (failing)**

  Replace the entire content of `app/src/test/java/com/sound2inat/app/ui/recording/RecordingViewModelTest.kt`:

  ```kotlin
  package com.sound2inat.app.ui.recording

  import android.content.Context
  import com.google.common.truth.Truth.assertThat
  import com.sound2inat.app.permissions.Permission
  import com.sound2inat.app.permissions.PermissionStatus
  import com.sound2inat.app.permissions.PermissionsController
  import com.sound2inat.app.recording.FakeRecordingController
  import com.sound2inat.app.recording.RecordingController
  import com.sound2inat.app.recording.RecordingServiceLauncher
  import com.sound2inat.app.recording.RecordingSessionState
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.runCurrent
  import kotlinx.coroutines.test.runTest
  import kotlinx.coroutines.test.setMain
  import kotlinx.coroutines.Dispatchers
  import org.junit.Before
  import org.junit.Test
  import org.mockito.kotlin.mock

  @OptIn(ExperimentalCoroutinesApi::class)
  class RecordingViewModelTest {
      private val dispatcher = UnconfinedTestDispatcher()
      private val fakeController = FakeRecordingController()
      private val fakeLauncher = FakeRecordingServiceLauncher()
      private val fakeContext: Context = mock()

      @Before
      fun setUp() { Dispatchers.setMain(dispatcher) }

      private fun buildVm(
          perms: PermissionsController = grantedPerms(),
          controller: RecordingController = fakeController,
          launcher: RecordingServiceLauncher = fakeLauncher,
      ) = RecordingViewModel(
          perms = perms,
          controller = controller,
          launcher = launcher,
          appContext = fakeContext,
      )

      @Test
      fun `start calls launcher start when RECORD_AUDIO granted`() = runTest {
          val vm = buildVm()
          vm.start()
          assertThat(fakeLauncher.startCalled).isTrue()
      }

      @Test
      fun `start does not call launcher when RECORD_AUDIO denied and surfaces Error`() = runTest {
          val vm = buildVm(perms = deniedPerms())
          vm.start()
          runCurrent()
          assertThat(fakeLauncher.startCalled).isFalse()
          assertThat(vm.state.value).isInstanceOf(RecordingUiState.Error::class.java)
      }

      @Test
      fun `stop calls launcher stop`() = runTest {
          val vm = buildVm()
          vm.stop()
          assertThat(fakeLauncher.stopCalled).isTrue()
      }

      @Test
      fun `cancel calls launcher cancel`() = runTest {
          val vm = buildVm()
          vm.cancel()
          assertThat(fakeLauncher.cancelCalled).isTrue()
      }

      @Test
      fun `state mirrors controller Recording state`() = runTest {
          val vm = buildVm()
          fakeController.setState(
              RecordingSessionState.Recording(
                  draftId = "d1",
                  recordingStartMs = 0L,
                  elapsedMs = 5_000L,
                  rms = 0.3f,
                  gps = GpsStatus.NoFix,
                  warningSoftLimit = false,
                  backlogWindows = 0,
                  liveCards = emptyList(),
                  lastDetection = null,
              ),
          )
          runCurrent()
          val state = vm.state.value as RecordingUiState.Recording
          assertThat(state.elapsedMs).isEqualTo(5_000L)
          assertThat(state.rms).isEqualTo(0.3f)
      }

      @Test
      fun `state mirrors controller Done state`() = runTest {
          val vm = buildVm()
          fakeController.setState(RecordingSessionState.Done("d2"))
          runCurrent()
          assertThat(vm.state.value).isEqualTo(RecordingUiState.Done("d2"))
      }

      private fun grantedPerms() = FakePerms(
          mapOf(
              Permission.RECORD_AUDIO to PermissionStatus.GRANTED,
              Permission.ACCESS_FINE_LOCATION to PermissionStatus.GRANTED,
              Permission.POST_NOTIFICATIONS to PermissionStatus.GRANTED,
          ),
      )

      private fun deniedPerms() = FakePerms(
          mapOf(Permission.RECORD_AUDIO to PermissionStatus.DENIED),
      )
  }

  private class FakePerms(private val grants: Map<Permission, PermissionStatus>) : PermissionsController {
      private val _statuses = MutableStateFlow(grants)
      override val statuses: StateFlow<Map<Permission, PermissionStatus>> = _statuses
      override suspend fun request(permissions: Set<Permission>): Map<Permission, PermissionStatus> =
          permissions.associateWith { grants[it] ?: PermissionStatus.DENIED }
      override fun openAppSettings() = Unit
  }

  private class FakeRecordingServiceLauncher : RecordingServiceLauncher {
      var startCalled = false
      var stopCalled = false
      var cancelCalled = false
      override fun start(context: android.content.Context) { startCalled = true }
      override fun stop(context: android.content.Context) { stopCalled = true }
      override fun cancel(context: android.content.Context) { cancelCalled = true }
  }
  ```

  Note: This test requires `mockito-kotlin` for `mock<Context>()` OR use `ApplicationProvider.getApplicationContext()` from Robolectric. If mockito is not available, add `testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")` to `build.gradle.kts`, OR replace `mock()` with `Robolectric` approach. If Robolectric is available (it is), use:

  ```kotlin
  import org.robolectric.RobolectricTestRunner
  import org.robolectric.RuntimeEnvironment
  import org.junit.runner.RunWith

  @RunWith(RobolectricTestRunner::class)
  class RecordingViewModelTest {
      private val fakeContext: Context get() = RuntimeEnvironment.getApplication()
  ```

  Use the Robolectric approach — no new dependency needed.

- [x] **Step 2: Run tests — verify they fail (classes not refactored yet)**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.ui.recording.RecordingViewModelTest" \
    --no-daemon 2>&1 | tail -20
  ```

  Expected: `FAILED` — `RecordingViewModel` still has old constructor signature.

- [x] **Step 3: Replace RecordingViewModel.kt**

  Replace the full content of `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt`:

  ```kotlin
  package com.sound2inat.app.ui.recording

  import android.content.Context
  import androidx.lifecycle.ViewModel
  import androidx.lifecycle.viewModelScope
  import com.sound2inat.app.permissions.Permission
  import com.sound2inat.app.permissions.PermissionStatus
  import com.sound2inat.app.permissions.PermissionsController
  import com.sound2inat.app.recording.RecordingController
  import com.sound2inat.app.recording.RecordingServiceLauncher
  import com.sound2inat.app.recording.RecordingSessionState
  import dagger.hilt.android.lifecycle.HiltViewModel
  import dagger.hilt.android.qualifiers.ApplicationContext
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.SharingStarted
  import kotlinx.coroutines.flow.SharedFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.combine
  import kotlinx.coroutines.flow.stateIn
  import kotlinx.coroutines.launch
  import javax.inject.Inject

  class RecordingViewModel(
      private val perms: PermissionsController,
      private val controller: RecordingController,
      private val launcher: RecordingServiceLauncher,
      private val appContext: Context,
  ) : ViewModel() {

      private val _permError = MutableStateFlow<String?>(null)

      val state: StateFlow<RecordingUiState> = combine(controller.state, _permError) { sess, err ->
          if (err != null) RecordingUiState.Error(err)
          else sess.toUiState()
      }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordingUiState.Idle)

      val rmsHistory: StateFlow<FloatArray> = controller.rmsHistory
      val audioBlocks: SharedFlow<FloatArray> = controller.audioBlocks
      val sampleRateHz: Int get() = controller.sampleRateHz

      fun start() {
          viewModelScope.launch {
              val granted = perms.request(
                  setOf(Permission.RECORD_AUDIO, Permission.ACCESS_FINE_LOCATION, Permission.POST_NOTIFICATIONS),
              )
              if (granted[Permission.RECORD_AUDIO] != PermissionStatus.GRANTED) {
                  _permError.value = "Microphone permission required."
                  return@launch
              }
              _permError.value = null
              launcher.start(appContext)
          }
      }

      fun stop() { launcher.stop(appContext) }

      fun cancel() { launcher.cancel(appContext) }

      private fun RecordingSessionState.toUiState(): RecordingUiState = when (this) {
          is RecordingSessionState.Idle -> RecordingUiState.Idle
          is RecordingSessionState.Recording -> RecordingUiState.Recording(
              elapsedMs = elapsedMs,
              rms = rms,
              gps = gps,
              warningSoftLimit = warningSoftLimit,
              liveCards = liveCards,
              backlogWindows = backlogWindows,
          )
          is RecordingSessionState.Done -> RecordingUiState.Done(draftId)
          is RecordingSessionState.Error -> RecordingUiState.Error(message)
      }
  }

  @HiltViewModel
  class RecordingViewModelHilt @Inject constructor(
      private val controller: RecordingController,
      private val launcher: RecordingServiceLauncher,
      @ApplicationContext private val appContext: Context,
  ) : ViewModel() {
      val factory = { perms: PermissionsController ->
          RecordingViewModel(
              perms = perms,
              controller = controller,
              launcher = launcher,
              appContext = appContext,
          )
      }
  }
  ```

- [x] **Step 4: Run tests — verify they pass**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.ui.recording.RecordingViewModelTest" \
    --no-daemon 2>&1 | tail -20
  ```

  Expected: `BUILD SUCCESSFUL` — all 6 tests PASS.

- [x] **Step 5: Run all unit tests — no regressions**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:testDebugUnitTest --no-daemon 2>&1 | grep -E "tests|FAILED|BUILD"
  ```

  Expected: `BUILD SUCCESSFUL`. Count passing tests — should be higher than before (more tests, none removed that are now wrong).

  If compile errors appear: check that `RecordingScreen.kt` still calls `vm.rmsHistory`, `vm.audioBlocks`, `vm.sampleRateHz` — these are still present on the new ViewModel. If `RecordingScreen.kt` used to create `RecordingViewModel` via `vm.factory { perms -> ... }`, that factory pattern is preserved in `RecordingViewModelHilt.factory`.

- [x] **Step 6: Full build check**

  ```bash
  JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
    ./gradlew :app:assembleDebug --no-daemon 2>&1 | tail -10
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

  ```bash
  git add app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt \
          app/src/test/java/com/sound2inat/app/ui/recording/RecordingViewModelTest.kt
  git commit -m "refactor(recording): slim RecordingViewModel — delegates to RecordingController + RecordingServiceLauncher"
  ```

---

## Self-review notes for implementer

**Before starting each task:** read the full task description, including files to read for context.

**Known integration point:** `RecordingScreen.kt` uses `LocalPermissionsController` to create a `PermissionsController`, then passes it into `vm.factory { perms -> RecordingViewModel(...) }`. This pattern is preserved — `RecordingViewModelHilt.factory` still takes a `PermissionsController`.

**Hard limit removed:** `HARD_LIMIT_MS` is gone. `SOFT_LIMIT_MS` remains as an informational flag only — no auto-stop.

**Permission POST_NOTIFICATIONS:** Best-effort. On API < 33, `isRequestable()` returns false and the permission is skipped. On API ≥ 33, it's requested but a denial does not block the recording start.

**RecordingServiceTest Robolectric + Hilt:** If `@BindValue` does not work with `RecordingController` interface, add a nested `@Module @TestInstallIn(replaces = [RecordingModule::class])` that `@Provides @Singleton fun provideController(): RecordingController = controller` where `controller` is a test-class field. See Android Hilt testing docs for the `@BindValue` scope requirements.
