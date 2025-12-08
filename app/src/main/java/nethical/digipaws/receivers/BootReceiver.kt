package nethical.digipaws.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.services.GeneralFeaturesService
import nethical.digipaws.services.LocationMonitoringService
import nethical.digipaws.utils.LocationPreferencesManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val DELAY_BEFORE_REFRESH_MS = 5000L
        private const val RETRY_DELAY_MS = 10000L
        private const val MAX_RETRIES = 3
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_PACKAGE_REPLACED) {
            
            Log.d(TAG, "Boot completed - Restoring app state")
            
            Handler(Looper.getMainLooper()).postDelayed({
                restoreAppState(context, 0)
            }, DELAY_BEFORE_REFRESH_MS)
        }
    }

    private fun restoreAppState(context: Context, retryCount: Int) {
        try {
            refreshAccessibilityServices(context)
            restoreLocationMonitoring(context)
            Log.d(TAG, "App state restored successfully (attempt ${retryCount + 1})")
            
            if (retryCount < MAX_RETRIES) {
                Handler(Looper.getMainLooper()).postDelayed({
                    restoreAppState(context, retryCount + 1)
                }, RETRY_DELAY_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring app state (attempt ${retryCount + 1})", e)
            if (retryCount < MAX_RETRIES) {
                Handler(Looper.getMainLooper()).postDelayed({
                    restoreAppState(context, retryCount + 1)
                }, RETRY_DELAY_MS)
            }
        }
    }

    private fun refreshAccessibilityServices(context: Context) {
        try {
            val refreshAppBlocker = Intent(AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(refreshAppBlocker)
            Log.d(TAG, "Sent refresh broadcast to AppBlockerService")

            val refreshFocusMode = Intent(AppBlockerService.INTENT_ACTION_REFRESH_FOCUS_MODE).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(refreshFocusMode)
            Log.d(TAG, "Sent refresh broadcast for Focus Mode")

            val refreshAntiUninstall = Intent(GeneralFeaturesService.INTENT_ACTION_REFRESH_ANTI_UNINSTALL).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(refreshAntiUninstall)
            Log.d(TAG, "Sent refresh broadcast to GeneralFeaturesService")
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing accessibility services", e)
        }
    }

    private fun restoreLocationMonitoring(context: Context) {
        try {
            val locationPrefsManager = LocationPreferencesManager(context)
            val config = locationPrefsManager.getConfig()
            
            if (config.isEnabled && config.locations.isNotEmpty()) {
                val startIntent = Intent(context, LocationMonitoringService::class.java).apply {
                    action = LocationMonitoringService.ACTION_START_MONITORING
                }
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
                Log.d(TAG, "Location monitoring service started with ${config.locations.size} locations")
            } else {
                Log.d(TAG, "Location monitoring not enabled or no locations, skipping")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring location monitoring", e)
        }
    }
}

