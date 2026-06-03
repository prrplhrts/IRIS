package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import java.util.Locale
import kotlin.math.abs

class SettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var moduleLanguageConfig: ConstraintLayout
    private lateinit var moduleScanConfig: ConstraintLayout
    private lateinit var tvSettingsSub: TextView
    private lateinit var tvCurrentLanguageValue: TextView
    private lateinit var tvCurrentScanValue: TextView

    private lateinit var gestureDetector: GestureDetector
    private lateinit var tts: TextToSpeech
    private lateinit var sharedPreferences: SharedPreferences

    // 1 = Language Focused, 2 = Scanning Mode Focused
    private var currentFocusState = 1

    // 2-finger double tap to exit
    private var lastTwoFingerTapTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Initialize Views
        moduleLanguageConfig = findViewById(R.id.moduleLanguageConfig)
        moduleScanConfig = findViewById(R.id.moduleScanConfig)
        tvSettingsSub = findViewById(R.id.tvSettingsSub)
        tvCurrentLanguageValue = findViewById(R.id.tvCurrentLanguageValue)
        tvCurrentScanValue = findViewById(R.id.tvCurrentScanValue)

        // Setup SharedPreferences
        sharedPreferences = getSharedPreferences("IrisSettings", Context.MODE_PRIVATE)
        loadSavedSettings()

        // Initialize Text-To-Speech
        tts = TextToSpeech(this, this)

        // Handle Back Button with modern Dispatcher
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                triggerExit()
            }
        })

        // Configure Gestures
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false

                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x

                // 1. VERTICAL SCROLL DETECTION
                if (abs(diffY) > abs(diffX)) {
                    if (abs(diffY) > 80 && abs(velocityY) > 80) {
                        if (diffY > 0) {
                            focusScanningWindow() // Downward scroll
                        } else {
                            focusLanguageWindow() // Upward scroll
                        }
                        return true
                    }
                }
                // 2. HORIZONTAL SWIPE DETECTION
                else if (abs(diffX) > 80 && abs(velocityX) > 80) {
                    if (diffX < 0) {
                        handleSwipeLeftAction()
                    } else {
                        handleSwipeRightAction()
                    }
                    return true
                }
                return false
            }
        })
    }

    private fun focusLanguageWindow() {
        currentFocusState = 1
        tvSettingsSub.text = "Focused: Language Configuration"
        
        // Highlight Language module
        moduleLanguageConfig.alpha = 1.0f
        moduleScanConfig.alpha = 0.4f
        
        speakOut("Language. Swipe left for English, swipe right for Filipino.")
    }

    private fun focusScanningWindow() {
        currentFocusState = 2
        tvSettingsSub.text = "Focused: Scanning Mode Options"
        
        // Highlight Scan module
        moduleLanguageConfig.alpha = 0.4f
        moduleScanConfig.alpha = 1.0f
        
        speakOut("Scanning Mode. Swipe left for Continuous, swipe right for Tap.")
    }

    private fun handleSwipeLeftAction() {
        if (currentFocusState == 1) {
            sharedPreferences.edit().putString("language", "English").apply()
            tvCurrentLanguageValue.text = "English"
            speakOut("Language set to English")
        } else if (currentFocusState == 2) {
            sharedPreferences.edit().putString("scan_mode", "Continuous").apply()
            tvCurrentScanValue.text = "Continuous"
            speakOut("Scanning mode set to Continuous")
        }
    }

    private fun handleSwipeRightAction() {
        if (currentFocusState == 1) {
            sharedPreferences.edit().putString("language", "Filipino").apply()
            tvCurrentLanguageValue.text = "Filipino"
            speakOut("Language set to Filipino")
        } else if (currentFocusState == 2) {
            sharedPreferences.edit().putString("scan_mode", "Tap to Scan").apply()
            tvCurrentScanValue.text = "Tap to Scan"
            speakOut("Scanning mode set to Tap to scan")
        }
    }

    private fun loadSavedSettings() {
        val savedLang = sharedPreferences.getString("language", "English")
        val savedMode = sharedPreferences.getString("scan_mode", "Continuous")
        tvCurrentLanguageValue.text = savedLang
        tvCurrentScanValue.text = savedMode
    }

    private fun speakOut(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(0.9f) // Set to a clear, normal speed (slightly slower than default for clarity)
            
            // Set listener to handle exit AFTER speech finishes
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "EXIT_SETTINGS") {
                        runOnUiThread {
                            finish()
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })

            speakOut("Settings. Scroll up or down to select a setting. Double tap with two fingers to exit.")
            // Use QUEUE_ADD to play instructions after the welcome message
            tts.speak("Language focused. Swipe left for English, swipe right for Filipino.", TextToSpeech.QUEUE_ADD, null, null)
            
            // Initialize UI state - both visible, language highlighted
            tvSettingsSub.text = "Focused: Language Configuration"
            moduleLanguageConfig.visibility = View.VISIBLE
            moduleLanguageConfig.alpha = 1.0f
            moduleScanConfig.visibility = View.VISIBLE
            moduleScanConfig.alpha = 0.4f
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        
        // Ignore 3-finger touches to prevent glitching
        if (event.pointerCount >= 3) {
            return true
        }

        // 2-Finger Double Tap to Exit
        if (event.pointerCount == 2 && action == MotionEvent.ACTION_POINTER_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastTwoFingerTapTime < 500) {
                triggerExit()
                vibrateFeedback(100)
            }
            lastTwoFingerTapTime = now
            return true
        }

        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    private fun vibrateFeedback(duration: Long) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(duration)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return super.onTouchEvent(event)
    }

    private fun triggerExit() {
        // Use utterance ID to trigger the listener's onDone callback
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "EXIT_SETTINGS")
        tts.speak("Exiting settings. Returning to camera.", TextToSpeech.QUEUE_FLUSH, params, "EXIT_SETTINGS")
    }

    override fun onStop() {
        super.onStop()
        if (::tts.isInitialized) tts.stop()
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}