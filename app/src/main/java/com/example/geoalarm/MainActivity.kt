package com.example.geoalarm

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log       // <-- ADDED FOR LOGS
import android.widget.Button  // <-- ADDED FOR THE TEST BUTTON
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import androidx.activity.result.IntentSenderRequest

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private var alarmLocation: LatLng? = null
    private val alarmRadius = 1000.0 // 1km

    private lateinit var geofencingClient: GeofencingClient
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {

                // REROUTED: Now it checks if the hardware is on before turning on the blue dot!
                checkLocationSettings()

            } else {
                Log.e("GeoAlarm", "Location required for alarm")
            }
        }

    private val resolutionForResult =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d("GeoAlarm", "User enabled location services. Good to go!")
                enableUserLocation()
            } else {
                Log.e("GeoAlarm", "User refused to enable location services. App will not work.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geofencingClient = LocationServices.getGeofencingClient(this)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        requestPermissions()

        // The Cheat Button
        findViewById<Button>(R.id.btnTestAlarm).setOnClickListener {
            Log.d("GeoAlarm", "Lock your phone NOW! Alarm firing in 5 seconds...")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val testIntent = Intent(this, GeofenceBroadcastReceiver::class.java).apply {
                    putExtra("IS_TEST_MODE", true) // <-- ADD THIS CHEAT CODE
                }
                sendBroadcast(testIntent)
            }, 5000)
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun checkLocationSettings() {
        // Create a request for high accuracy GPS
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client = LocationServices.getSettingsClient(this)

        client.checkLocationSettings(builder.build()).addOnSuccessListener {
            // Success! The GPS is already turned on.
            enableUserLocation()
        }.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                // GPS is off, but we can ask Android to show the "Turn on Location" dialog
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution).build()
                    resolutionForResult.launch(intentSenderRequest)
                } catch (sendEx: Exception) {
                    Log.e("GeoAlarm", "Error showing location prompt", sendEx)
                }
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        checkLocationSettings()

        mMap.setOnMapLongClickListener { latLng ->
            mMap.clear()
            alarmLocation = latLng

            mMap.addMarker(MarkerOptions().position(latLng).title("Alarm Trigger Zone"))
            mMap.addCircle(
                CircleOptions().center(latLng).radius(alarmRadius)
                    .strokeColor(Color.RED).fillColor(Color.argb(70, 255, 0, 0)).strokeWidth(5f)
            )

            addGeofence(latLng)
        }
    }

    private fun addGeofence(latLng: LatLng) {
        val geofence = Geofence.Builder()
            .setRequestId("ALARM_ZONE_1")
            .setCircularRegion(latLng.latitude, latLng.longitude, alarmRadius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
                addOnSuccessListener {
                    // FIXED: Removed Toast, replaced with Log
                    Log.d("GeoAlarm", "Geofence Armed! System is watching.")
                }
                addOnFailureListener {
                    // FIXED: Removed Toast, replaced with Log
                    Log.e("GeoAlarm", "Failed to arm: ${it.message}")
                }
            }
        }
    }

    private fun enableUserLocation() {
        if (!::mMap.isInitialized) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
        }
    }
}