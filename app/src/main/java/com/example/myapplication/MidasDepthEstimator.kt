package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.InterpreterApi
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MidasDepthEstimator(private val context: Context) {
    private var interpreter: InterpreterApi? = null
    var isInitialized = false

    private val inputSize = 256

    private val inputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 1).apply {
        order(ByteOrder.nativeOrder())
    }
    private val intValues = IntArray(inputSize * inputSize)

    init {
        // Because we guarantee Play Services is awake in MainActivity before this is called,
        // we can safely load the model immediately.
        loadModel()
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    private fun loadModel() {
        try {
            val model = loadModelFile(context, "midas.tflite") // Ensure this matches your file name

            // Step 2: Load the model into the InterpreterApi
            val options = InterpreterApi.Options().apply {
                setNumThreads(4) // Use 4 CPU threads for speed

                // CRITICAL FIX: Tell the app to use Google Play Services instead of the missing local library
                setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
            }

            interpreter = InterpreterApi.create(model, options)
            isInitialized = true
            Log.d("MiDaS", "MiDaS model loaded successfully via Google Play Services!")
        } catch (e: Exception) {
            Log.e("MiDaS", "Error loading model", e)
        }
    }

    fun estimateDepth(bitmap: Bitmap): FloatArray? {
        if (!isInitialized || interpreter == null) {
            Log.e("MiDaS", "MiDaS rejected the frame: Not Initialized")
            return null
        }

        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        resized.getPixels(intValues, 0, resized.width, 0, 0, resized.width, resized.height)

        inputBuffer.rewind()
        for (pixel in intValues) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }

        // CRITICAL FIX: Both buffers MUST be rewound before running the interpreter
        inputBuffer.rewind()
        outputBuffer.rewind()
        interpreter?.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        val floatArray = FloatArray(inputSize * inputSize)
        outputBuffer.asFloatBuffer().get(floatArray)

        return floatArray
    }

    fun close() {
        interpreter?.close()
    }
}