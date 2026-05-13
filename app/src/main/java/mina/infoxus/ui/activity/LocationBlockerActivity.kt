package mina.infoxus.ui.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import mina.infoxus.R
import mina.infoxus.data.LocationMode
import mina.infoxus.data.SavedLocation
import mina.infoxus.databinding.ActivityLocationBlockerBinding
import mina.infoxus.databinding.DialogAddLocationBinding
import mina.infoxus.databinding.ItemLocationBinding
import mina.infoxus.services.LocationMonitoringService
import mina.infoxus.services.MainAccessibilityService
import mina.infoxus.utils.GeofenceManager
import mina.infoxus.utils.LocationPreferencesManager

class LocationBlockerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocationBlockerBinding
    private lateinit var locationPrefsManager: LocationPreferencesManager
    private lateinit var geofenceManager: GeofenceManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationsAdapter: LocationsAdapter
    private var isAntiUninstallEnabled = false

    private var selectedAppsForNewLocation = mutableSetOf<String>()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            checkPermissions()
            if (binding.switchEnableLocationBlocking.isChecked) {
                startLocationMonitoring()
            }
        } else {
            Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            selectedApps?.let {
                val updatedApps = it.toMutableSet()
                
                val currentEditingId = editingLocationId
                if (currentEditingId != null) {
                    val location = locationPrefsManager.getLocations().find { it.id == currentEditingId }
                    if (location != null) {
                        if (isAntiUninstallEnabled && !updatedApps.containsAll(location.blockedApps)) {
                            Toast.makeText(this, "Removing apps from a location is disabled by anti-uninstall", Toast.LENGTH_SHORT).show()
                        } else {
                            val updatedLocation = location.copy(blockedApps = updatedApps)
                            locationPrefsManager.saveLocation(updatedLocation)
                            geofenceManager.addGeofence(updatedLocation)
                            loadLocations()
                            Toast.makeText(this, "Location apps updated", Toast.LENGTH_SHORT).show()
                        }
                    }
                    editingLocationId = null
                } else {
                    selectedAppsForNewLocation = updatedApps
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationBlockerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationPrefsManager = LocationPreferencesManager(this)
        geofenceManager = GeofenceManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val antiUninstallInfo = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
        isAntiUninstallEnabled = antiUninstallInfo.getBoolean("is_anti_uninstall_on", false) && 
                                 antiUninstallInfo.getBoolean("is_configuring_blocked", false)

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        checkPermissions()
        loadConfiguration()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        locationsAdapter = LocationsAdapter(
            onLocationClick = { location -> showEditLocationDialog(location) },
            onToggleEnabled = { location -> toggleLocationEnabled(location) },
            onDeleteClick = { location -> confirmDeleteLocation(location) }
        )

        binding.recyclerLocations.apply {
            layoutManager = LinearLayoutManager(this@LocationBlockerActivity)
            adapter = locationsAdapter
        }
    }

    private fun setupListeners() {
        binding.switchEnableLocationBlocking.setOnCheckedChangeListener { _, isChecked ->
            if (isAntiUninstallEnabled && !isChecked) {
                Toast.makeText(this, "Deactivating location blocking is disabled by anti-uninstall", Toast.LENGTH_SHORT).show()
                binding.switchEnableLocationBlocking.isChecked = true
                return@setOnCheckedChangeListener
            }
            locationPrefsManager.setEnabled(isChecked)
            if (isChecked) {
                if (hasLocationPermission()) {
                    startLocationMonitoring()
                } else {
                    requestLocationPermission()
                    binding.switchEnableLocationBlocking.isChecked = false
                }
            } else {
                stopLocationMonitoring()
            }
            updateStatusCard(isChecked)
        }

        binding.switchAutoControlFocusMode.setOnCheckedChangeListener { _, isChecked ->
            if (isAntiUninstallEnabled && !isChecked) {
                Toast.makeText(this, "Deactivating auto-control is disabled by anti-uninstall", Toast.LENGTH_SHORT).show()
                binding.switchAutoControlFocusMode.isChecked = true
                return@setOnCheckedChangeListener
            }
            locationPrefsManager.setAutoControlFocusMode(isChecked)

            val intent = Intent(MainAccessibilityService.ACTION_REFRESH_APP_BLOCKER)
            sendBroadcast(intent)
        }

        binding.radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
            if (isAntiUninstallEnabled) {
                // If the user tries to change mode, maybe we should prevent it?
                // The request says "can't delete location", but doesn't mention mode.
                // However, changing mode from "Block" to "Disable" is a form of bypass.
                val config = locationPrefsManager.getConfig()
                val targetMode = when (checkedId) {
                    R.id.radio_enable_in_zones -> LocationMode.ENABLE_IN_ZONES
                    R.id.radio_disable_in_zones -> LocationMode.DISABLE_IN_ZONES
                    else -> LocationMode.BLOCK_IN_ZONES
                }
                
                // If current mode is restrictive (BLOCK or ENABLE) and target is non-restrictive (DISABLE), 
                // we might want to block it. 
                // But the user didn't explicitly ask for this. I'll leave it for now but keep an eye.
            }
            val mode = when (checkedId) {
                R.id.radio_enable_in_zones -> LocationMode.ENABLE_IN_ZONES
                R.id.radio_disable_in_zones -> LocationMode.DISABLE_IN_ZONES
                else -> LocationMode.BLOCK_IN_ZONES
            }
            locationPrefsManager.setGlobalMode(mode)
        }

        binding.btnAddLocation.setOnClickListener {
            showAddLocationDialog()
        }

        binding.btnRequestPermission.setOnClickListener {
            requestLocationPermission()
        }
    }

    private fun loadConfiguration() {
        val config = locationPrefsManager.getConfig()

        binding.switchEnableLocationBlocking.isChecked = config.isEnabled
        binding.switchAutoControlFocusMode.isChecked = config.autoControlFocusMode

        when (config.globalMode) {
            LocationMode.BLOCK_IN_ZONES -> binding.radioBlockInZones.isChecked = true
            LocationMode.ENABLE_IN_ZONES -> binding.radioEnableInZones.isChecked = true
            LocationMode.DISABLE_IN_ZONES -> binding.radioDisableInZones.isChecked = true
        }

        loadLocations()
        updateStatusCard(config.isEnabled)
    }

    private fun loadLocations() {
        val locations = locationPrefsManager.getLocations()
        locationsAdapter.submitList(locations)

        if (locations.isEmpty()) {
            binding.tvEmptyLocations.visibility = View.VISIBLE
            binding.recyclerLocations.visibility = View.GONE
        } else {
            binding.tvEmptyLocations.visibility = View.GONE
            binding.recyclerLocations.visibility = View.VISIBLE
        }
    }

    private fun checkPermissions() {
        val hasPermission = hasLocationPermission()
        binding.permissionWarningCard.visibility = if (hasPermission) View.GONE else View.VISIBLE
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun showAddLocationDialog() {
        val dialogBinding = DialogAddLocationBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.sliderRadius.addOnChangeListener { _, value, _ ->
            dialogBinding.tvRadiusValue.text = "${value.toInt()} meters"
        }

        dialogBinding.btnGetCurrentLocation.setOnClickListener {
            getCurrentLocation { location ->
                dialogBinding.etLatitude.setText(location.latitude.toString())
                dialogBinding.etLongitude.setText(location.longitude.toString())
            }
        }

        dialogBinding.btnGetCurrentWifi.setOnClickListener {
            val ssid = getCurrentWifiSsid()
            if (ssid != null) {
                dialogBinding.etWifiSsid.setText(ssid)
            } else {
                Toast.makeText(this, "Unable to get current WiFi SSID. Ensure WiFi is connected and location is enabled.", Toast.LENGTH_LONG).show()
            }
        }

        dialogBinding.btnSelectAppsToBlock.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(selectedAppsForNewLocation)
            )
            selectAppsLauncher.launch(intent)
        }

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSave.setOnClickListener {
            val name = dialogBinding.etLocationName.text.toString()
            val latStr = dialogBinding.etLatitude.text.toString()
            val lngStr = dialogBinding.etLongitude.text.toString()

            if (name.isBlank()) {
                dialogBinding.tilLocationName.error = "Name is required"
                return@setOnClickListener
            }

            if (latStr.isBlank() || lngStr.isBlank()) {
                Toast.makeText(this, "Latitude and longitude are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val lat = latStr.toDoubleOrNull()
            val lng = lngStr.toDoubleOrNull()

            if (lat == null || lng == null) {
                Toast.makeText(this, "Invalid coordinates", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val newLocation = SavedLocation(
                name = name,
                latitude = lat,
                longitude = lng,
                radius = dialogBinding.sliderRadius.value,
                blockedApps = selectedAppsForNewLocation.toSet(),
                isActiveZone = dialogBinding.checkboxActiveZone.isChecked,
                enabled = true,
                enableFocusModeInZone = dialogBinding.checkboxTriggerFocusMode.isChecked,
                wifiSsid = dialogBinding.etWifiSsid.text.toString().takeIf { it.isNotBlank() }
            )

            locationPrefsManager.saveLocation(newLocation)
            geofenceManager.addGeofence(newLocation)

            loadLocations()
            selectedAppsForNewLocation.clear()
            dialog.dismiss()

            Toast.makeText(this, "Location added successfully", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun showEditLocationDialog(location: SavedLocation) {
        if (isAntiUninstallEnabled) {
            // Limited edit: only allow adding apps
            MaterialAlertDialogBuilder(this)
                .setTitle("Edit ${location.name}")
                .setMessage("Anti-uninstall is active. You can only add more apps to block in this location.")
                .setPositiveButton("Add Apps") { _, _ ->
                    selectedAppsForNewLocation = location.blockedApps.toMutableSet()
                    val intent = Intent(this, SelectAppsActivity::class.java)
                    intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(selectedAppsForNewLocation))
                    
                    // Wait, I can't register launcher here. I need to use the existing one or a separate one.
                    // I'll use a hack or just use the existing launcher and set a flag.
                    editingLocationId = location.id
                    selectAppsLauncher.launch(intent)
                }
                .setNegativeButton("Delete") { _, _ ->
                    confirmDeleteLocation(location)
                }
                .setNeutralButton("Close", null)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(location.name)
                .setMessage("Lat: ${location.latitude}, Lng: ${location.longitude}\nRadius: ${location.getRadiusText()}\nBlocked apps: ${location.getBlockedAppsCount()}")
                .setPositiveButton("Edit") { _, _ ->
                    // Full edit
                    showFullEditDialog(location)
                }
                .setNegativeButton("Delete") { _, _ ->
                    confirmDeleteLocation(location)
                }
                .setNeutralButton("Close", null)
                .show()
        }
    }

    private var editingLocationId: String? = null

    private fun showFullEditDialog(location: SavedLocation) {
        // Implementation of full edit dialog (similar to showAddLocationDialog but with pre-filled fields)
        val dialogBinding = DialogAddLocationBinding.inflate(layoutInflater)
        dialogBinding.etLocationName.setText(location.name)
        dialogBinding.etLatitude.setText(location.latitude.toString())
        dialogBinding.etLongitude.setText(location.longitude.toString())
        dialogBinding.sliderRadius.value = location.radius
        dialogBinding.tvRadiusValue.text = "${location.radius.toInt()} meters"
        dialogBinding.checkboxActiveZone.isChecked = location.isActiveZone
        dialogBinding.checkboxTriggerFocusMode.isChecked = location.enableFocusModeInZone
        dialogBinding.etWifiSsid.setText(location.wifiSsid ?: "")
        
        selectedAppsForNewLocation = location.blockedApps.toMutableSet()
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Edit Location")
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnGetCurrentLocation.setOnClickListener {
            getCurrentLocation { loc ->
                dialogBinding.etLatitude.setText(loc.latitude.toString())
                dialogBinding.etLongitude.setText(loc.longitude.toString())
            }
        }

        dialogBinding.btnGetCurrentWifi.setOnClickListener {
            val ssid = getCurrentWifiSsid()
            if (ssid != null) {
                dialogBinding.etWifiSsid.setText(ssid)
            } else {
                Toast.makeText(this, "Unable to get current WiFi SSID. Ensure WiFi is connected and location is enabled.", Toast.LENGTH_LONG).show()
            }
        }

        dialogBinding.btnSelectAppsToBlock.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(selectedAppsForNewLocation))
            editingLocationId = location.id // Set editing ID for full edit as well
            selectAppsLauncher.launch(intent)
        }

        dialogBinding.btnSave.setOnClickListener {
            val name = dialogBinding.etLocationName.text.toString()
            val lat = dialogBinding.etLatitude.text.toString().toDoubleOrNull()
            val lng = dialogBinding.etLongitude.text.toString().toDoubleOrNull()

            if (name.isBlank()) {
                dialogBinding.tilLocationName.error = "Name is required"
                return@setOnClickListener
            }

            if (lat == null || lng == null) {
                Toast.makeText(this, "Latitude and longitude are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val updatedLocation = location.copy(
                name = name,
                latitude = lat,
                longitude = lng,
                radius = dialogBinding.sliderRadius.value,
                blockedApps = selectedAppsForNewLocation.toSet(),
                isActiveZone = dialogBinding.checkboxActiveZone.isChecked,
                enableFocusModeInZone = dialogBinding.checkboxTriggerFocusMode.isChecked,
                wifiSsid = dialogBinding.etWifiSsid.text.toString().takeIf { it.isNotBlank() }
            )

            locationPrefsManager.saveLocation(updatedLocation)
            geofenceManager.addGeofence(updatedLocation)
            loadLocations()
            dialog.dismiss()
            Toast.makeText(this, "Location updated successfully", Toast.LENGTH_SHORT).show()
        }
        
        dialog.show()
    }

    private fun toggleLocationEnabled(location: SavedLocation) {
        if (isAntiUninstallEnabled && location.enabled) {
            Toast.makeText(this, "Disabling location zones is disabled by anti-uninstall", Toast.LENGTH_SHORT).show()
            loadLocations() // Refresh to undo toggle
            return
        }
        locationPrefsManager.toggleLocationEnabled(location.id)
        if (location.enabled) {
            geofenceManager.removeGeofence(location.id)
        } else {
            geofenceManager.addGeofence(location.copy(enabled = true))
        }
        loadLocations()
    }

    private fun confirmDeleteLocation(location: SavedLocation) {
        if (isAntiUninstallEnabled) {
            Toast.makeText(this, "Deleting locations is disabled by anti-uninstall", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Location?")
            .setMessage("Are you sure you want to delete '${location.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                locationPrefsManager.deleteLocation(location.id)
                geofenceManager.removeGeofence(location.id)
                loadLocations()
                Toast.makeText(this, "Location deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getCurrentLocation(callback: (Location) -> Unit) {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        // Check if location services are enabled
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isLocationEnabled) {
            Toast.makeText(
                this,
                "Please enable location services in Settings",
                Toast.LENGTH_LONG
            ).show()
            // Optionally open location settings
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: Exception) {
                // Settings activity not available
            }
            return
        }

        try {
            // First try to get a fresh location using getCurrentLocation()
            val cancellationTokenSource = CancellationTokenSource()

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            )
                .addOnSuccessListener { location ->
                    if (location != null) {
                        callback(location)
                    } else {
                        // If getCurrentLocation returns null, try lastLocation as fallback
                        // and also request a fresh update
                        requestFreshLocationUpdate(callback)
                    }
                }
                .addOnFailureListener {
                    // If getCurrentLocation fails, try lastLocation and request fresh update
                    requestFreshLocationUpdate(callback)
                }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Fallback to lastLocation and request fresh update
            requestFreshLocationUpdate(callback)
        }
    }

    private fun requestFreshLocationUpdate(callback: (Location) -> Unit) {
        try {
            // Try lastLocation first (might be available)
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        callback(location)
                    } else {
                        // If lastLocation is also null, request a fresh location update
                        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                            .setMaxUpdateDelayMillis(5000)
                            .build()

                        val locationCallback = object : LocationCallback() {
                            override fun onLocationResult(locationResult: LocationResult) {
                                val location = locationResult.lastLocation
                                if (location != null) {
                                    fusedLocationClient.removeLocationUpdates(this)
                                    callback(location)
                                } else {
                                    fusedLocationClient.removeLocationUpdates(this)
                                    Toast.makeText(
                                        this@LocationBlockerActivity,
                                        "Unable to get current location. Please ensure location services are enabled and try again.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }

                        if (ActivityCompat.checkSelfPermission(
                                this,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            fusedLocationClient.requestLocationUpdates(
                                locationRequest,
                                locationCallback,
                                Looper.getMainLooper()
                            )

                            // Timeout after 15 seconds
                            android.os.Handler(Looper.getMainLooper()).postDelayed({
                                fusedLocationClient.removeLocationUpdates(locationCallback)
                                Toast.makeText(
                                    this,
                                    "Location request timed out. Please ensure location services are enabled.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }, 15000)
                        }
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(
                        this,
                        "Failed to get location. Please ensure location services are enabled.",
                        Toast.LENGTH_LONG
                    ).show()
                }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Unable to get current location. Please ensure location services are enabled.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun getCurrentWifiSsid(): String? {
        // First try via ConnectivityManager (modern way)
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                // On newer Android versions, SSID retrieval is restricted. 
                // We'll try common fallbacks
            }
        }

        // Fallback to WifiManager
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        if (connectionInfo != null && connectionInfo.networkId != -1) {
            val ssid = connectionInfo.ssid
            if (ssid != null && ssid != "<unknown ssid>") {
                return ssid.removePrefix("\"").removeSuffix("\"")
            }
        }
        return null
    }

    private fun startLocationMonitoring() {
        val intent = Intent(this, LocationMonitoringService::class.java)
        intent.action = LocationMonitoringService.ACTION_START_MONITORING
        startService(intent)
    }

    private fun stopLocationMonitoring() {
        val intent = Intent(this, LocationMonitoringService::class.java)
        intent.action = LocationMonitoringService.ACTION_STOP_MONITORING
        startService(intent)
    }

    private fun updateStatusCard(enabled: Boolean) {
        if (enabled) {
            binding.statusCard.visibility = View.VISIBLE
            val locationsCount = locationPrefsManager.getEnabledLocations().size
            binding.tvCurrentStatus.text = "Location monitoring active • $locationsCount location(s) configured"
        } else {
            binding.statusCard.visibility = View.GONE
        }
    }

    inner class LocationsAdapter(
        private val onLocationClick: (SavedLocation) -> Unit,
        private val onToggleEnabled: (SavedLocation) -> Unit,
        private val onDeleteClick: (SavedLocation) -> Unit
    ) : RecyclerView.Adapter<LocationsAdapter.LocationViewHolder>() {

        private var locations = listOf<SavedLocation>()

        fun submitList(newLocations: List<SavedLocation>) {
            locations = newLocations
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LocationViewHolder {
            val binding = ItemLocationBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return LocationViewHolder(binding)
        }

        override fun onBindViewHolder(holder: LocationViewHolder, position: Int) {
            holder.bind(locations[position])
        }

        override fun getItemCount() = locations.size

        inner class LocationViewHolder(private val binding: ItemLocationBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(location: SavedLocation) {
                binding.tvLocationName.text = location.name
                binding.tvLocationDetails.text = 
                    "Radius: ${location.getRadiusText()} • ${location.getBlockedAppsCount()} apps blocked"

                binding.switchLocationEnabled.setOnCheckedChangeListener(null)
                binding.switchLocationEnabled.isChecked = location.enabled
                binding.switchLocationEnabled.setOnCheckedChangeListener { _, _ ->
                    onToggleEnabled(location)
                }

                binding.root.setOnClickListener {
                    onLocationClick(location)
                }

                binding.btnLocationOptions.setOnClickListener {
                    showLocationOptionsMenu(location)
                }
            }

            private fun showLocationOptionsMenu(location: SavedLocation) {
                MaterialAlertDialogBuilder(this@LocationBlockerActivity)
                    .setTitle(location.name)
                    .setItems(arrayOf("View Details", "Delete")) { _, which ->
                        when (which) {
                            0 -> onLocationClick(location)
                            1 -> {
                                if (isAntiUninstallEnabled) {
                                    Toast.makeText(this@LocationBlockerActivity, "Deleting locations is disabled by anti-uninstall", Toast.LENGTH_SHORT).show()
                                } else {
                                    onDeleteClick(location)
                                }
                            }
                        }
                    }
                    .show()
            }
        }
    }
}

