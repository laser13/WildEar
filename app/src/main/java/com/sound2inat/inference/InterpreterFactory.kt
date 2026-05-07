package com.sound2inat.inference

import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Test seam for constructing TFLite [InterpreterApi] instances. The production
 * implementation memory-maps the model file via [MappedByteBuffer] and wraps
 * `org.tensorflow.lite.Interpreter`; tests substitute a fake.
 */
interface InterpreterFactory {
    fun create(modelFile: File, threads: Int): InterpreterApi
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
    override fun create(modelFile: File, threads: Int): InterpreterApi {
        val raf = RandomAccessFile(modelFile, "r")
        val channel = raf.channel
        val buffer: MappedByteBuffer =
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())

        // Try NNAPI delegate first — on Snapdragon devices this routes to the
        // Hexagon DSP / NPU and is typically 2–5× faster than CPU on TFLite
        // models like BirdNET. If NNAPI fails (unsupported ops, driver bug,
        // older Android), fall back to CPU-only with XNNPACK kernels (enabled
        // by default in TFLite 2.16). XNNPACK + N threads is still a strong
        // baseline.
        var nnapi: NnApiDelegate? = null
        val interpreter = try {
            val delegate = NnApiDelegate()
            val opts = Interpreter.Options().apply {
                numThreads = threads
                addDelegate(delegate)
            }
            val i = Interpreter(buffer, opts)
            nnapi = delegate
            Log.i(TAG, "TFLite using NNAPI delegate (threads=$threads)")
            i
        } catch (e: Throwable) {
            Log.w(TAG, "NNAPI unavailable; falling back to CPU+XNNPACK (threads=$threads)", e)
            Interpreter(buffer, Interpreter.Options().apply { numThreads = threads })
        }

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
                nnapi?.close()
                channel.close()
                raf.close()
            }
        }
    }

    private companion object {
        const val TAG = "TfliteInterpreterFactory"
    }
}
