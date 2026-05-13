package mina.infoxus.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import mina.infoxus.services.MainAccessibilityService
import mina.infoxus.services.BootRestoreService
import mina.infoxus.services.LocationMonitoringService
import mina.infoxus.utils.LocationPreferencesManager
import mina.infoxus.utils.DataBackupManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val INITIAL_DELAY_MS = 5000L
        private const val SERVICE_CONNECTION_DELAY_MS = 8000L
        private const val RETRY_DELAY_MS = 15000L
        private const val MAX_RETRIES = 8
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_PACKAGE_REPLACED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.d(TAG, "Boot completed - Starting boot restore service. Action: ${intent.action}")
            
            // Start foreground service immediately - this ensures it runs even with battery optimization
            try {
                val serviceIntent = Intent(context, BootRestoreService::class.java).apply {
                    action = BootRestoreService.ACTION_START_RESTORE
                }
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "Boot restore service started")

                // Also schedule a direct restore as a fallback to ensure data loads even if service is killed
                Handler(Looper.getMainLooper()).postDelayed({
                    restoreAppState(context, 0)
                }, INITIAL_DELAY_MS)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting boot restore service, falling back to direct restore", e)
                // Fallback to direct restore if service fails
                Handler(Looper.getMainLooper()).postDelayed({
                    restoreAppState(context, 0)
                }, INITIAL_DELAY_MS)
            }
        }
    }

    private fun restoreAppState(context: Context, retryCount: Int) {
        try {
            Log.d(TAG, "Restoring app state - Attempt ${retryCount + 1}/$MAX_RETRIES")
            
            // CRITICAL: First restore data from backup files if SharedPreferences were cleared
            if (retryCount == 0) {
                Log.d(TAG, "Attempting to restore data from backup files...")
                try {
                    DataBackupManager.restoreAllData(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error restoring data from backup in BootReceiver", e)
                }
            }
            
            // Then verify that data exists in SharedPreferences
            verifyDataPersistence(context)
            
            // Restore location monitoring (can be started directly)
            restoreLocationMonitoring(context)
            
            // Send refresh broadcasts multiple times with increasing delays
            // Accessibility services auto-start when enabled, but need time to connect
            // Send first refresh after initial delay
            Handler(Looper.getMainLooper()).postDelayed({
                refreshAccessibilityServices(context)
            }, SERVICE_CONNECTION_DELAY_MS)
            
            // Send additional refreshes to ensure services get the message
            Handler(Looper.getMainLooper()).postDelayed({
                refreshAccessibilityServices(context)
            }, SERVICE_CONNECTION_DELAY_MS + 5000)
            
            Handler(Looper.getMainLooper()).postDelayed({
                refreshAccessibilityServices(context)
            }, SERVICE_CONNECTION_DELAY_MS + 10000)
            
            Log.d(TAG, "App state restoration initiated (attempt ${retryCount + 1})")
            
            // Retry a few times to ensure services have time to connect
            if (retryCount < MAX_RETRIES) {
                Handler(Looper.getMainLooper()).postDelayed({
                    restoreAppState(context, retryCount + 1)
                }, RETRY_DELAY_MS)
            } else {
                Log.d(TAG, "Max retries reached. App state restoration complete.")
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

    private fun verifyDataPersistence(context: Context) {
        try {
            // Verify anti-uninstall data
            val antiUninstallPrefs = context.getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
            val isAntiUninstallOn = antiUninstallPrefs.getBoolean("is_anti_uninstall_on", false)
            val antiUninstallMode = antiUninstallPrefs.getInt("mode", -1)
            Log.d(TAG, "Anti-uninstall data: enabled=$isAntiUninstallOn, mode=$antiUninstallMode")
            
            // Verify location data
            val locationPrefsManager = LocationPreferencesManager(context)
            val config = locationPrefsManager.getConfig()
            val locationsCount = config.locations.size
            Log.d(TAG, "Location data: enabled=${config.isEnabled}, locations=$locationsCount")
            
            // Verify auto-focus mode data
            val autoFocusPrefs = context.getSharedPreferences("auto_focus_hours", Context.MODE_PRIVATE)
            val autoFocusJson = autoFocusPrefs.getString("auto_focus_list", null)
            val hasAutoFocusData = !autoFocusJson.isNullOrEmpty()
            Log.d(TAG, "Auto-focus mode data: exists=$hasAutoFocusData")
            
            // Verify focus mode data
            val focusModePrefs = context.getSharedPreferences("focus_mode", Context.MODE_PRIVATE)
            val focusModeJson = focusModePrefs.getString("focus_mode", null)
            val hasFocusModeData = !focusModeJson.isNullOrEmpty()
            Log.d(TAG, "Focus mode data: exists=$hasFocusModeData")

            // Verify usage limits data
            val usageLimitsPrefs = context.getSharedPreferences("usage_limits", Context.MODE_PRIVATE)
            val usageLimitsJson = usageLimitsPrefs.getString("usage_limits_config", null)
            val hasUsageLimitsData = !usageLimitsJson.isNullOrEmpty()
            Log.d(TAG, "Usage limits data: exists=$hasUsageLimitsData")
            
            if (!isAntiUninstallOn && locationsCount == 0 && !hasAutoFocusData && !hasFocusModeData && !hasUsageLimitsData) {
                Log.w(TAG, "WARNING: No data found in SharedPreferences after boot. This might indicate data loss.")
            } else {
                Log.d(TAG, "Data persistence verified successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying data persistence", e)
        }
    }

    private fun refreshAccessibilityServices(context: Context) {
        try {
            // Send refresh broadcasts to accessibility services
            val packageName = context.packageName
            
            val refreshAll = Intent(MainAccessibilityService.ACTION_REFRESH_ALL).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(refreshAll)
            Log.d(TAG, "Sent unified refresh broadcast to MainAccessibilityService")
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing accessibility services", e)
        }
    }

    private fun restoreLocationMonitoring(context: Context) {
        try {
            // Wait a bit for data to be fully restored before checking
            Handler(Looper.getMainLooper()).postDelayed({
                startLocationServiceWithRetry(context, 0)
            }, 3000) // Wait 3 seconds for data restore to complete
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring location monitoring", e)
        }
    }
    
    private fun startLocationServiceWithRetry(context: Context, attempt: Int) {
        try {
            val locationPrefsManager = LocationPreferencesManager(context)
            val config = locationPrefsManager.getConfig()
            
            Log.d(TAG, "Attempting to start location monitoring (attempt ${attempt + 1}): enabled=${config.isEnabled}, locations=${config.locations.size}")
            
            if (config.isEnabled && config.locations.isNotEmpty()) {
                val startIntent = Intent(context, LocationMonitoringService::class.java).apply {
                    action = LocationMonitoringService.ACTION_START_MONITORING
                }
                
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(startIntent)
                    } else {
                        context.startService(startIntent)
                    }
                    Log.d(TAG, "Location monitoring service started with ${config.locations.size} locations")
                    
                    // Retry a few more times to ensure it stays running
                    if (attempt < 3) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            startLocationServiceWithRetry(context, attempt + 1)
                        }, 10000) // Retry every 10 seconds
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting location monitoring service (attempt ${attempt + 1})", e)
                    if (attempt < 3) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            startLocationServiceWithRetry(context, attempt + 1)
                        }, 10000)
                    }
                }
            } else {
                Log.d(TAG, "Location monitoring not enabled or no locations configured, skipping")
                // Still retry in case data gets restored later
                if (attempt < 5) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        startLocationServiceWithRetry(context, attempt + 1)
                    }, 15000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in location monitoring restore (attempt ${attempt + 1})", e)
            if (attempt < 3) {
                Handler(Looper.getMainLooper()).postDelayed({
                    startLocationServiceWithRetry(context, attempt + 1)
                }, 10000)
            }
        }
    }
}


