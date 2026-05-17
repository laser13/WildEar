package com.sound2inat.inference

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs YamNet over a finished live-recording WAV in the background and returns
 * the aggregated [SceneTags]. The live recorder path bypasses [InferenceQueue]
 * (it persists detections inline from [LiveInferenceEngine]), so without this
 * helper such drafts would never get scene tags and the review screen's Auto
 * button would stay disabled forever.
 *
 * Fail-open: any error (model not installed, decode failure, etc.) returns
 * null and the caller proceeds without scene tags.
 */
@Singleton
class LiveSceneTagsAnalyzer @Inject constructor(
    private val yamNetGate: YamNetGate?,
) {

    suspend fun analyze(audioPath: String): SceneTags? = withContext(Dispatchers.Default) {
        val gate = yamNetGate ?: return@withContext null
        runCatching {
            val (shorts, sampleRate) = WavReader.readMono16(File(audioPath))
            val floats = FloatArray(shorts.size) { i ->
                shorts[i] / Short.MAX_VALUE.toFloat()
            }
            gate.classify(floats, sampleRate)?.sceneTags
        }.onFailure { e ->
            Log.w(TAG, "YamNet analysis failed for $audioPath", e)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "LiveSceneTagsAnalyzer"
    }
}
