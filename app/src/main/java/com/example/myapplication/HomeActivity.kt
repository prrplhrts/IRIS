package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class HomeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private lateinit var startOrbButton: LottieAnimationView
    private lateinit var toneGenerator: ToneGenerator


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        startOrbButton = findViewById(R.id.start_orb_button)


        // 2. Initialize TTS. We DO NOT register the battery receiver yet!
        tts = TextToSpeech(this, this)
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

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

            val startupMessage = "IRIS Navigation ready. Tap the center to begin."
            tts?.speak(startupMessage, TextToSpeech.QUEUE_FLUSH, null, "STARTUP")

        } else {
            Log.e("TTS", "Initialization failed!")
        }
    }



    override fun onDestroy() {
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
        toneGenerator.release()
        super.onDestroy()
    }
}