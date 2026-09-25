package com.example.camerax

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteHelper(context: Context) {

    private val interpreter: Interpreter

    init {
        // Load the model file directly from assets via FileChannel
        val modelBuffer = loadModelFile(context, "model.tflite")
        val options = Interpreter.Options().apply {
            setNumThreads(4) // Optimize thread usage
        }
        interpreter = Interpreter(modelBuffer, options)
    }

    private fun loadModelFile(context: Context, modelFileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Runs inference on input data.
     * Adjust input/output data structures (FloatArray, Array<FloatArray>, etc.)
     * depending on your model's expected shape.
     */
    fun predict(inputData: Array<FloatArray>): Array<FloatArray> {
        // Example output array structure matching your model's output shape
        val outputData = Array(1) { FloatArray(1) }

        interpreter.run(inputData, outputData)
        return outputData
    }

    fun close() {
        interpreter.close()
    }
}