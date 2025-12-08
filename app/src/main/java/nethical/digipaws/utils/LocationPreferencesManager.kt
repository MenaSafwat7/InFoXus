package nethical.digipaws.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import nethical.digipaws.data.LocationBlockerConfig
import nethical.digipaws.data.LocationMode
import nethical.digipaws.data.SavedLocation

class LocationPreferencesManager(private val context: Context) {

    private val prefs: SharedPreferences = 
        context.getSharedPreferences("location_blocker", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_ENABLED = "location_blocking_enabled"
        private const val KEY_GLOBAL_MODE = "global_mode"
        private const val KEY_LOCATIONS = "saved_locations"
        private const val KEY_USE_HIGH_ACCURACY = "use_high_accuracy"
        private const val KEY_GEOFENCING_ENABLED = "geofencing_enabled"
        private const val KEY_UPDATE_INTERVAL = "update_interval_minutes"
        private const val KEY_AUTO_CONTROL_FOCUS_MODE = "auto_control_focus_mode"
    }

    fun saveLocation(location: SavedLocation) {
        val locations = getLocations().toMutableList()
        val existingIndex = locations.indexOfFirst { it.id == location.id }

        if (existingIndex >= 0) {
            locations[existingIndex] = location
        } else {
            locations.add(location)
        }

        saveLocations(locations)
    }

    fun deleteLocation(locationId: String) {
        val locations = getLocations().filter { it.id != locationId }
        saveLocations(locations)
    }

    fun getLocation(locationId: String): SavedLocation? {
        return getLocations().find { it.id == locationId }
    }

    fun getLocations(): List<SavedLocation> {
        val json = prefs.getString(KEY_LOCATIONS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SavedLocation>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveLocations(locations: List<SavedLocation>) {
        val json = gson.toJson(locations)
        prefs.edit().putString(KEY_LOCATIONS, json).commit()
    }

    fun getConfig(): LocationBlockerConfig {
        return LocationBlockerConfig(
            isEnabled = prefs.getBoolean(KEY_ENABLED, false),
            globalMode = LocationMode.valueOf(
                prefs.getString(KEY_GLOBAL_MODE, LocationMode.BLOCK_IN_ZONES.name)!!
            ),
            locations = getLocations(),
            useHighAccuracy = prefs.getBoolean(KEY_USE_HIGH_ACCURACY, true),
            geofencingEnabled = prefs.getBoolean(KEY_GEOFENCING_ENABLED, true),
            updateIntervalMinutes = prefs.getInt(KEY_UPDATE_INTERVAL, 5),
            autoControlFocusMode = prefs.getBoolean(KEY_AUTO_CONTROL_FOCUS_MODE, false)
        )
    }

    fun saveConfig(config: LocationBlockerConfig) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.isEnabled)
            putString(KEY_GLOBAL_MODE, config.globalMode.name)
            putBoolean(KEY_USE_HIGH_ACCURACY, config.useHighAccuracy)
            putBoolean(KEY_GEOFENCING_ENABLED, config.geofencingEnabled)
            putInt(KEY_UPDATE_INTERVAL, config.updateIntervalMinutes)
            commit()
        }
        saveLocations(config.locations)
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setGlobalMode(mode: LocationMode) {
        prefs.edit().putString(KEY_GLOBAL_MODE, mode.name).apply()
    }

    fun getGlobalMode(): LocationMode {
        val modeName = prefs.getString(KEY_GLOBAL_MODE, LocationMode.BLOCK_IN_ZONES.name)!!
        return try {
            LocationMode.valueOf(modeName)
        } catch (e: Exception) {
            LocationMode.BLOCK_IN_ZONES
        }
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun getEnabledLocations(): List<SavedLocation> {
        return getLocations().filter { it.enabled }
    }

    fun toggleLocationEnabled(locationId: String) {
        val location = getLocation(locationId) ?: return
        saveLocation(location.copy(enabled = !location.enabled))
    }

    fun updateBlockedApps(locationId: String, blockedApps: Set<String>) {
        val location = getLocation(locationId) ?: return
        saveLocation(location.copy(blockedApps = blockedApps))
    }

    fun setAutoControlFocusMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONTROL_FOCUS_MODE, enabled).apply()
    }

    fun isAutoControlFocusModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_CONTROL_FOCUS_MODE, false)
    }
}
