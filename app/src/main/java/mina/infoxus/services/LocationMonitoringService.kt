package mina.infoxus.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import mina.infoxus.R
import mina.infoxus.services.MainAccessibilityService
import mina.infoxus.data.LocationState
import mina.infoxus.data.SavedLocation
import mina.infoxus.ui.activity.MainActivity
import mina.infoxus.utils.GeofenceManager
import mina.infoxus.utils.LocationPreferencesManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class LocationMonitoringService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationPrefsManager: LocationPreferencesManager
    private lateinit var geofenceManager: GeofenceManager
    private var locationCallback: LocationCallback? = null

    private var currentLocationState: LocationState? = null
    private var isScreenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen turned off - pausing location updates for battery saving")
                    isScreenOn = false
                    stopLocationUpdates()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen turned on - resuming location updates")
                    isScreenOn = true
                    startLocationUpdates()
                }
            }
        }
    }

    companion object {
        private const val TAG = "LocationMonitoringService"
        private const val NOTIFICATION_ID = 5001
        private const val CHANNEL_ID = "location_monitoring_channel"
        private const val LOCATION_UPDATE_INTERVAL = 180000L 
        private const val LOCATION_FASTEST_INTERVAL = 90000L 
        private const val FAST_UPDATE_INTERVAL_AFTER_CONNECTIVITY = 30000L 
        private const val FAST_UPDATE_DURATION = 300000L 

        const val ACTION_START_MONITORING = "mina.infoxus.action.START_LOCATION_MONITORING"
        const val ACTION_STOP_MONITORING = "mina.infoxus.action.STOP_LOCATION_MONITORING"
    }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var fastUpdateStartTime: Long = 0
    private var isFastUpdateActive = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationPrefsManager = LocationPreferencesManager(this)
        geofenceManager = GeofenceManager(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)

        setupNetworkCallback()

        createNotificationChannel()
        
        // Listen for location provider changes (GPS on/off)
        val locationFilter = IntentFilter(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
        registerReceiver(locationProviderReceiver, locationFilter)
    }

    private val locationProviderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == android.location.LocationManager.PROVIDERS_CHANGED_ACTION) {
                checkLocationProviderStatus()
            }
        }
    }

    private fun checkLocationProviderStatus() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        
        if (!isGpsEnabled && !isNetworkEnabled) {
            Log.w(TAG, "Location providers were disabled, clearing inside zones")
            storeCurrentZones(emptyList())
            sendBroadcast(Intent(MainAccessibilityService.ACTION_REFRESH_APP_BLOCKER))
        } else {
            // Re-request updates if they were lost
            requestImmediateLocationUpdate()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                // Always check config fresh when starting
                val config = locationPrefsManager.getConfig()
                Log.d(TAG, "Received START_MONITORING action: enabled=${config.isEnabled}, locations=${config.locations.size}")
                if (config.isEnabled && config.locations.isNotEmpty()) {
                startLocationMonitoring()
                } else {
                    Log.w(TAG, "Location monitoring requested but not enabled or no locations, stopping service")
                    stopSelf()
                }
            }
            ACTION_STOP_MONITORING -> {
                stopLocationMonitoring()
                stopSelf()
            }
            else -> {
                // On service restart, check if monitoring should be active
                val config = locationPrefsManager.getConfig()
                if (config.isEnabled && config.locations.isNotEmpty()) {
                    Log.d(TAG, "Service restarted, resuming location monitoring")
                    startLocationMonitoring()
                } else {
                    Log.d(TAG, "Service restarted but monitoring not enabled, stopping")
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationMonitoring()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        try {
            unregisterReceiver(screenReceiver)
            unregisterReceiver(locationProviderReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers in onDestroy", e)
        }
        super.onDestroy()

        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
        Log.d(TAG, "Service destroyed")
    }

    private fun startLocationMonitoring() {
        Log.d(TAG, "Starting location monitoring")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting location FGS: ${e.message}")
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location FGS: ${e.message}")
        }

        geofenceManager.rebuildGeofences()

        startLocationUpdates()
    }

    private fun stopLocationMonitoring() {
        Log.d(TAG, "Stopping location monitoring")
        stopLocationUpdates()
    }

    private fun stopLocationUpdates() {

        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted")
            stopSelf()
            return
        }

        val config = locationPrefsManager.getConfig()
        val priority = if (config.useHighAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val updateInterval = if (isFastUpdateActive && 
            (System.currentTimeMillis() - fastUpdateStartTime) < FAST_UPDATE_DURATION) {
            FAST_UPDATE_INTERVAL_AFTER_CONNECTIVITY
        } else {
            LOCATION_UPDATE_INTERVAL
        }

        val fastestInterval = if (isFastUpdateActive) {
            FAST_UPDATE_INTERVAL_AFTER_CONNECTIVITY / 2
        } else {
            LOCATION_FASTEST_INTERVAL
        }

        val locationRequest = LocationRequest.Builder(priority, updateInterval)
            .setMinUpdateIntervalMillis(fastestInterval)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    handleLocationUpdate(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Location updates started with interval: ${updateInterval}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when requesting location updates", e)
            stopSelf()
        }
    }

    private fun checkConnectivityAndUpdateLocation() {
        if (!isScreenOn) {

            return
        }

        val isConnected = isNetworkAvailable()
        if (isConnected) {
            Log.d(TAG, "Connectivity restored - triggering immediate location update")

            isFastUpdateActive = true
            fastUpdateStartTime = System.currentTimeMillis()

            requestImmediateLocationUpdate()

            if (locationCallback != null) {
                stopLocationUpdates()
                startLocationUpdates()
            }
            // Also refresh immediately based on WiFi if possible
            refreshLocationStatusByNetwork()
        } else {
            Log.d(TAG, "No network connectivity available")
            isFastUpdateActive = false
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            connectivityManager?.let { cm ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val network = cm.activeNetwork
                    val capabilities = cm.getNetworkCapabilities(network)
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                     capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                } else {
                    @Suppress("DEPRECATION")
                    val activeNetworkInfo = cm.activeNetworkInfo
                    activeNetworkInfo != null && activeNetworkInfo.isConnected
                }
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network availability", e)
            false
        }
    }

    private fun requestImmediateLocationUpdate() {
        if (!hasLocationPermission()) {
            return
        }

        try {
            val lastLocation = fusedLocationClient.lastLocation
            lastLocation.addOnSuccessListener { location ->
                location?.let {
                    Log.d(TAG, "Got immediate location update: ${it.latitude}, ${it.longitude}")
                    handleLocationUpdate(it)
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to get immediate location update", e)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when requesting immediate location", e)
        }
    }

    private fun setupNetworkCallback() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available - triggering immediate location update")
                checkConnectivityAndUpdateLocation()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                isFastUpdateActive = false
                requestImmediateLocationUpdate()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    val hasWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val hasCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

                    if (hasWifi || hasCellular) {
                        Log.d(TAG, "Network capabilities changed - WiFi/Cellular connectivity available")
                        checkConnectivityAndUpdateLocation()
                    }
                }
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.d(TAG, "Network callback registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback", e)
        }
    }

    private fun handleLocationUpdate(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        val locations = locationPrefsManager.getEnabledLocations()
        val currentSsid = getCurrentWifiSsid()

        val insideZones = locations.filter { savedLocation ->
            isInsideGeofence(latLng, savedLocation) || 
            (savedLocation.wifiSsid != null && savedLocation.wifiSsid == currentSsid)
        }

        currentLocationState = LocationState(
            currentLatLng = latLng,
            insideZones = insideZones,
            lastUpdateTime = System.currentTimeMillis(),
            accuracy = location.accuracy
        )

        storeCurrentZones(insideZones)

        updateNotification()

        sendBroadcast(Intent(MainAccessibilityService.ACTION_REFRESH_APP_BLOCKER))

        Log.d(TAG, "Location updated: $latLng, Inside ${insideZones.size} zones, Accuracy: ${location.accuracy}m")
    }

    private fun storeCurrentZones(zones: List<SavedLocation>) {
        val prefs = getSharedPreferences("location_blocker", MODE_PRIVATE)
        val gson = com.google.gson.Gson()
        val zoneIds = zones.map { it.id }
        val json = gson.toJson(zoneIds)
        prefs.edit().putString("current_inside_zones", json).apply()
    }

    private fun isInsideGeofence(point: LatLng, location: SavedLocation): Boolean {
        val distance = calculateDistance(
            point.latitude,
            point.longitude,
            location.latitude,
            location.longitude
        )
        return distance <= location.radius
    }

    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val earthRadius = 6371000.0 
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)

        val a = sin(dLat / 2).pow(2) + 
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return (earthRadius * c).toFloat()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Monitoring",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Monitors system state"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val state = currentLocationState
        val contentText = if (state != null && state.isInsideAnyZone()) {
            "Active"
        } else {
            "System is protected"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(null)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshLocationStatusByNetwork() {
        val currentSsid = getCurrentWifiSsid()
        if (currentSsid == null) return

        val locations = locationPrefsManager.getEnabledLocations()
        val wifiMatchingZones = locations.filter { it.wifiSsid != null && it.wifiSsid == currentSsid }
        
        if (wifiMatchingZones.isNotEmpty()) {
            Log.d(TAG, "WiFi match found: $currentSsid. Entering ${wifiMatchingZones.size} zones.")
            
            // If we are already in some zones, merge them. 
            // But usually WiFi is more reliable for "Home" than GPS if GPS is locked/bad.
            val currentZones = currentLocationState?.insideZones ?: emptyList()
            val allZones = (currentZones + wifiMatchingZones).distinctBy { it.id }
            
            currentLocationState = LocationState(
                currentLatLng = currentLocationState?.currentLatLng,
                insideZones = allZones,
                lastUpdateTime = System.currentTimeMillis(),
                accuracy = currentLocationState?.accuracy ?: 0f
            )
            
            storeCurrentZones(allZones)
            updateNotification()
            sendBroadcast(Intent(MainAccessibilityService.ACTION_REFRESH_APP_BLOCKER))
        }
    }

    private fun getCurrentWifiSsid(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            if (connectionInfo != null && connectionInfo.networkId != -1) {
                val ssid = connectionInfo.ssid
                if (ssid != null && ssid != "<unknown ssid>") {
                    return ssid.removePrefix("\"").removeSuffix("\"")
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WiFi SSID", e)
            null
        }
    }

    fun getCurrentLocationState(): LocationState? {
        return currentLocationState
    }
}

