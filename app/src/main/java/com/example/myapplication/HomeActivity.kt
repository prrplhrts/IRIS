package com.example.myapplication

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class HomeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private lateinit var homeRoot: View
    private lateinit var logoGroup: View
    private lateinit var irisLogo: ImageView
    private lateinit var logoGlow: View
    private lateinit var bottomPrompt: View
    private lateinit var toneGenerator: ToneGenerator
    private var breathingAnimator: ObjectAnimator? = null
    private var breathingGlowAnimator: ObjectAnimator? = null

    // Goodbye overlay views
    private lateinit var goodbyeOverlay: FrameLayout
    private lateinit var goodbyeLogo: ImageView
    private lateinit var goodbyeGlow: View
    private lateinit var goodbyeTitle: TextView
    private lateinit var goodbyeSubtitle: TextView
    private var isClosing = false

    // Double-tap detection
    private var lastTapTime = 0L
    private val DOUBLE_TAP_TIMEOUT = 400L

    // Long-press detection
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private val LONG_PRESS_DURATION = 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        homeRoot = findViewById(R.id.home_root)
        logoGroup = findViewById(R.id.logo_group)
        irisLogo = findViewById(R.id.iris_logo)
        logoGlow = findViewById(R.id.logo_glow)
        bottomPrompt = findViewById(R.id.bottom_prompt)

        // Goodbye overlay
        goodbyeOverlay = findViewById(R.id.goodbye_overlay)
        goodbyeLogo = findViewById(R.id.goodbye_logo)
        goodbyeGlow = findViewById(R.id.goodbye_glow)
        goodbyeTitle = findViewById(R.id.goodbye_title)
        goodbyeSubtitle = findViewById(R.id.goodbye_subtitle)

        startLogoBreathingAnimation()

        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

        tts = TextToSpeech(this, this)
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

        homeRoot.setOnTouchListener { _, event ->
            if (isClosing) return@setOnTouchListener true

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Start long-press timer
                    longPressRunnable = Runnable {
                        playGoodbyeSequence()
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION)

                    // Check for double-tap
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                        longPressHandler.removeCallbacks(longPressRunnable!!)
                        tts?.speak("Camera On. Welcome to IRIS!", TextToSpeech.QUEUE_FLUSH, null, "CLICK")
                        val intent = Intent(this@HomeActivity, MainActivity::class.java)
                        startActivity(intent)
                    }
                    lastTapTime = now
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Cancel long-press if finger lifted
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                }
            }
            true
        }
    }

    private fun playGoodbyeSequence() {
        if (isClosing) return
        isClosing = true

        // Speak the goodbye message
        tts?.speak("IRIS is now closing. Goodbye", TextToSpeech.QUEUE_FLUSH, null, "GOODBYE")

        // Set utterance listener to finish the app after TTS completes
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "GOODBYE") {
                    // Wait a beat after speech ends, then close
                    Handler(Looper.getMainLooper()).postDelayed({
                        finishAffinity()
                    }, 1200)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })

        lifecycleScope.launch {
            // Step 1: Fade out the home content
            runOnUiThread {
                logoGroup.animate().alpha(0f).setDuration(300).start()
                bottomPrompt.animate().alpha(0f).setDuration(300).start()
            }
            delay(350)

            // Step 2: Show the goodbye overlay (fade in the background)
            runOnUiThread {
                goodbyeOverlay.visibility = View.VISIBLE
                goodbyeOverlay.animate()
                    .alpha(1f)
                    .setDuration(400)
                    .start()
            }
            delay(300)

            // Step 3: Fade in the logo
            runOnUiThread {
                goodbyeLogo.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .start()
            }
            delay(200)

            // Step 4: Fade in the purple glow with a pulse
            runOnUiThread {
                goodbyeGlow.animate()
                    .alpha(1f)
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(600)
                    .start()

                // Start a gentle breathing pulse on the glow
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

            // Step 5: Fade in "Goodbye" title
            runOnUiThread {
                goodbyeTitle.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(500)
                    .start()
                // Start slightly below and slide up
                goodbyeTitle.translationY = 20f
            }
            delay(300)

            // Step 6: Fade in subtitle
            runOnUiThread {
                goodbyeSubtitle.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(500)
                    .start()
                goodbyeSubtitle.translationY = 15f
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.US)

            val startupMessage = "Welcome to IRIS. Would you like to activate the detection system? Double tap the screen to activate. Or long press the screen to close."
            tts?.speak(startupMessage, TextToSpeech.QUEUE_FLUSH, null, "STARTUP")

            // Show the bottom prompt after a short delay
            lifecycleScope.launch {
                delay(1500)
                runOnUiThread {
                    showBottomPrompt()
                }
            }

        } else {
            Log.e("TTS", "Initialization failed!")
        }
    }

    private fun startLogoBreathingAnimation() {
        breathingAnimator = ObjectAnimator.ofPropertyValuesHolder(
            irisLogo,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f)
        ).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }

        breathingGlowAnimator = ObjectAnimator.ofPropertyValuesHolder(
            logoGlow,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.15f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.15f),
            PropertyValuesHolder.ofFloat("alpha", 0.7f, 0.2f)
        ).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun showBottomPrompt() {
        bottomPrompt.animate()
            .alpha(1f)
            .setDuration(800)
            .start()
    }

    override fun onDestroy() {
        breathingAnimator?.cancel()
        breathingGlowAnimator?.cancel()
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
        toneGenerator.release()
        super.onDestroy()
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
        // QUEUE_FLUSH ensures the feedback is instant
        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "VOLUME_ID")

        // Play the beep
        toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100)
    }
}

