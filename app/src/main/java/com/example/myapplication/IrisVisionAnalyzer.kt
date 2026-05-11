package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.InterpreterApi
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class IrisVisionAnalyzer(
    private val context: Context,
    private val listener: (String) -> Unit,
    private val lowLightListener: () -> Unit,
    private val obstructionListener: (Boolean) -> Unit
) : ImageAnalysis.Analyzer {

    private var interpreter: InterpreterApi? = null
    private var labels = listOf<String>()
    
    // Obstruction detection state
    private var obstructionScore = 0
    private val OBSTRUCTION_THRESHOLD = 3
    private val OBSTRUCTION_RECOVERY_SPEED = 5

    // YOLOv11 Nano expects a 640x640 pixel input
    private val inputSize = 640

    // Manual Memory Allocation
    private val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).apply {
        order(ByteOrder.nativeOrder())
    }
    private val intValues = IntArray(inputSize * inputSize)

    init {
        try {
            val model = loadModelFile(context, "yolo11s_float32.tflite")
            val options = InterpreterApi.Options().apply {
                setNumThreads(4)
                setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
            }
            interpreter = InterpreterApi.create(model, options)
            labels = context.assets.open("labels.txt").bufferedReader().readLines()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        scaledBitmap.getPixels(intValues, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)

        // ── Signal 1: Average brightness ──
        var totalBrightness = 0f
        val step = 4 // Sample every 4th pixel for speed
        val sampleCount = intValues.size / step

        for (i in 0 until intValues.size step step) {
            val p = intValues[i]
            totalBrightness += (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF))
        }
        val avgBrightness = totalBrightness / sampleCount

        // ── Signal 2: Global variance of brightness ──
        // Obstructed lens → virtually zero variance (every pixel is nearly identical).
        // Low-light scene → still has noise/texture → measurable variance.
        var sumSquaredDiff = 0f
        for (i in 0 until intValues.size step step) {
            val p = intValues[i]
            val luma = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF))
            val diff = luma - avgBrightness
            sumSquaredDiff += diff * diff
        }
        val variance = sumSquaredDiff / sampleCount

        // ── Detection Logic ──
        // Obstruction: low global variance means every pixel is nearly identical (blocked lens).
        // A real scene, even in low light, has noise/texture producing measurable variance.
        val isFrameObstructed = variance < 15f

        Log.d("IRIS_LIGHT", "Brightness=%.1f  Variance=%.1f  ObsScore=$obstructionScore".format(avgBrightness, variance))

        if (isFrameObstructed) {
            obstructionScore = (obstructionScore + 1).coerceAtMost(10)
        } else {
            obstructionScore = (obstructionScore - OBSTRUCTION_RECOVERY_SPEED).coerceAtLeast(0)
        }

        val isObstructed = obstructionScore >= OBSTRUCTION_THRESHOLD 
        
        if (isObstructed) {
            Log.d("IRIS_OBSTRUCTION", "Obstruction CONFIRMED (Score: $obstructionScore)")
        }

        obstructionListener(isObstructed)

        // Low light: moderately dark but NOT obstructed (has real scene texture).
        // Only trigger when we're confident it's NOT an obstruction.
        if (!isObstructed && !isFrameObstructed && avgBrightness < 50f) {
            lowLightListener()
        }

        if (interpreter != null) {
            byteBuffer.rewind()
            for (i in 0 until inputSize * inputSize) {
                val valInt = intValues[i]
                byteBuffer.putFloat(((valInt shr 16) and 0xFF) / 255.0f)
                byteBuffer.putFloat(((valInt shr 8) and 0xFF) / 255.0f)
                byteBuffer.putFloat((valInt and 0xFF) / 255.0f)
            }

            val outputArray = Array(1) { Array(84) { FloatArray(8400) } }

            try {
                // LOG 1: Check if the AI is starting the math
                // Log.d("IRIS_AI", "Feeding frame to AI...")

                interpreter?.run(byteBuffer, outputArray)

                // LOG 2: Check if the AI survived the math
                // Log.d("IRIS_AI", "Frame processed successfully!")

                val detectedObject = parseYoloOutput(outputArray)

                if (detectedObject != null) {
                    listener(detectedObject)
                }
            } catch (e: Exception) {
                // LOG 3: Catch the exact reason the AI crashed
                Log.e("IRIS_ERROR", "AI MATH CRASH: ${e.message}")
            }
        }

        imageProxy.close()
    }

    private fun parseYoloOutput(output: Array<Array<FloatArray>>): String? {
        var bestConfidence = 0f
        var bestClassIndex = -1

        for (i in 0 until 8400) {
            for (c in 4 until 84) {
                val confidence = output[0][c][i]
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                    bestClassIndex = c - 4
                }
            }
        }

        // LOG 4: Print exactly what the AI thinks it sees and how confident it is
        val confidencePct = (bestConfidence * 100).toInt()
        Log.d("IRIS_AI", "Highest confidence in frame: $confidencePct% for item #$bestClassIndex")

        return if (bestConfidence > 0.25f && bestClassIndex in labels.indices) {
            labels[bestClassIndex]
        } else {
            null
        }
    }
}