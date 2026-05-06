package com.sound2inat.inference

import org.tensorflow.lite.Interpreter
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
        val opts = Interpreter.Options().apply { numThreads = threads }
        val interpreter = Interpreter(buffer, opts)
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
                channel.close()
                raf.close()
            }
        }
    }
}
