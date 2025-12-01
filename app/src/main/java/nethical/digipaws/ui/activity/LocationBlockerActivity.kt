package nethical.digipaws.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
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
import com.google.android.gms.location.LocationServices
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import nethical.digipaws.R
import nethical.digipaws.data.LocationMode
import nethical.digipaws.data.SavedLocation
import nethical.digipaws.databinding.ActivityLocationBlockerBinding
import nethical.digipaws.databinding.DialogAddLocationBinding
import nethical.digipaws.databinding.ItemLocationBinding
import nethical.digipaws.services.LocationMonitoringService
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.utils.GeofenceManager
import nethical.digipaws.utils.LocationPreferencesManager

class LocationBlockerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocationBlockerBinding
    private lateinit var locationPrefsManager: LocationPreferencesManager
    private lateinit var geofenceManager: GeofenceManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationsAdapter: LocationsAdapter

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
                selectedAppsForNewLocation = it.toMutableSet()
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
            locationPrefsManager.setAutoControlFocusMode(isChecked)

            val intent = Intent(AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER)
            sendBroadcast(intent)
        }

        binding.radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
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
                enableFocusModeInZone = dialogBinding.checkboxTriggerFocusMode.isChecked
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

        MaterialAlertDialogBuilder(this)
            .setTitle(location.name)
            .setMessage("Lat: ${location.latitude}, Lng: ${location.longitude}\nRadius: ${location.getRadiusText()}\nBlocked apps: ${location.getBlockedAppsCount()}")
            .setPositiveButton("Edit") { _, _ ->

                Toast.makeText(this, "Edit functionality coming soon", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Delete") { _, _ ->
                confirmDeleteLocation(location)
            }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun toggleLocationEnabled(location: SavedLocation) {
        locationPrefsManager.toggleLocationEnabled(location.id)
        if (location.enabled) {
            geofenceManager.removeGeofence(location.id)
        } else {
            geofenceManager.addGeofence(location.copy(enabled = true))
        }
        loadLocations()
    }

    private fun confirmDeleteLocation(location: SavedLocation) {
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

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        callback(location)
                    } else {
                        Toast.makeText(this, "Unable to get current location", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to get location", Toast.LENGTH_SHORT).show()
                }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
        }
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
                            1 -> onDeleteClick(location)
                        }
                    }
                    .show()
            }
        }
    }
}
