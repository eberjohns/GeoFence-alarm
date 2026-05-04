package com.example.geoalarm

import android.app.KeyguardManager
import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class AlarmActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null

    // NEW: The Handler to control the 10-second loop
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var beepRunnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_alarm)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancelAll()

        // NEW: Force Audio through the hardware ALARM channel
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) // Using actual alarm sound
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM) // Bypasses silent/vibrate mode
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(applicationContext, soundUri)
            prepare()
        }

        // The loop logic remains the same
        beepRunnable = Runnable {
            mediaPlayer?.start()
            handler.postDelayed(beepRunnable, 10000)
        }

        handler.post(beepRunnable)

        findViewById<Button>(R.id.btnStopAlarm).setOnClickListener {
            handler.removeCallbacks(beepRunnable) // Kill the loop
            mediaPlayer?.stop()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(beepRunnable) // Ensure the loop dies if the app is force-closed
        mediaPlayer?.release()
    }
}