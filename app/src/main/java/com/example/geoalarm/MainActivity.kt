package com.example.geoalarm

import android.Manifest
import android.app.PendingIntent
import android.content.Context // <-- THE MISSING IMPORT FIX
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import androidx.activity.result.IntentSenderRequest
import android.location.Geocoder
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.google.android.gms.maps.CameraUpdateFactory

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private var alarmLocation: LatLng? = null
    private val visualRadius = 100.0 // The map will show the 100m sniper zone
    private lateinit var btnSetAlarm: Button
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button

    private lateinit var geofencingClient: GeofencingClient
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
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
        btnSetAlarm = findViewById(R.id.btnSetAlarm)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        requestPermissions()

        btnSetAlarm.setOnClickListener {
            alarmLocation?.let { location ->

                // SAVE TARGET TO MEMORY FOR THE SNIPER SERVICE
                val prefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putFloat("TARGET_LAT", location.latitude.toFloat())
                    putFloat("TARGET_LNG", location.longitude.toFloat())
                    apply()
                }

                addGeofence(location) // Arm the 2km wide net

                // Update UX State
                btnSetAlarm.isEnabled = false
                btnSetAlarm.text = "SYSTEM ARMED"
                btnSetAlarm.setBackgroundColor(Color.parseColor("#1E88E5")) // Changes to a sleek locked-in Blue
                btnSetAlarm.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_secure, 0, 0, 0) // Adds a native lock icon
            }
        }

        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)

        // Trigger search when the GO button is tapped
        btnSearch.setOnClickListener {
            performSearch()
        }

        // Trigger search when the 'Enter'/'Search' key on the keyboard is pressed
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        checkLocationSettings()

        mMap.setOnMapLongClickListener { latLng ->
            setTargetLocation(latLng) // Now both clicking and searching do the exact same thing
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

    private fun performSearch() {
        val query = etSearch.text.toString()
        if (query.isEmpty()) return

        // Hide keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)

        try {
            val geocoder = Geocoder(this)
            val addresses = geocoder.getFromLocationName(query, 1)

            if (!addresses.isNullOrEmpty()) {
                val location = addresses[0]
                val latLng = LatLng(location.latitude, location.longitude)

                // Fly the camera to the searched location
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))

                // Drop the pin
                setTargetLocation(latLng)
            } else {
                Log.e("GeoAlarm", "Location not found")
            }
        } catch (e: Exception) {
            Log.e("GeoAlarm", "Geocoding failed: ${e.message}")
        }
    }

    private fun setTargetLocation(latLng: LatLng) {
        mMap.clear()
        alarmLocation = latLng

        mMap.addMarker(MarkerOptions().position(latLng).title("Sniper Trigger Zone"))
        mMap.addCircle(
            CircleOptions().center(latLng).radius(visualRadius)
                .strokeColor(Color.RED).fillColor(Color.argb(70, 255, 0, 0)).strokeWidth(5f)
        )

        btnSetAlarm.visibility = android.view.View.VISIBLE
        btnSetAlarm.isEnabled = true
        btnSetAlarm.text = "SET ALARM"
        btnSetAlarm.setBackgroundColor(Color.parseColor("#4CAF50"))
        btnSetAlarm.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
    }

    private fun checkLocationSettings() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client = LocationServices.getSettingsClient(this)

        client.checkLocationSettings(builder.build()).addOnSuccessListener {
            enableUserLocation()
        }.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution).build()
                    resolutionForResult.launch(intentSenderRequest)
                } catch (sendEx: Exception) {
                    Log.e("GeoAlarm", "Error showing location prompt", sendEx)
                }
            }
        }
    }

    private fun addGeofence(latLng: LatLng) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // 1. Tell the OS to forget all previous geofences and clear its location cache
        geofencingClient.removeGeofences(geofencePendingIntent).addOnCompleteListener {

            // 2. Generate a totally unique ID using the current millisecond
            val uniqueGeofenceId = "ALARM_ZONE_${System.currentTimeMillis()}"

            val geofence = Geofence.Builder()
                .setRequestId(uniqueGeofenceId) // <-- THE FIX: The OS treats this as a brand new place
                .setCircularRegion(latLng.latitude, latLng.longitude, 2000f)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()

            val geofencingRequest = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
                .addGeofence(geofence)
                .build()

            geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
                addOnSuccessListener {
                    Log.d("GeoAlarm", "2KM Wide Net Armed! System is watching.")
                }
                addOnFailureListener {
                    Log.e("GeoAlarm", "Failed to arm net: ${it.message}")
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