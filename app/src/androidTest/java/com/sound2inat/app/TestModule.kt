package com.sound2inat.app

import com.sound2inat.app.di.SwappableModule
import com.sound2inat.inference.BioacousticModel
import com.sound2inat.inference.WindowPrediction
import com.sound2inat.inference.YamNetGate
import com.sound2inat.location.Fix
import com.sound2inat.location.LocationProvider
import com.sound2inat.modelmanager.BirdNetV24
import com.sound2inat.modelmanager.ModelDescriptor
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.recorder.AudioRecordSource
import com.sound2inat.recorder.Recorder
import com.sound2inat.recorder.RecordingResult
import com.sound2inat.recorder.WavWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * Replaces [SwappableModule] in instrumented tests so we can inject fakes
 * for the recorder, model, location and model-manager subsystems.
 *
 * Real Room/DataStore/files remain untouched — only the units that talk to
 * hardware/network or run heavy ML are stubbed.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SwappableModule::class])
object TestSwappableModule {

    @Provides @Singleton
    fun provideRecorder(): Recorder = FakeRecorder()

    @Provides @Singleton
    fun provideAudioSource(): AudioRecordSource = NoopAudioSource()

    @Provides @Singleton
    fun provideLocationProvider(): LocationProvider = FakeLocationProvider()

    @Provides @Singleton
    fun provideBioacousticModels(): List<BioacousticModel> = listOf(FakeBioacousticModel())

    @Provides @Singleton
    fun provideModelDescriptors(): List<ModelDescriptor> =
        // Mirror the fake model's id so ProductionInferenceJob can pair them
        // up; everything else (urls, sha) is irrelevant because FakeModelManager
        // overrides stateFor() to always report Ready.
        listOf(BirdNetV24.descriptor.copy(id = "fake_birdnet"))

    @Provides @Singleton
    fun provideModelManager(http: OkHttpClient): ModelManager =
        FakeModelManager(File("/dev/null"), http)

    /** Bypass YAMNet in e2e tests — InferenceRunner sees null and never calls the gate. */
    @Provides @Singleton
    fun provideYamNetGate(): YamNetGate? = null
}

/**
 * Fake recorder that synthesises a short mono 16-bit WAV at the requested target.
 * The audio is silence + tiny sine — enough for [InferenceRunner] to read the
 * header and slice windows without crashing.
 */
class FakeRecorder : Recorder {
    private val _rms = MutableStateFlow(0f)
    override val rmsLevel: StateFlow<Float> = _rms
    private val _rmsHistory = MutableStateFlow(FloatArray(0))
    override val rmsHistory: StateFlow<FloatArray> = _rmsHistory

    private var target: File? = null
    private var startMs: Long = 0L

    override suspend fun start(target: File) {
        this.target = target
        startMs = System.currentTimeMillis()
        _rms.value = FAKE_RMS
    }

    override suspend fun stop(): RecordingResult {
        val out = checkNotNull(target) { "FakeRecorder.stop without start" }
        writeSilenceWav(out, durationSeconds = WAV_DURATION_SECONDS)
        val durationMs = (System.currentTimeMillis() - startMs)
            .coerceAtLeast(WAV_DURATION_SECONDS * MS_PER_SECOND)
        _rms.value = 0f
        return RecordingResult(out.absolutePath, durationMs, SAMPLE_RATE, CHANNELS)
    }

    override fun cancel() {
        target?.delete()
        target = null
        _rms.value = 0f
    }

    private fun writeSilenceWav(file: File, durationSeconds: Long) {
        val writer = WavWriter(file, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE)
        writer.open()
        val totalSamples = (SAMPLE_RATE * durationSeconds).toInt()
        val chunk = ShortArray(SAMPLE_RATE) // 1-second chunks of silence
        var written = 0
        while (written < totalSamples) {
            val n = minOf(chunk.size, totalSamples - written)
            writer.writeShorts(chunk, 0, n)
            written += n
        }
        writer.close()
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val WAV_DURATION_SECONDS = 4L
        const val MS_PER_SECOND = 1000L
        const val FAKE_RMS = 0.25f
    }
}

/**
 * Audio source provided for completeness — DefaultRecorder is not used in tests
 * because [FakeRecorder] replaces it directly. We still bind it because Hilt
 * resolves the dependency graph even if no consumer remains.
 */
class NoopAudioSource : AudioRecordSource {
    override val sampleRate: Int = FakeRecorder.SAMPLE_RATE
    override val channels: Int = FakeRecorder.CHANNELS
    override val bitsPerSample: Int = FakeRecorder.BITS_PER_SAMPLE
    override suspend fun start() = Unit
    override suspend fun read(buf: ShortArray, off: Int, len: Int): Int = 0
    override suspend fun stop() = Unit
}

/** Always returns the same fix near London — keeps the Review screen's GPS line stable. */
class FakeLocationProvider : LocationProvider {
    override suspend fun getCurrent(timeoutMs: Long): Fix? = Fix(
        latitude = FIXED_LAT,
        longitude = FIXED_LON,
        accuracyMeters = FIXED_ACCURACY,
        timestampMs = System.currentTimeMillis(),
    )

    private companion object {
        const val FIXED_LAT = 51.5074
        const val FIXED_LON = -0.1278
        const val FIXED_ACCURACY = 10f
    }
}

/**
 * Returns two predetermined predictions per window so the Review screen renders
 * a stable, non-empty species list (Common Blackbird + Eurasian Robin).
 */
class FakeBioacousticModel : BioacousticModel {
    override val modelId: String = "fake_birdnet"
    override val modelVersion: String = "0.0-test"
    override val expectedSampleRateHz: Int = 48_000
    override val windowMs: Long = 3_000L

    override suspend fun load(modelFile: File, labelsFile: File) = Unit

    @Suppress("LongParameterList")
    override suspend fun predict(
        pcmFloat32: FloatArray,
        sampleRateHz: Int,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<WindowPrediction> = listOf(
        WindowPrediction(
            startMs = windowStartMs,
            endMs = windowEndMs,
            taxonScientificName = "Turdus merula",
            taxonCommonName = "Common Blackbird",
            confidence = BLACKBIRD_CONFIDENCE,
        ),
        WindowPrediction(
            startMs = windowStartMs,
            endMs = windowEndMs,
            taxonScientificName = "Erithacus rubecula",
            taxonCommonName = "Eurasian Robin",
            confidence = ROBIN_CONFIDENCE,
        ),
    )

    override fun close() = Unit

    private companion object {
        const val BLACKBIRD_CONFIDENCE = 0.92f
        const val ROBIN_CONFIDENCE = 0.71f
    }
}

/**
 * Always reports the BirdNET descriptor as Ready by pointing at empty stub
 * files in the test cache dir — the FakeBioacousticModel.load() ignores them.
 */
class FakeModelManager(filesDir: File, http: OkHttpClient) : ModelManager(filesDir, http) {
    private val stubModel: File = File.createTempFile("fake_model", ".tflite").apply { deleteOnExit() }
    private val stubLabels: File = File.createTempFile("fake_labels", ".txt").apply { deleteOnExit() }

    override suspend fun stateFor(descriptor: ModelDescriptor): ModelInstallState =
        ModelInstallState.Ready(stubModel, stubLabels)

    override suspend fun install(
        descriptor: ModelDescriptor,
        emit: (ModelInstallState) -> Unit,
    ) {
        emit(ModelInstallState.Ready(stubModel, stubLabels))
    }

    override fun remove(descriptor: ModelDescriptor) = Unit
}
