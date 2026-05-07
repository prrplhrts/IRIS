package com.example.myapplication

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibrationEffect
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tflite.java.TfLite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var viewFinder: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var vibrator: Vibrator
    private lateinit var lowLightWarningOverlay: LinearLayout
    private lateinit var lowLightWarningTitle: TextView
    private lateinit var lowLightWarningSubtitle: TextView
    private lateinit var lowLightWarningIcon: ImageView
    private lateinit var obstructionWarningOverlay: LinearLayout
    private lateinit var obstructionWarningTitle: TextView
    private lateinit var obstructionWarningSubtitle: TextView
    private lateinit var obstructionWarningIcon: ImageView
    private lateinit var batteryWarningOverlay: LinearLayout
    private lateinit var batteryWarningTitle: TextView
    private lateinit var batteryWarningSubtitle: TextView
    private lateinit var batteryWarningIcon: ImageView
    private lateinit var lowLightWarningTag: TextView
    private lateinit var obstructionWarningTag: TextView
    private lateinit var toneGenerator: android.media.ToneGenerator

    private var tts: TextToSpeech? = null
    private var lastSpokenTime: Long = 0
    private var lastLowLightTime: Long = 0
    private var lastObstructionTime: Long = 0
    private var lastWarnedBatteryPct = -1
    private var isObstructionFadingOut = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = (level * 100) / scale.toFloat()

            if (batteryPct <= 20) {
                val currentPct = batteryPct.toInt()
                if (currentPct != lastWarnedBatteryPct) {
                    lastWarnedBatteryPct = currentPct
                    triggerBatteryWarningSequence(currentPct)
                }
            } else {
                runOnUiThread {
                    batteryWarningOverlay.visibility = View.GONE
                }
                lastWarnedBatteryPct = -1
            }
        }
    }

    private fun triggerBatteryWarningSequence(batteryPercentage: Int) {
        lifecycleScope.launch {
            // First warning
            speakOut("Warning. The device battery is at $batteryPercentage%. Please charge the device.")
            showWarning(batteryWarningOverlay, batteryWarningTitle, batteryWarningSubtitle, "LOW BATTERY\nPERCENTAGE", "Please connect to a power source\nto ensure continuous navigation.", 3000L)
            
            // Wait for 3s display + 500ms fade + 5s interval before showing again
            delay(3000L + 500L + 5000L)
            
            // Second warning
            speakOut("Warning. The device battery is at $batteryPercentage%. Please charge the device.")
            showWarning(batteryWarningOverlay, batteryWarningTitle, batteryWarningSubtitle, "LOW BATTERY\nPERCENTAGE", "Please connect to a power source\nto ensure continuous navigation.", 3000L)
        }
    }

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                initTfLiteAndStartCamera()
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        tts = TextToSpeech(this, this)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        lowLightWarningOverlay = findViewById(R.id.lowLightWarningOverlay)
        lowLightWarningTitle = findViewById(R.id.lowLightWarningTitle)
        lowLightWarningSubtitle = findViewById(R.id.lowLightWarningSubtitle)
        lowLightWarningIcon = findViewById(R.id.lowLightWarningIcon)
        obstructionWarningOverlay = findViewById(R.id.obstructionWarningOverlay)
        obstructionWarningTitle = findViewById(R.id.obstructionWarningTitle)
        obstructionWarningSubtitle = findViewById(R.id.obstructionWarningSubtitle)
        obstructionWarningIcon = findViewById(R.id.obstructionWarningIcon)
        batteryWarningOverlay = findViewById(R.id.batteryWarningOverlay)
        batteryWarningTitle = findViewById(R.id.batteryWarningTitle)
        batteryWarningSubtitle = findViewById(R.id.batteryWarningSubtitle)
        batteryWarningIcon = findViewById(R.id.batteryWarningIcon)
        lowLightWarningTag = findViewById(R.id.lowLightWarningTag)
        obstructionWarningTag = findViewById(R.id.obstructionWarningTag)
        toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)

        // Receiver registration moved to onInit so TTS has time to warm up first
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            initTfLiteAndStartCamera()
        } else {
            requestPermissions()
        }
    }

    // Wakes up the Play Services AI engine, THEN starts the camera
    private fun initTfLiteAndStartCamera() {
        TfLite.initialize(this).addOnSuccessListener {
            startCamera()
        }.addOnFailureListener {
            Log.e("IRIS", "Failed to initialize TFLite", it)
            Toast.makeText(this, "AI Engine failed to load.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(640, 480))
                .build()
                .also {
                    val analyzer = IrisVisionAnalyzer(this, { detectedObject ->
                        triggerAudioWarning(detectedObject)
                    }, {
                        triggerLowLightWarning()
                    }, { isObstructed ->
                        handleObstructionWarning(isObstructed)
                    })
                    var frameCount = 0
                    it.setAnalyzer(cameraExecutor, object : ImageAnalysis.Analyzer {
                        override fun analyze(image: ImageProxy) {
                            frameCount++
                            if (frameCount % 3 != 0) {  // Process every 3rd frame to prevent lag
                                image.close()
                                return
                            }
                            try {
                                analyzer.analyze(image)
                            } catch (e: Exception) {
                                Log.e("IRIS", "Image analysis failed", e)
                            } finally {
                                image.close()
                            }
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("IRIS", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun triggerAudioWarning(detectedObject: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSpokenTime > 3000) {
            Log.d("IRIS", detectedObject)
            speakOut("$detectedObject ahead")
            lastSpokenTime = currentTime
        }
    }

    private fun triggerLowLightWarning() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLowLightTime > 5000) {
            Log.d("IRIS", "Triggering low light warning")
            showWarning(lowLightWarningOverlay, lowLightWarningTitle, lowLightWarningSubtitle, "Low\nLighting", "Detection accuracy may be reduced")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 150, 100, 150), -1)
            }
            
            // Set Red Color for Low Light to maintain consistency
            lowLightWarningTag.background.setTint(android.graphics.Color.parseColor("#D32F2F"))
            
            speakOut("WARNING: The camera detects low lighting environment.")
            lastLowLightTime = currentTime
            lastSpokenTime = currentTime
        }
    }

    private fun handleObstructionWarning(isObstructed: Boolean) {
        val currentTime = System.currentTimeMillis()
        if (isObstructed) {
            isObstructionFadingOut = false // Reset immediately
            runOnUiThread {
                obstructionWarningOverlay.animate().cancel()
                obstructionWarningOverlay.alpha = 1f
                obstructionWarningOverlay.visibility = View.VISIBLE
                obstructionWarningTitle.text = "CAMERA\nOBSCURED"
                obstructionWarningSubtitle.text = "Detection accuracy may be reduced"
            }

            if (currentTime - lastObstructionTime > 3000) {
                Log.d("IRIS", "Triggering obstruction warning")
                
                // Play sharp alert tone for obstruction
                toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 200)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Aggressive rapid vibration for obstruction
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100, 50, 100, 50, 100), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 100, 50, 100, 50, 100, 50, 100), -1)
                }
                
                // Set Bright Red Color for Obstruction
                obstructionWarningTag.background.setTint(android.graphics.Color.parseColor("#D32F2F"))
                
                speakOut("WARNING: There is obstruction on the camera.")
                lastObstructionTime = currentTime
                lastSpokenTime = currentTime
            }
        } else {
            runOnUiThread {
                // Safety check: if it's visible but not currently fading, start fading.
                if (obstructionWarningOverlay.visibility == View.VISIBLE && !isObstructionFadingOut) {
                    isObstructionFadingOut = true
                    obstructionWarningOverlay.animate()
                        .alpha(0f)
                        .setDuration(500)
                        .withEndAction {
                            obstructionWarningOverlay.visibility = View.GONE
                            isObstructionFadingOut = false
                        }
                        .start()
                }
            }
        }
    }

    /**
     * Displays the warning overlay and hides it after a timeout with fade-out.
     */
    private fun showWarning(overlay: LinearLayout, titleText: TextView, subtitleText: TextView, title: String, subtitle: String, timeoutMillis: Long = 3000L) {
        runOnUiThread {
            // Set the dynamic text
            titleText.text = title
            subtitleText.text = subtitle

            // Reset alpha and show it
            overlay.alpha = 1f
            overlay.visibility = View.VISIBLE

            // Start a new coroutine for timeout and fade-out
            lifecycleScope.launch {
                delay(timeoutMillis)
                overlay.animate()
                    .alpha(0f)
                    .setDuration(500)
                    .withEndAction {
                        overlay.visibility = View.GONE
                    }
                    .start()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.US)
            
            // Register battery receiver ONLY AFTER TTS is fully ready
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    private fun speakOut(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.e("BatteryReceiver", "Receiver already unregistered.")
        }
        cameraExecutor.shutdown()
        toneGenerator.release()
        tts?.stop()
        tts?.shutdown()
    }
}