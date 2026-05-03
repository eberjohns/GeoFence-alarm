package com.example.geoalarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        // 1. Check if this is the Fake Test Button
        val isTest = intent.getBooleanExtra("IS_TEST_MODE", false)
        if (isTest) {
            Log.d("GeoAlarm", "TEST MODE DETECTED! FIRING ALARM!")
            fireFullScreenAlarm(context)
            return // Stop here, don't check for GPS
        }

        // 2. Otherwise, treat it as a real GPS Geofence event
        val geofencingEvent = GeofencingEvent.fromIntent(intent)

        if (geofencingEvent?.hasError() == true) {
            Log.e("GeoAlarm", "Error receiving geofence event")
            return
        }

        if (geofencingEvent?.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.d("GeoAlarm", "TARGET ZONE ENTERED! FIRING ALARM!")
            fireFullScreenAlarm(context)
        }
    }

    private fun fireFullScreenAlarm(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "ALARM_CHANNEL_ID"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Geofence Alarms", NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Wakes the screen when entering a zone"
            notificationManager.createNotificationChannel(channel)
        }

        // --- THE FIX: GENERATE A UNIQUE ID ---
        val uniqueId = System.currentTimeMillis().toInt()

        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // Pass the uniqueId here as the second parameter!
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, uniqueId,
            fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Location Reached!")
            .setContentText("You have entered the buffer zone.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)

        // Pass the uniqueId here as well!
        notificationManager.notify(uniqueId, notificationBuilder.build())
    }
}