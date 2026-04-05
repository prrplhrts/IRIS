package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.lottie.LottieAnimationView
import java.util.Locale

class HomeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private lateinit var startOrbButton: LottieAnimationView

    companion object {
        private var hasWarnedBatteryLow = false
    }

    // 1. The Background Battery Monitor (For when the app is already running)
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = (level * 100) / scale.toFloat()

            if (batteryPct <= 20) {
                if (!hasWarnedBatteryLow) {
                    // Use QUEUE_ADD here so it doesn't interrupt anything currently speaking
                    tts?.speak("Warning. Battery is low. Currently at ${batteryPct.toInt()} percent.", TextToSpeech.QUEUE_ADD, null, "BATT_WARN")
                    hasWarnedBatteryLow = true
                }
            } else {
                hasWarnedBatteryLow = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        startOrbButton = findViewById(R.id.start_orb_button)

        // 2. Initialize TTS. We DO NOT register the battery receiver yet!
        tts = TextToSpeech(this, this)

        startOrbButton.setOnClickListener {
            // QUEUE_FLUSH will immediately stop the welcome message if the user clicks early
            tts?.speak("Camera On. Welcome to iris!", TextToSpeech.QUEUE_FLUSH, null, "CLICK")
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
    }

    // 3. This runs the moment the voice engine is fully warmed up and ready
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.US)

            // Synchronously check the battery state ONCE before speaking
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = (level * 100) / scale.toFloat()

            // Build a smart, combined startup message
            var startupMessage = "IRIS Navigation ready. Tap the center to begin."

            if (batteryPct <= 20 && !hasWarnedBatteryLow) {
                startupMessage = "IRIS Navigation ready. Tap the center to begin. Warning: Battery is low at ${batteryPct.toInt()} percent."
                hasWarnedBatteryLow = true
            }

            // Speak the combined message seamlessly
            tts?.speak(startupMessage, TextToSpeech.QUEUE_FLUSH, null, "STARTUP")

            // 4. NOW that the startup is handled, turn on the background receiver
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        } else {
            Log.e("TTS", "Initialization failed!")
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.e("BatteryReceiver", "Receiver already unregistered.")
        }

        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
        super.onDestroy()
    }
}