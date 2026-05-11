package com.example.geoalarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

class LocationStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            val isLocationOn = isGpsEnabled || isNetworkEnabled

            // Check if the system is actually armed before yelling at the user
            val sharedPrefs = context.getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
            val isArmed = sharedPrefs.getBoolean("IS_SYSTEM_ARMED", false)

            if (isArmed && !isLocationOn) {
                Log.e("GeoAlarm", "SABOTAGE DETECTED: Location turned off while armed!")
                triggerSabotageWarning(context)
            } else if (isArmed && isLocationOn) {
                Log.d("GeoAlarm", "Sabotage resolved. Location restored.")
                cancelSabotageWarning(context)
            }
        }
    }

    private fun triggerSabotageWarning(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "SABOTAGE_CHANNEL"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Critical Warnings", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Warns if GPS is disabled while alarm is active"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Tapping the notification takes them directly to the main app to fix it
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CRITICAL: Alarm Failing")
            .setContentText("You turned off location. The alarm WILL NOT ring. Turn on the location service or Tap to fix.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOngoing(true) // Makes it impossible to swipe away
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(999, notification)
    }

    private fun cancelSabotageWarning(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(999)
    }
}