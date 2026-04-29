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

/** Minimal surface of `org.tensorflow.lite.Interpreter` used by the model wrapper. */
interface InterpreterApi {
    fun run(input: Any, output: Any)
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
            override fun run(input: Any, output: Any) = interpreter.run(input, output)

            override fun close() {
                interpreter.close()
                channel.close()
                raf.close()
            }
        }
    }
}
