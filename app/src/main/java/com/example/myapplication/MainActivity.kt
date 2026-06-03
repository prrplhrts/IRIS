package com.example.myapplication

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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
import android.graphics.Color

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var viewFinder: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var vibrator: Vibrator
    private lateinit var lowLightWarningOverlay: LinearLayout
    private lateinit var lowLightWarningTitle: TextView
    private lateinit var lowLightWarningSubtitle: TextView
    private lateinit var obstructionWarningOverlay: LinearLayout
    private lateinit var obstructionWarningTitle: TextView
    private lateinit var obstructionWarningSubtitle: TextView
    private lateinit var batteryWarningOverlay: LinearLayout
    private lateinit var batteryWarningTitle: TextView
    private lateinit var batteryWarningSubtitle: TextView
    private lateinit var toneGenerator: android.media.ToneGenerator

    // SENSOR FUSION: MiDaS Depth Estimator instance
    private lateinit var midasEstimator: MidasDepthEstimator

    // Goodbye overlay views
    private lateinit var goodbyeOverlay: FrameLayout
    private lateinit var goodbyeLogo: ImageView
    private lateinit var goodbyeGlow: View
    private lateinit var goodbyeTitle: TextView
    private lateinit var goodbyeSubtitle: TextView

    private lateinit var statusLayoutContainer: FrameLayout
    private var isStatusDashboardOpen = false

    // Status Dashboard View Hooks
    private lateinit var ivMainStatusIcon: ImageView
    private lateinit var tvMainStatusText: TextView
    private lateinit var ivAppIcon: ImageView
    private lateinit var tvAppState: TextView
    private lateinit var tvBatteryState: TextView
    private lateinit var tvCameraState: TextView

    // Long-press detection
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private val LONG_PRESS_DURATION = 1000L
    private var isClosing = false

    private var tts: TextToSpeech? = null
    private var lastSpokenTime: Long = 0
    private var latestDetectedObject: String = ""
    private var lastLowLightTime: Long = 0
    private var lastObstructionTime: Long = 0
    private var lastWarnedBatteryPct = -1
    private var isObstructionFadingOut = false

    private var isThreeFingerDetected = false
    private var threeFingerStartX = 0f
    private val SWIPE_THRESHOLD = 150
    private var isSettingsOpening = false

    // Tracks frame activity metrics for Device Health diagnostics
    private var lastProcessedFrameTimestamp: Long = 0L

    // Multitouch tracking properties
    private var isTwoFingerDetected = false
    private var twoFingerTouchStartTime: Long = 0
    private val MAX_TAP_DURATION = 350L
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
        if (isStatusDashboardOpen || isClosing || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        lifecycleScope.launch {
            speakOut("Warning. The device battery is at $batteryPercentage%. Please charge the device.")
            showWarning(batteryWarningOverlay, batteryWarningTitle, batteryWarningSubtitle, "LOW BATTERY\nPERCENTAGE", "Please connect to a power source\nto ensure continuous navigation.", 3000L)

            delay(3000L + 500L + 5000L)

            if (isStatusDashboardOpen || isClosing) return@launch

            speakOut("Warning. The device battery is at $batteryPercentage%. Please charge the device.")
            showWarning(batteryWarningOverlay, batteryWarningTitle, batteryWarningSubtitle, "LOW BATTERY\nPERCENTAGE", "Please connect to a power source\nto ensure continuous navigation.", 3000L)
        }
    }

    private fun openSettings() {
        if (isSettingsOpening || isClosing) return
        isSettingsOpening = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }

        tts?.speak("Opening settings", TextToSpeech.QUEUE_ADD, null, "")

        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)

        viewFinder.postDelayed({ isSettingsOpening = false }, 2000)
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
        obstructionWarningOverlay = findViewById(R.id.obstructionWarningOverlay)
        obstructionWarningTitle = findViewById(R.id.obstructionWarningTitle)
        obstructionWarningSubtitle = findViewById(R.id.obstructionWarningSubtitle)
        batteryWarningOverlay = findViewById(R.id.batteryWarningOverlay)
        batteryWarningTitle = findViewById(R.id.batteryWarningTitle)
        batteryWarningSubtitle = findViewById(R.id.batteryWarningSubtitle)
        toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)

        goodbyeOverlay = findViewById(R.id.goodbye_overlay)
        goodbyeLogo = findViewById(R.id.goodbye_logo)
        goodbyeGlow = findViewById(R.id.goodbye_glow)
        goodbyeTitle = findViewById(R.id.goodbye_title)
        goodbyeSubtitle = findViewById(R.id.goodbye_subtitle)
        statusLayoutContainer = findViewById(R.id.status_layout_container)

        // NOTE: midasEstimator was removed from here. It is now inside initTfLiteAndStartCamera()!

        viewFinder.setOnTouchListener { _, event ->
            if (isClosing) return@setOnTouchListener true

            val action = event.actionMasked

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressRunnable = Runnable { playGoodbyeSequence() }
                    longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                    if (event.pointerCount == 2) {
                        isTwoFingerDetected = true
                        twoFingerTouchStartTime = System.currentTimeMillis()
                    }

                    if (event.pointerCount == 3) {
                        isThreeFingerDetected = true
                        isTwoFingerDetected = false
                        threeFingerStartX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3
                    }
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                    if (isTwoFingerDetected) {
                        val duration = System.currentTimeMillis() - twoFingerTouchStartTime
                        if (duration < 350L) {
                            performSystemDiagnosticCheck()
                        }
                        isTwoFingerDetected = false
                    }
                    isThreeFingerDetected = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isThreeFingerDetected && event.pointerCount >= 3) {
                        val currentX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3
                        val deltaX = threeFingerStartX - currentX

                        if (Math.abs(deltaX) > SWIPE_THRESHOLD) {
                            isThreeFingerDetected = false
                            openSettings()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    isTwoFingerDetected = false
                    isThreeFingerDetected = false
                }
            }
            false
        }

        viewFinder.setOnClickListener {
            val sharedPrefs = getSharedPreferences("IrisSettings", Context.MODE_PRIVATE)
            val currentScanMode = sharedPrefs.getString("scan_mode", "Continuous")

            if (currentScanMode == "Tap to Scan") {
                if (latestDetectedObject.isNotEmpty()) {
                    speakOut(latestDetectedObject)
                } else {
                    speakOut("Scanning, please hold steady.")
                }
            }
        }

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

    private fun initTfLiteAndStartCamera() {
        TfLite.initialize(this).addOnSuccessListener {
            // SUCCESS! Google Play Services is awake. Now we can safely build MiDaS.
            midasEstimator = MidasDepthEstimator(this)

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
                    val sharedPrefs = getSharedPreferences("IrisSettings", Context.MODE_PRIVATE)

                    // Pass the MiDaS Estimator into the IrisVisionAnalyzer
                    val analyzer = IrisVisionAnalyzer(this, midasEstimator, { detectedObject, distance ->

                        // Formatting logic for TTS output: "(object) x meters ahead"
                        val ttsMessage = if (distance > 0.1f) {
                            val distanceStr = String.format(Locale.US, "%.1f", distance)
                            "$detectedObject $distanceStr meters ahead"
                        } else {
                            "$detectedObject ahead"
                        }

                        val currentScanMode = sharedPrefs.getString("scan_mode", "Continuous")

                        if (currentScanMode == "Continuous") {
                            triggerAudioWarning(ttsMessage)
                        } else {
                            latestDetectedObject = ttsMessage
                        }
                    }, {
                        triggerLowLightWarning()
                    }, { isObstructed ->
                        handleObstructionWarning(isObstructed)
                    })
                    var frameCount = 0
                    it.setAnalyzer(cameraExecutor, object : ImageAnalysis.Analyzer {
                        override fun analyze(image: ImageProxy) {
                            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                                image.close()
                                return
                            }
                            lastProcessedFrameTimestamp = System.currentTimeMillis()
                            frameCount++
                            if (frameCount % 3 != 0) {
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

    private fun triggerAudioWarning(message: String) {
        if (isStatusDashboardOpen || isClosing || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSpokenTime > 3000) {
            Log.d("IRIS", message)
            speakOut(message)
            lastSpokenTime = currentTime
        }
    }

    private fun triggerLowLightWarning() {
        if (isStatusDashboardOpen || isClosing || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
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

            speakOut("WARNING: The camera detects low lighting environment.")
            lastLowLightTime = currentTime
            lastSpokenTime = currentTime
        }
    }

    private fun handleObstructionWarning(isObstructed: Boolean) {
        if (isStatusDashboardOpen || isClosing || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            if (obstructionWarningOverlay.visibility == View.VISIBLE) {
                runOnUiThread { obstructionWarningOverlay.visibility = View.GONE }
            }
            return
        }
        val currentTime = System.currentTimeMillis()
        if (isObstructed) {
            isObstructionFadingOut = false
            runOnUiThread {
                obstructionWarningOverlay.animate().cancel()
                obstructionWarningOverlay.alpha = 1f
                obstructionWarningOverlay.visibility = View.VISIBLE
                obstructionWarningTitle.text = "CAMERA\nOBSCURED"
                obstructionWarningSubtitle.text = "Detection accuracy may be reduced"
            }

            if (currentTime - lastObstructionTime > 3000) {
                Log.d("IRIS", "Triggering obstruction warning")
                toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 200)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100, 50, 100, 50, 100), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 100, 50, 100, 50, 100, 50, 100), -1)
                }

                speakOut("WARNING: There is obstruction on the camera.")
                lastObstructionTime = currentTime
                lastSpokenTime = currentTime
            }
        } else {
            runOnUiThread {
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

    private fun showWarning(overlay: LinearLayout, titleText: TextView, subtitleText: TextView, title: String, subtitle: String, timeoutMillis: Long = 3000L) {
        runOnUiThread {
            titleText.text = title
            subtitleText.text = subtitle

            overlay.alpha = 1f
            overlay.visibility = View.VISIBLE

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

    private fun performSystemDiagnosticCheck() {
        if (isStatusDashboardOpen || isClosing) return
        val isAppWorking = true

        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val isFrameFlowing = (System.currentTimeMillis() - lastProcessedFrameTimestamp) < 2500
        val isCameraActive = isFrameFlowing && (batteryLevel > 15)

        val isSystemHealthy = isAppWorking && isCameraActive && (batteryLevel > 15)

        isStatusDashboardOpen = true
        runOnUiThread {
            lowLightWarningOverlay.visibility = View.GONE
            obstructionWarningOverlay.visibility = View.GONE
            batteryWarningOverlay.visibility = View.GONE

            statusLayoutContainer.removeAllViews()
            val layoutId = R.layout.activity_status_healthy
            val inflatedView = layoutInflater.inflate(layoutId, statusLayoutContainer, true)
            statusLayoutContainer.visibility = View.VISIBLE

            ivMainStatusIcon = inflatedView.findViewById(R.id.iv_main_status_icon)
            tvMainStatusText = inflatedView.findViewById(R.id.tv_main_status_text)
            ivAppIcon = inflatedView.findViewById(R.id.iv_app_icon)
            tvAppState = inflatedView.findViewById(R.id.tv_app_state)
            tvBatteryState = inflatedView.findViewById(R.id.tv_battery_state)
            tvCameraState = inflatedView.findViewById(R.id.tv_camera_state)

            if (isSystemHealthy) {
                ivMainStatusIcon.setImageResource(R.drawable.monitor_green)
                tvMainStatusText.text = "System healthy"
                tvMainStatusText.setTextColor(Color.parseColor("#00FF00"))

                ivAppIcon.setImageResource(R.drawable.check)
                tvAppState.text = "Running"
                tvAppState.setTextColor(Color.parseColor("#00FF00"))

                tvBatteryState.text = "$batteryLevel%"
                tvBatteryState.setTextColor(Color.parseColor("#00FF00"))

                tvCameraState.text = "Active"
                tvCameraState.setTextColor(Color.parseColor("#00FF00"))
            } else {
                ivMainStatusIcon.setImageResource(R.drawable.monitor_red)
                tvMainStatusText.text = "System Error!"
                tvMainStatusText.setTextColor(Color.parseColor("#FF0000"))

                ivAppIcon.setImageResource(R.drawable.x)
                tvAppState.text = if (isAppWorking) "Running" else "Stopped"
                tvAppState.setTextColor(if (isAppWorking) Color.parseColor("#00FF00") else Color.parseColor("#FF0000"))

                tvBatteryState.text = "$batteryLevel%"
                tvBatteryState.setTextColor(if (batteryLevel > 15) Color.parseColor("#00FF00") else Color.parseColor("#FF0000"))

                tvCameraState.text = if (isCameraActive) "Active" else "Inactive"
                tvCameraState.setTextColor(if (isCameraActive) Color.parseColor("#00FF00") else Color.parseColor("#FF0000"))
            }
        }

        val speechOutput = StringBuilder()
        if (isSystemHealthy) {
            speechOutput.append("System is healthy. ")
        } else {
            speechOutput.append("System error detected. ")
        }
        speechOutput.append("Battery is at $batteryLevel percent. ")
        if (isCameraActive) {
            speechOutput.append("Camera is active.")
        } else {
            speechOutput.append("Camera is inactive.")
        }

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "DIAGNOSTIC_ID")
        tts?.speak(speechOutput.toString(), TextToSpeech.QUEUE_FLUSH, params, "DIAGNOSTIC_ID")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.US)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    when (utteranceId) {
                        "DIAGNOSTIC_ID" -> {
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (isStatusDashboardOpen) {
                                    closeStatusDashboard()
                                }
                            }, 2000)
                        }
                        "GOODBYE" -> {
                            Handler(Looper.getMainLooper()).postDelayed({
                                finishAffinity()
                            }, 1200)
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    when (utteranceId) {
                        "DIAGNOSTIC_ID" -> closeStatusDashboard()
                        "GOODBYE" -> finishAffinity()
                    }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    when (utteranceId) {
                        "DIAGNOSTIC_ID" -> closeStatusDashboard()
                        "GOODBYE" -> {
                            if (interrupted) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    finishAffinity()
                                }, 500)
                            }
                        }
                    }
                }
            })

            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    private fun speakOut(text: String) {
        if (isStatusDashboardOpen || isClosing) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun playGoodbyeSequence() {
        if (isClosing) return
        isClosing = true

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "GOODBYE")
        tts?.speak("IRIS is now closing. Goodbye", TextToSpeech.QUEUE_FLUSH, params, "GOODBYE")

        lifecycleScope.launch {
            runOnUiThread {
                goodbyeOverlay.visibility = View.VISIBLE
                goodbyeOverlay.animate()
                    .alpha(1f)
                    .setDuration(400)
                    .start()
            }
            delay(300)

            runOnUiThread {
                goodbyeLogo.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .start()
            }
            delay(200)

            runOnUiThread {
                goodbyeGlow.animate()
                    .alpha(1f)
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(600)
                    .start()

                ObjectAnimator.ofPropertyValuesHolder(
                    goodbyeGlow,
                    PropertyValuesHolder.ofFloat("scaleX", 1.1f, 1.25f),
                    PropertyValuesHolder.ofFloat("scaleY", 1.1f, 1.25f),
                    PropertyValuesHolder.ofFloat("alpha", 1f, 0.5f)
                ).apply {
                    duration = 1500
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    startDelay = 600
                    start()
                }
            }
            delay(300)

            runOnUiThread {
                goodbyeTitle.translationY = 20f
                goodbyeTitle.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(500)
                    .start()
            }
            delay(300)

            runOnUiThread {
                goodbyeSubtitle.translationY = 15f
                goodbyeSubtitle.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(500)
                    .start()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.e("BatteryReceiver", "Receiver already unregistered.")
        }
        cameraExecutor.shutdown()
        toneGenerator.release()
        tts?.stop()
        tts?.shutdown()

        // Safety check to prevent a crash on closing if MiDaS never initialized
        if (::midasEstimator.isInitialized) {
            midasEstimator.close()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager

        when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                announceVolume(audioManager)
                return true
            }
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                announceVolume(audioManager)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun announceVolume(audioManager: android.media.AudioManager) {
        val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val percent = (currentVolume.toDouble() / maxVolume * 100).toInt()

        val text = "Volume at $percent percent"

        val params = Bundle()
        params.putString(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "VOLUME_ID")

        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "VOLUME_ID")

        toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100)
    }

    private fun closeStatusDashboard() {
        isStatusDashboardOpen = false
        runOnUiThread {
            statusLayoutContainer.visibility = View.GONE
            statusLayoutContainer.removeAllViews()
        }
    }
}