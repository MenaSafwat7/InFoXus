package nethical.digipaws.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import nethical.digipaws.data.GeofenceEvent
import nethical.digipaws.data.GeofenceTransition
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.utils.LocationPreferencesManager

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GeofenceReceiver"
        const val ACTION_GEOFENCE_EVENT = "nethical.digipaws.action.GEOFENCE_EVENT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)

        if (geofencingEvent == null) {
            Log.e(TAG, "Geofencing event is null")
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing error: ${geofencingEvent.errorCode}")
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition

        val triggeringGeofences = geofencingEvent.triggeringGeofences

        if (triggeringGeofences == null || triggeringGeofences.isEmpty()) {
            Log.w(TAG, "No triggering geofences")
            return
        }

        for (geofence in triggeringGeofences) {
            handleGeofenceTransition(context, geofence.requestId, geofenceTransition)
        }
    }

    private fun handleGeofenceTransition(
        context: Context,
        locationId: String,
        transitionType: Int
    ) {
        val locationPrefs = LocationPreferencesManager(context)
        val location = locationPrefs.getLocation(locationId)

        if (location == null) {
            Log.w(TAG, "Location not found for ID: $locationId")
            return
        }

        val transition = when (transitionType) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                Log.d(TAG, "Entered geofence: ${location.name}")
                GeofenceTransition.ENTER
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                Log.d(TAG, "Exited geofence: ${location.name}")
                GeofenceTransition.EXIT
            }
            Geofence.GEOFENCE_TRANSITION_DWELL -> {
                Log.d(TAG, "Dwelling in geofence: ${location.name}")
                GeofenceTransition.DWELL
            }
            else -> {
                Log.w(TAG, "Unknown transition type: $transitionType")
                return
            }
        }

        val event = GeofenceEvent(
            locationId = locationId,
            locationName = location.name,
            transitionType = transition
        )

        notifyAppBlockerService(context, event)

        storeLastEvent(context, event)
    }

    private fun notifyAppBlockerService(context: Context, event: GeofenceEvent) {
        val intent = Intent(ACTION_GEOFENCE_EVENT).apply {
            putExtra("location_id", event.locationId)
            putExtra("location_name", event.locationName)
            putExtra("transition_type", event.transitionType.name)
            putExtra("timestamp", event.timestamp)
        }
        context.sendBroadcast(intent)

        context.sendBroadcast(Intent(AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER))
    }

    private fun storeLastEvent(context: Context, event: GeofenceEvent) {
        context.getSharedPreferences("location_blocker", Context.MODE_PRIVATE)
            .edit()
            .apply {
                putString("last_event_location_id", event.locationId)
                putString("last_event_location_name", event.locationName)
                putString("last_event_transition", event.transitionType.name)
                putLong("last_event_timestamp", event.timestamp)
                apply()
            }
    }
}
