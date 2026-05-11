package com.example.geoalarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.content.IntentFilter
import android.location.LocationManager

class RadarService : Service() {
    private val locationStateReceiver = LocationStateReceiver()

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Dynamically listen for GPS toggles while radar is active
        registerReceiver(locationStateReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        startActiveRadar()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "RADAR_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Active Radar", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("GeoAlarm Tracking")
            .setContentText("Approaching destination...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()

        startForeground(1, notification)
    }

    private fun fireFullScreenAlarm() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "ALARM_BYPASS_CHANNEL"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Alarm Triggers", NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val uniqueId = System.currentTimeMillis().toInt()
        val fullScreenIntent = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, uniqueId,
            fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Destination Reached!")
            .setContentText("Wake up!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true) // THIS is what bypasses the block

        notificationManager.notify(uniqueId, notificationBuilder.build())
    }

    private fun startActiveRadar() {
        // Read coordinates
        val prefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        val targetLat = prefs.getFloat("TARGET_LAT", 0f).toDouble()
        val targetLng = prefs.getFloat("TARGET_LNG", 0f).toDouble()

        // READ THE SLIDER RADIUS: Fetch the dynamic value we saved in MainActivity (defaults to 500m)
        val alarmPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val dynamicTriggerRadius = alarmPrefs.getFloat("TARGET_RADIUS", 500f).toDouble()

        if (targetLat == 0.0) { stopSelf(); return }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    val results = FloatArray(1)
                    android.location.Location.distanceBetween(
                        location.latitude, location.longitude,
                        targetLat, targetLng, results
                    )

                    val distance = results[0]
                    Log.d("RadarService", "Distance: ${distance.toInt()}m | Trigger at: ${dynamicTriggerRadius.toInt()}m")

                    // THE FIX: Trigger when distance breaches the slider's radius
                    if (distance <= dynamicTriggerRadius) {
                        Log.e("RadarService", "${dynamicTriggerRadius.toInt()}M RADIUS BREACHED! FIRING ALARM!")

                        // 1. Fire the Full-Screen Intent Bypass
                        fireFullScreenAlarm()

                        // 2. Kill the Radar to save battery
                        fusedLocationClient.removeLocationUpdates(this)
                        stopForeground(STOP_FOREGROUND_REMOVE) // Updated for newer Android versions
                        stopSelf()
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        // Clean up the receiver
        unregisterReceiver(locationStateReceiver)
    }
}