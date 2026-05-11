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
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import android.widget.Toast
import com.google.android.material.slider.Slider
import android.widget.TextView
import android.view.View
import com.google.android.gms.maps.model.Circle
import android.content.IntentFilter
import android.location.LocationManager

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private var alarmLocation: LatLng? = null
    private val visualRadius = 500.0 // The map will show the 500m sniper zone

    private lateinit var tvRadiusLabel: TextView
    private lateinit var radiusSlider: Slider
    private lateinit var radiusCard: View

    private var currentRadius = 500.0 // Replaces your hardcoded visualRadius
    private var mapCircle: Circle? = null // Keeps track of the red circle shape
    private lateinit var btnSetAlarm: Button
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button

    // This creates the Geocoder once, and reuses it forever to save memory
    private val geocoder by lazy { Geocoder(this) }

    private var isSystemArmed = false

    private val locationStateReceiver = LocationStateReceiver()

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
            if (isSystemArmed) {
                cancelAlarm() // If armed, pressing it acts as a kill switch
            } else {
                // Safely unwrap the nullable coordinate
                alarmLocation?.let { target ->
                    addGeofence(target) // If disarmed, pressing it sets the alarm
                }
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

        tvRadiusLabel = findViewById(R.id.tvRadiusLabel)
        radiusSlider = findViewById(R.id.radiusSlider)
        radiusCard = findViewById(R.id.radiusCard)

        // Listen for the user dragging the slider
        radiusSlider.addOnChangeListener { _, value, _ ->
            currentRadius = value.toDouble()

            // Format the text nicely (e.g., "Trigger Radius: 1.5km" or "500m")
            if (currentRadius >= 1000) {
                tvRadiusLabel.text = "Trigger Radius: ${currentRadius / 1000}km"
            } else {
                tvRadiusLabel.text = "Trigger Radius: ${currentRadius.toInt()}m"
            }

            // Animate the red circle expanding/contracting on the map in real-time
            mapCircle?.radius = currentRadius
        }

        // Dynamically listen for GPS toggles while the UI is alive
        registerReceiver(locationStateReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(locationStateReceiver) // Clean up to prevent memory leaks
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        checkLocationSettings()

        mMap.setOnMapLongClickListener { latLng ->
            setTargetLocation(latLng) // Now both clicking and searching do the exact same thing
        }

        handleSharedIntent(intent)

        // Check if we need to restore a running alarm
        restoreArmedState()
    }

    // This fires when the app is already open in the background,
    // and you share a NEW link to it from Google Maps.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Replace the old intent with the new one

        // Clear the old pin and search for the new one!
        handleSharedIntent(intent)
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

    private fun cancelAlarm() {
        radiusCard.visibility = View.GONE

        // Tell the memory the system is disarmed
        val alarmPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        alarmPrefs.edit().putBoolean("IS_SYSTEM_ARMED", false).apply()

        // 1. Tell Android OS to delete the 2km wide net
        geofencingClient.removeGeofences(geofencePendingIntent)

        // 2. Kill the Sniper Radar background service instantly
        val serviceIntent = Intent(this, RadarService::class.java)
        stopService(serviceIntent)

        // 3. Reset the system state
        isSystemArmed = false
        mMap.clear() // Wipes the red circles and pins off the map

        // Hide the button until they drop a new pin
        btnSetAlarm.visibility = android.view.View.GONE

        Toast.makeText(this, "Alarm Cancelled & System Disarmed", Toast.LENGTH_SHORT).show()
    }

    private fun setTargetLocation(latLng: LatLng) {
        if (isSystemArmed) {
            cancelAlarm()
        }

        mMap.clear()
        alarmLocation = latLng

        mMap.addMarker(MarkerOptions().position(latLng).title("Sniper Trigger Zone"))

        // Save the circle to the variable so the slider can resize it
        mapCircle = mMap.addCircle(
            CircleOptions().center(latLng).radius(currentRadius)
                .strokeColor(Color.RED).fillColor(Color.argb(70, 255, 0, 0)).strokeWidth(5f)
        )

        // Show the controls
        radiusCard.visibility = View.VISIBLE
        btnSetAlarm.visibility = View.VISIBLE

        btnSetAlarm.visibility = android.view.View.VISIBLE
        btnSetAlarm.isEnabled = true
        btnSetAlarm.text = "SET ALARM"
        btnSetAlarm.setBackgroundColor(Color.parseColor("#4CAF50"))
        btnSetAlarm.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
    }

    private fun restoreArmedState() {
        val alarmPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val currentlyArmed = alarmPrefs.getBoolean("IS_SYSTEM_ARMED", false)

        if (currentlyArmed) {
            // 1. Tell the UI variable we are armed
            isSystemArmed = true

            // 2. Fetch the saved coordinates and radius
            val geoPrefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
            val savedLat = geoPrefs.getFloat("TARGET_LAT", 0f).toDouble()
            val savedLng = geoPrefs.getFloat("TARGET_LNG", 0f).toDouble()
            currentRadius = alarmPrefs.getFloat("TARGET_RADIUS", 500f).toDouble()

            if (savedLat != 0.0 && savedLng != 0.0) {
                val savedLatLng = LatLng(savedLat, savedLng)
                alarmLocation = savedLatLng

                // 3. Rebuild the Map visuals
                mMap.clear()
                mMap.addMarker(MarkerOptions().position(savedLatLng).title("Sniper Trigger Zone"))
                mapCircle = mMap.addCircle(
                    CircleOptions().center(savedLatLng).radius(currentRadius)
                        .strokeColor(Color.RED).fillColor(Color.argb(70, 255, 0, 0)).strokeWidth(5f)
                )

                // Move the camera to the saved location
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(savedLatLng, 15f))

                // 4. Restore the Slider UI
                radiusCard.visibility = View.VISIBLE
                radiusSlider.value = currentRadius.toFloat()
                if (currentRadius >= 1000) {
                    tvRadiusLabel.text = "Trigger Radius: ${currentRadius / 1000}km"
                } else {
                    tvRadiusLabel.text = "Trigger Radius: ${currentRadius.toInt()}m"
                }

                // 5. Restore the Red Kill Switch Button
                btnSetAlarm.visibility = View.VISIBLE
                btnSetAlarm.isEnabled = true
                btnSetAlarm.text = "CANCEL ALARM"
                btnSetAlarm.setBackgroundColor(Color.parseColor("#F44336"))
                btnSetAlarm.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_close_clear_cancel, 0, 0, 0)

                Log.d("GeoAlarm", "UI Restored! Successfully synced with background Radar.")
            }
        }
    }

    private fun handleSharedIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return

            val urlMatcher = Pattern.compile("https?://\\S+").matcher(sharedText)
            if (urlMatcher.find()) {
                val shortUrl = urlMatcher.group()

                Thread {
                    try {
                        var currentUrl = shortUrl
                        var htmlContent = ""
                        var redirects = 0

                        // 1. Manually hop through Google's redirects to catch the true expanded URL
                        while (redirects < 5) {
                            val connection = URL(currentUrl).openConnection() as HttpURLConnection
                            connection.setRequestProperty(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                            // WE control the redirects now, not Java
                            connection.instanceFollowRedirects = false
                            connection.connect()

                            val responseCode = connection.responseCode
                            if (responseCode in 300..399) {
                                // Catch the redirect destination
                                val location = connection.getHeaderField("Location")
                                if (location != null) {
                                    currentUrl = location
                                    redirects++
                                    connection.disconnect()
                                    continue
                                }
                            }

                            // If we hit the final page (200 OK), grab the HTML
                            if (responseCode == 200) {
                                htmlContent = connection.inputStream.bufferedReader().readText()
                            }
                            connection.disconnect()
                            break
                        }

                        Log.d("GeoAlarm", "Final Unfurled URL: $currentUrl")

                        var targetLat: Double? = null
                        var targetLng: Double? = null

                        // STRATEGY 1: Search the unfurled URL for ALL known Google Maps coordinate patterns
                        val urlRegexes = listOf(
                            Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"), // Matches !3d... !4d...
                            Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),     // Matches @lat,lng
                            Regex("[?&](?:q|query|ll)=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)") // Matches ?q=lat,lng
                        )

                        for (regex in urlRegexes) {
                            val match = regex.find(currentUrl)
                            if (match != null) {
                                targetLat = match.groupValues[1].toDouble()
                                targetLng = match.groupValues[2].toDouble()
                                Log.d("GeoAlarm", "Extracted from URL Pattern: $targetLat, $targetLng")
                                break
                            }
                        }

                        // STRATEGY 2: Deep HTML Parsing (Attribute-Order Agnostic)
                        if (targetLat == null) {
                            val deepLinkRegex = Regex("android-app://com\\.google\\.android\\.apps\\.maps/geo/0,0\\?q=(?:.*?%40|.*?@)?(-?\\d+\\.\\d+)[,%2C]+(-?\\d+\\.\\d+)")
                            val markerRegex = Regex("markers=(?:.*?%7C|.*?\\|)?(-?\\d+\\.\\d+)(?:%2C|,)(-?\\d+\\.\\d+)")

                            // These regexes now catch the tags no matter what order Google puts the attributes in
                            val metaLatRegex = Regex("content=[\"'](-?\\d+\\.\\d+)[\"']\\s*itemprop=[\"']latitude[\"']|itemprop=[\"']latitude[\"']\\s*content=[\"'](-?\\d+\\.\\d+)[\"']")
                            val metaLngRegex = Regex("content=[\"'](-?\\d+\\.\\d+)[\"']\\s*itemprop=[\"']longitude[\"']|itemprop=[\"']longitude[\"']\\s*content=[\"'](-?\\d+\\.\\d+)[\"']")

                            val deepLinkMatch = deepLinkRegex.find(htmlContent)
                            val markerMatch = markerRegex.find(htmlContent)
                            val latMatch = metaLatRegex.find(htmlContent)
                            val lngMatch = metaLngRegex.find(htmlContent)

                            if (deepLinkMatch != null) {
                                targetLat = deepLinkMatch.groupValues[1].toDouble()
                                targetLng = deepLinkMatch.groupValues[2].toDouble()
                                Log.d("GeoAlarm", "Extracted from Android Deep Link")
                            } else if (markerMatch != null) {
                                targetLat = markerMatch.groupValues[1].toDouble()
                                targetLng = markerMatch.groupValues[2].toDouble()
                                Log.d("GeoAlarm", "Extracted from Pin Marker")
                            } else if (latMatch != null && lngMatch != null) {
                                // Extract whichever regex group caught the number
                                val latStr = if (latMatch.groupValues[1].isNotEmpty()) latMatch.groupValues[1] else latMatch.groupValues[2]
                                val lngStr = if (lngMatch.groupValues[1].isNotEmpty()) lngMatch.groupValues[1] else lngMatch.groupValues[2]
                                targetLat = latStr.toDouble()
                                targetLng = lngStr.toDouble()
                                Log.d("GeoAlarm", "Extracted from HTML Meta Tags")
                            }
                        }

                        // STRATEGY 3: The Dropped Pin & Full-Address Geocoder Fallback
                        if (targetLat == null) {
                            val placeRegex = Regex("/place/([^/]+)/")
                            val placeMatch = placeRegex.find(currentUrl)

                            if (placeMatch != null) {
                                val rawPlace = placeMatch.groupValues[1]

                                // Scenario A: It's a raw dropped pin (e.g., /place/10.123,76.123/)
                                val coordMatch = Regex("^(-?\\d+\\.\\d+)(?:%2C|,)(-?\\d+\\.\\d+)$").find(rawPlace)
                                if (coordMatch != null) {
                                    targetLat = coordMatch.groupValues[1].toDouble()
                                    targetLng = coordMatch.groupValues[2].toDouble()
                                    Log.d("GeoAlarm", "Extracted from Dropped Pin URL")
                                } else {
                                    // Scenario B: It's a massive, exact address string. Let the OS translate it.
                                    val cleanAddress = java.net.URLDecoder.decode(rawPlace.replace("+", " "), "UTF-8")
                                    Log.d("GeoAlarm", "Attempting Geocoder with Full Address: $cleanAddress")

                                    val addresses = geocoder.getFromLocationName(cleanAddress, 1)
                                    if (!addresses.isNullOrEmpty()) {
                                        targetLat = addresses[0].latitude
                                        targetLng = addresses[0].longitude
                                        Log.d("GeoAlarm", "Extracted via Geocoder Exact Address")
                                    }
                                }
                            }
                        }

                        // FINALE: Update the UI
                        if (targetLat != null && targetLng != null) {
                            val sharedLatLng = LatLng(targetLat, targetLng)
                            runOnUiThread {
                                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(sharedLatLng, 15f))
                                setTargetLocation(sharedLatLng)
                            }
                        } else {
                            Log.e("GeoAlarm", "ALL EXTRACTIONS FAILED. URL: $currentUrl")
                        }

                    } catch (e: Exception) {
                        Log.e("GeoAlarm", "Scraper crashed", e)
                    }
                }.start()
            }
        }
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

        // 1. Save the exact slider value to the phone's memory
        val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putFloat("TARGET_RADIUS", currentRadius.toFloat())
            .putBoolean("IS_SYSTEM_ARMED", true)
            .apply()

        // ADD THIS: Save the coordinates so the RadarService knows where the center of the circle is!
        val geoPrefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        geoPrefs.edit()
            .putFloat("TARGET_LAT", latLng.latitude.toFloat())
            .putFloat("TARGET_LNG", latLng.longitude.toFloat())
            .apply()

        // 2. Tell the OS to forget all previous geofences
        geofencingClient.removeGeofences(geofencePendingIntent).addOnCompleteListener {

            val uniqueGeofenceId = "ALARM_ZONE_${System.currentTimeMillis()}"

            // 3. THE MATH FIX: Slider Radius + 2000m Wake-Up Buffer
            val tripwireRadius = currentRadius.toFloat() + 2000f

            val geofence = Geofence.Builder()
                .setRequestId(uniqueGeofenceId)
                .setCircularRegion(latLng.latitude, latLng.longitude, tripwireRadius) // Uses the massive tripwire
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

                    // Transform the button into the Kill Switch
                    isSystemArmed = true
                    btnSetAlarm.isEnabled = true
                    btnSetAlarm.text = "CANCEL ALARM"
                    btnSetAlarm.setBackgroundColor(Color.parseColor("#F44336")) // Alert Red
                    btnSetAlarm.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_close_clear_cancel, 0, 0, 0)
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