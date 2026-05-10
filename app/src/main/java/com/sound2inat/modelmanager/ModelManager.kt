package com.sound2inat.modelmanager

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

open class ModelManager(
    private val filesDir: File,
    private val http: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hiddenDescriptors: List<ModelDescriptor> = emptyList(),
) {
    private val dir: File = File(filesDir, "models").apply { mkdirs() }

    /**
     * Reports whether the model is on disk and SHA-256-verified. SHA-256 over the
     * 50 MB model file takes ~1 s; this method is `suspend` so callers always
     * dispatch off Main.
     */
    open suspend fun stateFor(descriptor: ModelDescriptor): ModelInstallState =
        withContext(ioDispatcher) {
            val m = modelFile(descriptor)
            val l = labelsFile(descriptor)
            val bothExist = m.exists() && l.exists()
            val shaMatch = bothExist &&
                sha256(m) == descriptor.modelSha256 &&
                sha256(l) == descriptor.labelsSha256
            if (shaMatch) ModelInstallState.Ready(m, l) else ModelInstallState.NotInstalled
        }

    @Suppress("TooGenericExceptionCaught")
    open suspend fun install(
        descriptor: ModelDescriptor,
        emit: (ModelInstallState) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        try {
            emit(ModelInstallState.Downloading(0f))
            val modelTmp = downloadTo(
                descriptor.modelUrl,
                partialFor(descriptor.id, "tflite"),
            ) { p ->
                emit(ModelInstallState.Downloading(p * HALF))
            }
            val labelsTmp = downloadTo(
                descriptor.labelsUrl,
                partialFor(descriptor.id, "labels.txt"),
            ) { p ->
                emit(ModelInstallState.Downloading(HALF + p * HALF))
            }
            emit(ModelInstallState.Verifying(false))
            require(sha256(modelTmp) == descriptor.modelSha256) { "Model SHA-256 mismatch" }
            require(sha256(labelsTmp) == descriptor.labelsSha256) { "Labels SHA-256 mismatch" }
            val mFinal = modelFile(descriptor)
            val lFinal = labelsFile(descriptor)
            check(modelTmp.renameTo(mFinal)) {
                "Failed to rename model file: ${modelTmp.name} → ${mFinal.name}"
            }
            check(labelsTmp.renameTo(lFinal)) {
                "Failed to rename labels file: ${labelsTmp.name} → ${lFinal.name}"
            }
            emit(ModelInstallState.Ready(mFinal, lFinal))
            // Auto-install hidden companion models (e.g. YAMNet) once a visible model lands.
            if (!descriptor.hidden) {
                for (hidden in hiddenDescriptors) {
                    if (stateFor(hidden) !is ModelInstallState.Ready) {
                        install(hidden) { /* progress silently ignored */ }
                    }
                }
            }
        } catch (t: Throwable) {
            partialFor(descriptor.id, "tflite").delete()
            partialFor(descriptor.id, "labels.txt").delete()
            modelFile(descriptor).delete()
            labelsFile(descriptor).delete()
            emit(ModelInstallState.Failed(t.message ?: t::class.simpleName.orEmpty()))
        }
    }

    open fun remove(descriptor: ModelDescriptor) {
        modelFile(descriptor).delete()
        labelsFile(descriptor).delete()
    }

    private fun modelFile(d: ModelDescriptor) = File(dir, "${d.id}.tflite")
    private fun labelsFile(d: ModelDescriptor) = File(dir, "${d.id}.labels.txt")
    private fun partialFor(id: String, ext: String) = File(dir, "$id.$ext.partial")

    private fun downloadTo(url: String, target: File, onProgress: (Float) -> Unit): File {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            val body = resp.body!!
            val total = body.contentLength().coerceAtLeast(1L)
            FileOutputStream(target).use { out ->
                pumpStream(body.byteStream(), out, total, onProgress)
            }
        }
        return target
    }

    private fun pumpStream(
        src: InputStream,
        out: FileOutputStream,
        total: Long,
        onProgress: (Float) -> Unit,
    ) {
        val buf = ByteArray(BUF_SIZE)
        var read = 0L
        while (true) {
            val n = src.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            read += n
            onProgress((read.toFloat() / total).coerceIn(0f, 1f))
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUF_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BUF_SIZE = 64 * 1024
        private const val HALF = 0.5f
    }
}
