package nethical.digipaws.data

import com.google.android.gms.maps.model.LatLng
import java.util.UUID

data class SavedLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float, // in meters (50-500m recommended)
    val blockedApps: Set<String> = emptySet(), // Package names to block at this location
    val isActiveZone: Boolean = true, // true = blocking active here, false = blocking disabled here
    val enabled: Boolean = true,
    val enableFocusModeInZone: Boolean = false, // Auto-enable focus mode when in this zone
    val createdAt: Long = System.currentTimeMillis()
) {

    fun toLatLng(): LatLng = LatLng(latitude, longitude)

    fun shouldBlockApp(packageName: String): Boolean {
        return enabled && isActiveZone && blockedApps.contains(packageName)
    }

    fun getBlockedAppsCount(): Int = blockedApps.size

    fun getRadiusText(): String {
        return if (radius >= 1000) {
            "${radius / 1000} km"
        } else {
            "${radius.toInt()} m"
        }
    }
}

data class LocationBlockerConfig(
    val isEnabled: Boolean = false,
    val globalMode: LocationMode = LocationMode.BLOCK_IN_ZONES,
    val locations: List<SavedLocation> = emptyList(),
    val useHighAccuracy: Boolean = true, // GPS vs Network location
    val geofencingEnabled: Boolean = true,
    val updateIntervalMinutes: Int = 5, // How often to check location if not using geofencing
    val autoControlFocusMode: Boolean = false // Automatically enable/disable focus mode based on location
) {

    fun getEnabledLocations(): List<SavedLocation> {
        return locations.filter { it.enabled }
    }

    fun getLocationsBlockingApp(packageName: String): List<SavedLocation> {
        return locations.filter { it.blockedApps.contains(packageName) && it.enabled }
    }

    fun hasActiveLocations(): Boolean {
        return isEnabled && locations.any { it.enabled }
    }

    fun getTotalBlockedAppsCount(): Int {
        return locations.flatMap { it.blockedApps }.toSet().size
    }
}

enum class LocationMode {

    ENABLE_IN_ZONES,

    DISABLE_IN_ZONES,

    BLOCK_IN_ZONES
}

data class LocationState(
    val currentLatLng: LatLng?,
    val insideZones: List<SavedLocation> = emptyList(),
    val lastUpdateTime: Long = System.currentTimeMillis(),
    val accuracy: Float = 0f // in meters
) {

    fun isInsideAnyZone(): Boolean = insideZones.isNotEmpty()

    fun isInsideZone(locationId: String): Boolean {
        return insideZones.any { it.id == locationId }
    }

    fun getBlockedApps(): Set<String> {
        return insideZones.flatMap { it.blockedApps }.toSet()
    }
}

data class GeofenceEvent(
    val locationId: String,
    val locationName: String,
    val transitionType: GeofenceTransition,
    val timestamp: Long = System.currentTimeMillis()
)

enum class GeofenceTransition {
    ENTER,  // Entered the geofence
    EXIT,   // Exited the geofence
    DWELL   // Stayed inside geofence for a period
}
