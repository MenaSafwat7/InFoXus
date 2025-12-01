package nethical.digipaws.utils

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import nethical.digipaws.data.SavedLocation
import nethical.digipaws.receivers.GeofenceBroadcastReceiver

class GeofenceManager(private val context: Context) {

    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val locationPrefs = LocationPreferencesManager(context)

    companion object {
        private const val TAG = "GeofenceManager"
        private const val GEOFENCE_EXPIRATION_IN_HOURS = 24L
        private const val GEOFENCE_EXPIRATION_IN_MILLISECONDS = 
            GEOFENCE_EXPIRATION_IN_HOURS * 60 * 60 * 1000
        private const val GEOFENCE_LOITERING_DELAY = 30000 
    }

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    fun addGeofence(location: SavedLocation): Task<Void>? {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted, cannot add geofence")
            return null
        }

        val geofence = buildGeofence(location)
        val request = buildGeofencingRequest(geofence)

        return try {
            geofencingClient.addGeofences(request, geofencePendingIntent).apply {
                addOnSuccessListener {
                    Log.d(TAG, "Geofence added successfully for ${location.name}")
                }
                addOnFailureListener { e ->
                    Log.e(TAG, "Failed to add geofence for ${location.name}", e)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when adding geofence", e)
            null
        }
    }

    fun addGeofences(locations: List<SavedLocation>): Task<Void>? {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted, cannot add geofences")
            return null
        }

        if (locations.isEmpty()) {
            Log.w(TAG, "No locations to add geofences for")
            return null
        }

        val geofences = locations.map { buildGeofence(it) }
        val request = buildGeofencingRequest(geofences)

        return try {
            geofencingClient.addGeofences(request, geofencePendingIntent).apply {
                addOnSuccessListener {
                    Log.d(TAG, "Added ${locations.size} geofences successfully")
                }
                addOnFailureListener { e ->
                    Log.e(TAG, "Failed to add geofences", e)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when adding geofences", e)
            null
        }
    }

    fun removeGeofence(locationId: String): Task<Void> {
        return geofencingClient.removeGeofences(listOf(locationId)).apply {
            addOnSuccessListener {
                Log.d(TAG, "Geofence removed successfully for $locationId")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove geofence for $locationId", e)
            }
        }
    }

    fun removeGeofences(locationIds: List<String>): Task<Void> {
        return geofencingClient.removeGeofences(locationIds).apply {
            addOnSuccessListener {
                Log.d(TAG, "Removed ${locationIds.size} geofences successfully")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove geofences", e)
            }
        }
    }

    fun removeAllGeofences(): Task<Void> {
        return geofencingClient.removeGeofences(geofencePendingIntent).apply {
            addOnSuccessListener {
                Log.d(TAG, "All geofences removed successfully")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove all geofences", e)
            }
        }
    }

    fun updateGeofence(location: SavedLocation): Task<Void>? {

        removeGeofence(location.id)

        return addGeofence(location)
    }

    fun rebuildGeofences(): Task<Void>? {
        val locations = locationPrefs.getEnabledLocations()
        if (locations.isEmpty()) {
            Log.d(TAG, "No enabled locations to rebuild geofences")
            return null
        }

        removeAllGeofences()

        return addGeofences(locations)
    }

    private fun buildGeofence(location: SavedLocation): Geofence {
        return Geofence.Builder()
            .setRequestId(location.id)
            .setCircularRegion(
                location.latitude,
                location.longitude,
                location.radius
            )
            .setExpirationDuration(GEOFENCE_EXPIRATION_IN_MILLISECONDS)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or 
                Geofence.GEOFENCE_TRANSITION_EXIT or
                Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(GEOFENCE_LOITERING_DELAY)
            .build()
    }

    private fun buildGeofencingRequest(geofence: Geofence): GeofencingRequest {
        return buildGeofencingRequest(listOf(geofence))
    }

    private fun buildGeofencingRequest(geofences: List<Geofence>): GeofencingRequest {
        return GeofencingRequest.Builder().apply {

            setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            addGeofences(geofences)
        }.build()
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getActiveGeofencesCount(): Int {
        return locationPrefs.getEnabledLocations().size
    }

    fun isGeofencingAvailable(): Boolean {
        return try {

            geofencingClient != null
        } catch (e: Exception) {
            Log.e(TAG, "Geofencing not available", e)
            false
        }
    }
}
