package com.sound2inat.inference

import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.modelmanager.YamNetV1
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * YAMNet-based biological gate. Resamples each window to 16 kHz, splits into
 * 0.975 s frames, runs YAMNet TFLite, and returns a [YamNetGateResult] with:
 *   - [YamNetGateResult.biologicalScore]: max bio class probability across frames
 *   - [YamNetGateResult.backgroundScore]: max noise class probability across frames
 *   - [YamNetGateResult.recommendation]: PASS if bio score >= threshold or no
 *     frame has an obvious noise top-1 with low bio score; DOWNRANK otherwise
 *
 * Fail-open: any error or missing model returns null (callers treat null as PASS).
 */
class YamNetTfliteGate(
    private val factory: InterpreterFactory,
    private val modelManager: ModelManager,
) : YamNetGate {

    private val mutex = Mutex()
    private var interpreter: InterpreterApi? = null
    private var bioIndices: Set<Int> = emptySet()
    private var noiseIndices: Set<Int> = emptySet()
    private var birdIndices: Set<Int> = emptySet()
    private var owlIndices: Set<Int> = emptySet()
    private var frogIndices: Set<Int> = emptySet()
    private var insectIndices: Set<Int> = emptySet()
    private var mammalIndices: Set<Int> = emptySet()

    override suspend fun classify(pcmFloat32: FloatArray, sampleRateHz: Int): YamNetGateResult? =
        runCatching { classifyInternal(pcmFloat32, sampleRateHz) }.getOrNull()

    @Suppress("ReturnCount")
    private suspend fun classifyInternal(pcmFloat32: FloatArray, sampleRateHz: Int): YamNetGateResult? {
        ensureLoaded() // acquires+releases mutex internally for lazy load
        val resampled = resampleTo16k(pcmFloat32, sampleRateHz)
        return mutex.withLock {
            val interp = interpreter ?: return@withLock null
            var maxBioScore = 0f
            var maxNoiseScore = 0f
            var anyNoiseTopWithLowBio = false
            var maxBird = 0f
            var maxOwl = 0f
            var maxFrog = 0f
            var maxInsect = 0f
            var maxMammal = 0f
            var frameStart = 0
            while (frameStart < resampled.size) {
                val frame = FloatArray(YAMNET_FRAME_SIZE)
                val end = (frameStart + YAMNET_FRAME_SIZE).coerceAtMost(resampled.size)
                resampled.copyInto(frame, destinationOffset = 0, startIndex = frameStart, endIndex = end)
                val input = arrayOf(frame)
                val output = arrayOf(FloatArray(CLASS_COUNT))
                interp.run(input, output)
                val probs = output[0]
                val bioScore = bioIndices.maxOfOrNull { probs[it] } ?: 0f
                val noiseScore = noiseIndices.maxOfOrNull { probs[it] } ?: 0f
                val topClass = probs.indices.maxByOrNull { probs[it] } ?: 0
                if (bioScore > maxBioScore) maxBioScore = bioScore
                if (noiseScore > maxNoiseScore) maxNoiseScore = noiseScore
                if (topClass in noiseIndices && bioScore < BIO_THRESHOLD) anyNoiseTopWithLowBio = true
                val birdScore = birdIndices.maxOfOrNull { probs[it] } ?: 0f
                val owlScore = owlIndices.maxOfOrNull { probs[it] } ?: 0f
                val frogScore = frogIndices.maxOfOrNull { probs[it] } ?: 0f
                val insectScore = insectIndices.maxOfOrNull { probs[it] } ?: 0f
                val mammalScore = mammalIndices.maxOfOrNull { probs[it] } ?: 0f
                if (birdScore > maxBird) maxBird = birdScore
                if (owlScore > maxOwl) maxOwl = owlScore
                if (frogScore > maxFrog) maxFrog = frogScore
                if (insectScore > maxInsect) maxInsect = insectScore
                if (mammalScore > maxMammal) maxMammal = mammalScore
                frameStart += YAMNET_FRAME_SIZE
            }
            val passes = !(maxBioScore < BIO_THRESHOLD && anyNoiseTopWithLowBio)
            YamNetGateResult(
                biologicalScore = maxBioScore,
                backgroundScore = maxNoiseScore,
                recommendation = if (passes) GateRecommendation.PASS else GateRecommendation.DOWNRANK,
                sceneTags = SceneTags(maxBird, maxOwl, maxFrog, maxInsect, maxMammal),
            )
        }
    }

    private suspend fun ensureLoaded() = mutex.withLock {
        if (interpreter != null) return@withLock
        val state = modelManager.stateFor(YamNetV1.descriptor) as? ModelInstallState.Ready
            ?: return@withLock
        val classMap = parseClassMap(state.labelsFile)
        bioIndices = BIOLOGICAL_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
        noiseIndices = NOISE_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
        birdIndices = BIRD_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
        owlIndices = OWL_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
        frogIndices = FROG_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
        insectIndices = INSECT_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
        mammalIndices = MAMMAL_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
        interpreter = factory.create(state.modelFile, threads = 1)
    }

    private fun parseClassMap(file: File): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        file.bufferedReader().useLines { lines ->
            for (line in lines.drop(1)) { // skip header: index,mid,display_name
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

    override suspend fun close() {
        mutex.withLock {
            runCatching { interpreter?.close() }
            interpreter = null
        }
    }

    companion object {
        private const val YAMNET_SAMPLE_RATE = 16_000
        private const val YAMNET_FRAME_SIZE = 15_600 // 0.975 s at 16 kHz
        private const val CLASS_COUNT = 521
        private const val BIO_THRESHOLD = 0.15f

        // Display names from yamnet_class_map.csv — verify against downloaded CSV if gate misfires.
        private val BIRD_DISPLAY_NAMES = setOf(
            "Bird", "Bird vocalization, bird call, bird song", "Chirp, tweet",
            "Squawk", "Pigeon, dove", "Cooing", "Crow", "Caw",
            "Bird flight, flapping wings",
        )
        private val OWL_DISPLAY_NAMES = setOf("Owl", "Hoot")
        private val FROG_DISPLAY_NAMES = setOf("Frog", "Croak")
        private val INSECT_DISPLAY_NAMES = setOf("Insect", "Cricket", "Bee, wasp, etc.")
        private val MAMMAL_DISPLAY_NAMES = setOf(
            "Rodents, rats, mice", "Squeak",
            "Canidae, wild dogs, wolves", "Howl", "Bow-wow", "Bay",
            "Roar", "Caterwaul",
            "Bovine, mooing", "Whale vocalization",
        )

        private val BIOLOGICAL_DISPLAY_NAMES: Set<String> =
            setOf("Animal", "Wild animals", "Silence") +
                BIRD_DISPLAY_NAMES + OWL_DISPLAY_NAMES +
                FROG_DISPLAY_NAMES + INSECT_DISPLAY_NAMES +
                MAMMAL_DISPLAY_NAMES

        private val NOISE_DISPLAY_NAMES = setOf(
            "Speech", "Engine", "Motor vehicle (road)", "Car", "Motorcycle", "Truck",
            "Rail transport", "Boat, Water vehicle", "Aircraft",
            "Mechanical fan, air conditioning fan", "Wind", "Rain", "Thunder", "Music",
        )
    }
}
