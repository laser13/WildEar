package com.sound2inat.inference

import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Test seam for constructing TFLite [InterpreterApi] instances. The production
 * implementation memory-maps the model file via [MappedByteBuffer] and wraps
 * `org.tensorflow.lite.Interpreter`; tests substitute a fake.
 *
 * Pass [allowDelegate]=false for models that cannot use GPU/hardware acceleration
 * (e.g. models with dynamic-sized tensors like [BirdNetMetaModel]).
 */
interface InterpreterFactory {
    fun create(modelFile: File, threads: Int, allowDelegate: Boolean = true): InterpreterApi
}

/**
 * Minimal surface of `org.tensorflow.lite.Interpreter` used by the model
 * wrapper. Single-output models call [run]; multi-output models (e.g.
 * Perch v2 ships 4 output tensors) call [runForMultipleOutputs] and pick
 * the right tensor by index discovered via [getOutputShape].
 */
interface InterpreterApi {
    /** Number of output tensors. */
    val outputTensorCount: Int

    /** Shape of output tensor at [index] (e.g. `[1, 14795]`). */
    fun getOutputShape(index: Int): IntArray

    fun run(input: Any, output: Any)

    /** Runs inference and copies the requested outputs into [outputs]. */
    fun runForMultipleOutputs(input: Any, outputs: Map<Int, Any>)

    fun close()
}

class TfliteInterpreterFactory : InterpreterFactory {
    @Suppress("NestedBlockDepth")
    override fun create(modelFile: File, threads: Int, allowDelegate: Boolean): InterpreterApi {
        val raf = RandomAccessFile(modelFile, "r")
        val channel = raf.channel
        try {
            val buffer: MappedByteBuffer =
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())

            // Try GPU delegate when allowed — Adreno/Mali GPUs typically support a
            // wider set of TFLite ops than NNAPI drivers on mid-range devices, and
            // GPU is 2-5× faster than CPU+XNNPACK for large convolution models like
            // BirdNET and Perch. Models with dynamic-sized tensors (BirdNetMetaModel)
            // must pass allowDelegate=false — neither GPU nor NNAPI support them.
            var gpu: GpuDelegate? = null
            val interpreter = if (allowDelegate) {
                try {
                    val delegate = GpuDelegate()
                    val opts = Interpreter.Options().apply {
                        numThreads = threads
                        addDelegate(delegate)
                    }
                    val i = Interpreter(buffer, opts)
                    gpu = delegate
                    Log.i(TAG, "TFLite using GPU delegate (threads=$threads)")
                    i
                } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                    gpu?.close()
                    gpu = null
                    Log.w(TAG, "GPU delegate unavailable; falling back to CPU+XNNPACK (threads=$threads)", e)
                    Interpreter(buffer, Interpreter.Options().apply { numThreads = threads })
                }
            } else {
                Log.d(TAG, "GPU delegate skipped; using CPU+XNNPACK (threads=$threads)")
                Interpreter(buffer, Interpreter.Options().apply { numThreads = threads })
            }

            val capturedGpu = gpu
            return object : InterpreterApi {
                override val outputTensorCount: Int get() = interpreter.outputTensorCount
                override fun getOutputShape(index: Int): IntArray =
                    interpreter.getOutputTensor(index).shape()

                override fun run(input: Any, output: Any) = interpreter.run(input, output)

                override fun runForMultipleOutputs(input: Any, outputs: Map<Int, Any>) {
                    interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
                }

                override fun close() {
                    interpreter.close()
                    capturedGpu?.close()
                    channel.close()
                    raf.close()
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            runCatching { channel.close() }
            runCatching { raf.close() }
            throw t
        }
    }

    private companion object {
        const val TAG = "TfliteInterpreterFactory"
    }
}
