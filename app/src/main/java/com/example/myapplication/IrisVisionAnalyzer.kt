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

// Data class to hold both the label and the bounding box coordinates
data class YoloDetection(val label: String, val cx: Float, val cy: Float, val w: Float, val h: Float)

class IrisVisionAnalyzer(
    private val context: Context,
    private val midasEstimator: MidasDepthEstimator,
    private val listener: (String, Float) -> Unit,
    private val lowLightListener: () -> Unit,
    private val obstructionListener: (Boolean) -> Unit
) : ImageAnalysis.Analyzer {

    private var interpreter: InterpreterApi? = null
    private var labels = listOf<String>()

    private var obstructionScore = 0
    private val OBSTRUCTION_THRESHOLD = 3
    private val OBSTRUCTION_RECOVERY_SPEED = 5
    private val inputSize = 640

    private val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).apply {
        order(ByteOrder.nativeOrder())
    }
    private val intValues = IntArray(inputSize * inputSize)

    init {
        try {
            val model = loadModelFile(context, "yolo11m_float32.tflite")
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
        return inputStream.channel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        scaledBitmap.getPixels(intValues, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)

        // Light & Obstruction Logic
        var totalBrightness = 0f
        val step = 4
        val sampleCount = intValues.size / step
        var sumSquaredDiff = 0f

        for (i in 0 until intValues.size step step) {
            val p = intValues[i]
            val luma = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF))
            totalBrightness += luma
        }
        val avgBrightness = totalBrightness / sampleCount

        for (i in 0 until intValues.size step step) {
            val p = intValues[i]
            val luma = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF))
            val diff = luma - avgBrightness
            sumSquaredDiff += diff * diff
        }
        val variance = sumSquaredDiff / sampleCount
        val isFrameObstructed = variance < 15f

        if (isFrameObstructed) obstructionScore = (obstructionScore + 1).coerceAtMost(10)
        else obstructionScore = (obstructionScore - OBSTRUCTION_RECOVERY_SPEED).coerceAtLeast(0)

        val isObstructed = obstructionScore >= OBSTRUCTION_THRESHOLD
        obstructionListener(isObstructed)

        if (!isObstructed && !isFrameObstructed && avgBrightness < 50f) {
            lowLightListener()
        }

        // YOLO AI Execution
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
                byteBuffer.rewind() // CRITICAL FIX: Ensure position is 0 before AI runs
                interpreter?.run(byteBuffer, outputArray)
                val detection = parseYoloOutput(outputArray)

                if (detection != null) {
                    val depthMap = midasEstimator.estimateDepth(bitmap)

                    if (depthMap != null) {
                        val distanceMeters = calculateDistance(detection, depthMap)
                        listener(detection.label, distanceMeters)
                    } else {
                        Log.e("IRIS_DEPTH", "MiDaS array returned null. Check initialization.")
                        listener(detection.label, -1f)
                    }
                }
            } catch (e: Exception) {
                Log.e("IRIS_ERROR", "AI MATH CRASH: ${e.message}")
            }
        }
        imageProxy.close()
    }

    private fun parseYoloOutput(output: Array<Array<FloatArray>>): YoloDetection? {
        var bestConfidence = 0f
        var bestClassIndex = -1
        var bestBoxIndex = -1

        for (i in 0 until 8400) {
            for (c in 4 until 84) {
                val confidence = output[0][c][i]
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                    bestClassIndex = c - 4
                    bestBoxIndex = i
                }
            }
        }

        if (bestConfidence > 0.25f && bestClassIndex in labels.indices) {
            val cx = output[0][0][bestBoxIndex]
            val cy = output[0][1][bestBoxIndex]
            val w = output[0][2][bestBoxIndex]
            val h = output[0][3][bestBoxIndex]
            return YoloDetection(labels[bestClassIndex], cx, cy, w, h)
        }
        return null
    }

    private fun calculateDistance(detection: YoloDetection, depthMap: FloatArray): Float {
        // SMART CHECK: Detect if YOLO exported normalized (0.0-1.0) or absolute (0-640) coordinates
        // and map them cleanly to the MiDaS 256x256 grid.
        val mCx = if (detection.cx <= 1f) detection.cx * 256f else detection.cx * (256f / 640f)
        val mCy = if (detection.cy <= 1f) detection.cy * 256f else detection.cy * (256f / 640f)
        val mW = if (detection.w <= 1f) detection.w * 256f else detection.w * (256f / 640f)
        val mH = if (detection.h <= 1f) detection.h * 256f else detection.h * (256f / 640f)

        // Define the bounding box on the 256x256 grid
        val startX = (mCx - mW / 2).toInt().coerceIn(0, 255)
        val endX = (mCx + mW / 2).toInt().coerceIn(0, 255)
        val startY = (mCy - mH / 2).toInt().coerceIn(0, 255)
        val endY = (mCy + mH / 2).toInt().coerceIn(0, 255)

        var sumDepth = 0f
        var count = 0

        // Average the pixels specifically inside the object's box
        for (y in startY..endY) {
            for (x in startX..endX) {
                sumDepth += depthMap[y * 256 + x]
                count++
            }
        }

        // If the box is 0 pixels, fail gracefully
        if (count == 0) {
            Log.e("IRIS_DEPTH", "Bounding box mapped to 0 pixels.")
            return -1f
        }

        val avgDepth = sumDepth / count

        // MiDaS outputs *inverse* depth (larger numbers = closer objects).
        // 500f is a starting heuristic scalar.
        val safeDepth = java.lang.Math.max(avgDepth, 0.1f) // Prevent division by zero
        val distanceMeters = 500f / safeDepth

        Log.d("IRIS_DEPTH", "Target: ${detection.label} | Pixels Analyzed: $count | Raw Inverse Depth: $avgDepth | Est. Meters: $distanceMeters")

        return distanceMeters
    }
}