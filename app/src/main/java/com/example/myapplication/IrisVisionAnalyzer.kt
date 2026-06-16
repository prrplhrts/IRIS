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

data class YoloDetection(val label: String, val cx: Float, val cy: Float, val w: Float, val h: Float)

// MEMORY TRACKER CLASS
data class TrackedObject(
    var distance: Float,
    var framesSinceLastSeen: Int,
    var consecutiveHits: Int
)

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

    // TEMPORAL SMOOTHING STATE
    private val trackingMemory = mutableMapOf<String, TrackedObject>()
    private val MAX_MISSES = 5 // Remember a dropped object for 5 frames
    private val MIN_HITS = 2   // Must see an object twice to prove it's not a hallucination

    // THE KILL SWITCH
    fun forceClearMemory() {
        trackingMemory.clear()
    }

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

        // ── Light & Obstruction Logic ──
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

        // ── YOLO AI Execution & Memory Tracker ──
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
                byteBuffer.rewind()
                interpreter?.run(byteBuffer, outputArray)
                val detection = parseYoloOutput(outputArray)

                // 1. Age all existing memories
                for (entry in trackingMemory.entries) {
                    entry.value.framesSinceLastSeen++
                }

                // 2. Process current frame's detection
                if (detection != null) {
                    val depthMap = midasEstimator.estimateDepth(bitmap)
                    val distanceMeters = if (depthMap != null) calculateDistance(detection, depthMap) else -1f

                    if (trackingMemory.containsKey(detection.label)) {
                        val track = trackingMemory[detection.label]!!
                        track.distance = distanceMeters // Update distance
                        track.framesSinceLastSeen = 0   // Reset miss counter
                        track.consecutiveHits++         // Increase confidence
                    } else {
                        trackingMemory[detection.label] = TrackedObject(distanceMeters, 0, 1)
                    }
                }

                // 3. Clean up old memories (Forget objects not seen in 5 frames)
                trackingMemory.entries.removeIf { it.value.framesSinceLastSeen > MAX_MISSES }

                // 4. Find the most reliable & closest object to announce
                val bestTrack = trackingMemory.entries
                    .filter { it.value.consecutiveHits >= MIN_HITS }
                    .minByOrNull {
                        val dist = if (it.value.distance > 0) it.value.distance else 999f
                        (it.value.framesSinceLastSeen * 1000) + dist
                    }

                // 5. Send to Text-To-Speech (GHOST MEMORY FIX IS HERE)
                if (bestTrack != null) {
                    listener(bestTrack.key, bestTrack.value.distance)
                } else {
                    // Send an empty string so MainActivity knows the screen is physically empty
                    listener("", -1f)
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

        if (bestConfidence > 0.35f && bestClassIndex in labels.indices) {
            val cx = output[0][0][bestBoxIndex]
            val cy = output[0][1][bestBoxIndex]
            val w = output[0][2][bestBoxIndex]
            val h = output[0][3][bestBoxIndex]
            return YoloDetection(labels[bestClassIndex], cx, cy, w, h)
        }
        return null
    }

    private fun calculateDistance(detection: YoloDetection, depthMap: FloatArray): Float {
        val mCx = if (detection.cx <= 1f) detection.cx * 256f else detection.cx * (256f / 640f)
        val mCy = if (detection.cy <= 1f) detection.cy * 256f else detection.cy * (256f / 640f)
        val mW = if (detection.w <= 1f) detection.w * 256f else detection.w * (256f / 640f)
        val mH = if (detection.h <= 1f) detection.h * 256f else detection.h * (256f / 640f)

        val startX = (mCx - mW / 2).toInt().coerceIn(0, 255)
        val endX = (mCx + mW / 2).toInt().coerceIn(0, 255)
        val startY = (mCy - mH / 2).toInt().coerceIn(0, 255)
        val endY = (mCy + mH / 2).toInt().coerceIn(0, 255)

        var sumDepth = 0f
        var count = 0

        for (y in startY..endY) {
            for (x in startX..endX) {
                sumDepth += depthMap[y * 256 + x]
                count++
            }
        }

        if (count == 0) return -1f

        val avgDepth = sumDepth / count
        val safeDepth = java.lang.Math.max(avgDepth, 0.1f)

        return 500f / safeDepth
    }
}