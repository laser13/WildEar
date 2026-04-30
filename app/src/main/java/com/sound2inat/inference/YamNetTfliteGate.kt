package com.sound2inat.inference

import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.modelmanager.YamNetV1
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * YAMNet-based biological gate. Resamples each window to 16 kHz, splits into
 * 0.975 s frames, runs YAMNet TFLite, and returns false only when the maximum
 * biological class score across all frames is < 0.15 AND at least one frame
 * has a known noise class as its top-1 prediction with bio score < 0.15.
 * Fail-open: any error or missing model returns true.
 */
class YamNetTfliteGate(
    private val factory: InterpreterFactory,
    private val modelManager: ModelManager,
) : YamNetGate {

    private val mutex = Mutex()
    private var interpreter: InterpreterApi? = null
    private var bioIndices: Set<Int> = emptySet()
    private var noiseIndices: Set<Int> = emptySet()

    override suspend fun isBiological(pcmFloat32: FloatArray, sampleRateHz: Int): Boolean =
        runCatching { isBiologicalInternal(pcmFloat32, sampleRateHz) }.getOrDefault(true)

    @Suppress("ReturnCount")
    private suspend fun isBiologicalInternal(pcmFloat32: FloatArray, sampleRateHz: Int): Boolean {
        ensureLoaded()
        val interp = interpreter ?: return true  // model not installed yet — fail open
        val resampled = resampleTo16k(pcmFloat32, sampleRateHz)

        var maxBioScore = 0f
        var anyNoiseTopWithLowBio = false
        var frameStart = 0
        while (frameStart < resampled.size) {
            val frame = FloatArray(YAMNET_FRAME_SIZE)  // zero-padded if last frame is short
            val end = (frameStart + YAMNET_FRAME_SIZE).coerceAtMost(resampled.size)
            resampled.copyInto(frame, destinationOffset = 0, startIndex = frameStart, endIndex = end)

            val input = arrayOf(frame)
            val output = arrayOf(FloatArray(CLASS_COUNT))
            interp.run(input, output)
            val probs = output[0]

            val bioScore = bioIndices.maxOfOrNull { probs[it] } ?: 0f
            val topClass = probs.indices.maxByOrNull { probs[it] } ?: 0
            if (bioScore > maxBioScore) maxBioScore = bioScore
            if (topClass in noiseIndices && bioScore < BIO_THRESHOLD) anyNoiseTopWithLowBio = true
            frameStart += YAMNET_FRAME_SIZE
        }
        return !(maxBioScore < BIO_THRESHOLD && anyNoiseTopWithLowBio)
    }

    private suspend fun ensureLoaded() = mutex.withLock {
        if (interpreter != null) return@withLock
        val state = modelManager.stateFor(YamNetV1.descriptor) as? ModelInstallState.Ready
            ?: return@withLock
        val classMap = parseClassMap(state.labelsFile)
        bioIndices = BIOLOGICAL_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
        noiseIndices = NOISE_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
        interpreter = factory.create(state.modelFile, threads = 1)
    }

    private fun parseClassMap(file: File): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        file.bufferedReader().useLines { lines ->
            for (line in lines.drop(1)) {  // skip header: index,mid,display_name
                val parts = line.split(",", limit = 3)
                if (parts.size == 3) {
                    val idx = parts[0].trim().toIntOrNull() ?: continue
                    result[parts[2].trim()] = idx
                }
            }
        }
        return result
    }

    private fun resampleTo16k(input: FloatArray, fromRate: Int): FloatArray {
        if (fromRate == YAMNET_SAMPLE_RATE || input.size < 2) return input
        val ratio = fromRate.toDouble() / YAMNET_SAMPLE_RATE
        val outLen = ((input.size - 1) / ratio).toInt() + 1
        return FloatArray(outLen) { i ->
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            input[i0] + (input[i1] - input[i0]) * frac
        }
    }

    companion object {
        private const val YAMNET_SAMPLE_RATE = 16_000
        private const val YAMNET_FRAME_SIZE = 15_600  // 0.975 s at 16 kHz
        private const val CLASS_COUNT = 521
        private const val BIO_THRESHOLD = 0.15f

        // Display names from yamnet_class_map.csv — verify against downloaded CSV if gate misfires.
        private val BIOLOGICAL_DISPLAY_NAMES = setOf(
            "Animal", "Wild animals",
            "Bird", "Bird vocalization, bird call, bird song", "Chirp, tweet", "Squawk",
            "Pigeon, dove", "Cooing", "Crow", "Caw", "Owl", "Hoot", "Bird flight, flapping wings",
            "Frog", "Croak",
            "Insect", "Cricket", "Bee, wasp, etc.",
            "Rodents, rats, mice",
            "Silence",
        )
        private val NOISE_DISPLAY_NAMES = setOf(
            "Speech", "Engine", "Motor vehicle (road)", "Car", "Motorcycle", "Truck",
            "Rail transport", "Boat, Water vehicle", "Aircraft",
            "Mechanical fan, air conditioning fan", "Wind", "Rain", "Thunder", "Music",
        )
    }
}
