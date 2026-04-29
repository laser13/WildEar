package com.sound2inat.modelmanager

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
) {
    private val dir: File = File(filesDir, "models").apply { mkdirs() }

    open fun stateFor(descriptor: ModelDescriptor): ModelInstallState {
        val m = modelFile(descriptor)
        val l = labelsFile(descriptor)
        val bothExist = m.exists() && l.exists()
        val shaMatch = bothExist && sha256(m) == descriptor.modelSha256 && sha256(l) == descriptor.labelsSha256
        return if (shaMatch) ModelInstallState.Ready(m, l) else ModelInstallState.NotInstalled
    }

    @Suppress("TooGenericExceptionCaught")
    open suspend fun install(
        descriptor: ModelDescriptor,
        emit: (ModelInstallState) -> Unit,
    ) = withContext(Dispatchers.IO) {
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
            modelTmp.renameTo(mFinal)
            labelsTmp.renameTo(lFinal)
            emit(ModelInstallState.Ready(mFinal, lFinal))
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
