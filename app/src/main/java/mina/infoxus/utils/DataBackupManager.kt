package mina.infoxus.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import mina.infoxus.services.MainAccessibilityService

object DataBackupManager {
    private const val TAG = "DataBackupManager"
    private const val BACKUP_DIR = "data_backup"
    
    private fun getBackupDir(context: Context): File {
        // Use external files directory - more persistent than internal files
        val externalDir = context.getExternalFilesDir(null)
        val dir = if (externalDir != null) {
            File(externalDir, BACKUP_DIR)
        } else {
            // Fallback to internal storage if external is not available
            File(context.filesDir, BACKUP_DIR)
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    // Also backup to internal storage as a secondary backup
    private fun getSecondaryBackupDir(context: Context): File {
        val dir = File(context.filesDir, BACKUP_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    fun backupAntiUninstall(context: Context) {
        try {
            val prefs = context.getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
            val allData = prefs.all
            
            if (allData.isEmpty()) return
            
            val gson = Gson()
            val json = gson.toJson(allData)
            
            // Backup to primary location (external storage)
            val backupFile = File(getBackupDir(context), "anti_uninstall.json")
            FileWriter(backupFile).use { it.write(json) }
            
            // Also backup to secondary location (internal storage)
            val secondaryBackupFile = File(getSecondaryBackupDir(context), "anti_uninstall.json")
            FileWriter(secondaryBackupFile).use { it.write(json) }
            
            Log.d(TAG, "Backed up anti-uninstall data")
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up anti-uninstall data", e)
        }
    }
    
    fun restoreAntiUninstall(context: Context): Boolean {
        try {
            // Try primary backup location first (external storage)
            var backupFile = File(getBackupDir(context), "anti_uninstall.json")
            
            // If not found, try secondary location (internal storage)
            if (!backupFile.exists()) {
                backupFile = File(getSecondaryBackupDir(context), "anti_uninstall.json")
            }
            
            if (!backupFile.exists()) {
                Log.d(TAG, "No anti-uninstall backup file found in either location")
                return false
            }
            
            Log.d(TAG, "Found anti-uninstall backup at: ${backupFile.absolutePath}")
            
            val prefs = context.getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
            val currentData = prefs.all
            val isCurrentlyEnabled = prefs.getBoolean("is_anti_uninstall_on", false)
            val currentMode = prefs.getInt("mode", -1)
            
            // Restore if:
            // 1. Current data is empty, OR
            // 2. Enabled is true but mode is missing (data corruption), OR
            // 3. Enabled is false but backup has data
            val shouldRestore = currentData.isEmpty() || 
                               (isCurrentlyEnabled && currentMode == -1) ||
                               (!isCurrentlyEnabled && backupFile.exists())
            
            if (!shouldRestore && currentData.isNotEmpty() && isCurrentlyEnabled && currentMode != -1) {
                Log.d(TAG, "Anti-uninstall data exists, is enabled, and mode is set, skipping restore")
                return false
            }
            
            Log.d(TAG, "Restoring anti-uninstall data (current enabled: $isCurrentlyEnabled, mode: $currentMode, backup exists: true)")
            
            val gson = Gson()
            val json = FileReader(backupFile).use { it.readText() }
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
            val data = gson.fromJson<Map<String, Any>>(json, type) ?: return false
            
            val editor = prefs.edit()
            data.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Double -> {
                        // Gson parses all numbers as Double by default
                        if (key == "mode") editor.putInt(key, value.toInt())
                        else if (value == value.toInt().toDouble()) editor.putInt(key, value.toInt())
                        else editor.putFloat(key, value.toFloat())
                    }
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
                }
            }
            editor.apply()
            Log.d(TAG, "Restored anti-uninstall data from backup")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring anti-uninstall data", e)
            return false
        }
    }
    
    fun backupLocations(context: Context) {
        try {
            val locationPrefsManager = LocationPreferencesManager(context)
            val config = locationPrefsManager.getConfig()
            
            // Always backup if there are locations, even if disabled
            // This ensures we can restore locations even if the enabled flag gets reset
            if (config.locations.isEmpty()) {
                Log.d(TAG, "No locations to backup")
                return
            }
            
            val gson = Gson()
            val json = gson.toJson(config)
            
            // Backup to primary location
            val backupFile = File(getBackupDir(context), "locations.json")
            FileWriter(backupFile).use { it.write(json) }
            
            // Also backup to secondary location
            val secondaryBackupFile = File(getSecondaryBackupDir(context), "locations.json")
            FileWriter(secondaryBackupFile).use { it.write(json) }
            
            Log.d(TAG, "Backed up location data: ${config.locations.size} locations to both locations")
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up location data", e)
        }
    }
    
    fun restoreLocations(context: Context): Boolean {
        try {
            // Try primary backup location first
            var backupFile = File(getBackupDir(context), "locations.json")
            
            // If not found, try secondary location
            if (!backupFile.exists()) {
                backupFile = File(getSecondaryBackupDir(context), "locations.json")
            }
            
            if (!backupFile.exists()) {
                Log.d(TAG, "No locations backup file found in either location")
                return false
            }
            
            Log.d(TAG, "Found locations backup at: ${backupFile.absolutePath}")
            
            val locationPrefsManager = LocationPreferencesManager(context)
            val currentConfig = locationPrefsManager.getConfig()
            
            // Restore if locations are empty OR if enabled flag is false but backup has enabled=true
            val shouldRestore = currentConfig.locations.isEmpty() || 
                               (!currentConfig.isEnabled && backupFile.exists())
            
            if (!shouldRestore && currentConfig.locations.isNotEmpty() && currentConfig.isEnabled) {
                Log.d(TAG, "Locations exist (${currentConfig.locations.size}) and enabled=true, skipping restore")
                return false
            }
            
            Log.d(TAG, "Restoring locations data (current: enabled=${currentConfig.isEnabled}, locations=${currentConfig.locations.size}, backup exists: true)")
            
            val gson = Gson()
            val json = FileReader(backupFile).use { it.readText() }
            val config = gson.fromJson(json, mina.infoxus.data.LocationBlockerConfig::class.java) ?: return false
            
            locationPrefsManager.saveConfig(config)
            Log.d(TAG, "Restored location data from backup: enabled=${config.isEnabled}, ${config.locations.size} locations")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring location data", e)
            return false
        }
    }
    
    fun backupAutoFocusHours(context: Context) {
        try {
            val prefs = context.getSharedPreferences("auto_focus_hours", Context.MODE_PRIVATE)
            val json = prefs.getString("auto_focus_list", null)
            
            if (json.isNullOrEmpty() || json == "[]") {
                // If we are about to backup an empty list, check if we already have a non-empty backup
                val primaryFile = File(getBackupDir(context), "auto_focus_hours.json")
                if (primaryFile.exists() && primaryFile.length() > 5) {
                    Log.d(TAG, "Skipping backup of empty auto-focus list to avoid overwriting existing data")
                    return
                }
            }
            
            if (json.isNullOrEmpty()) return
            
            // Backup to primary location
            val backupFile = File(getBackupDir(context), "auto_focus_hours.json")
            FileWriter(backupFile).use { it.write(json) }
            
            // Also backup to secondary location
            val secondaryBackupFile = File(getSecondaryBackupDir(context), "auto_focus_hours.json")
            FileWriter(secondaryBackupFile).use { it.write(json) }
            
            Log.d(TAG, "Backed up auto-focus hours data")
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up auto-focus hours data", e)
        }
    }
    
    fun restoreAutoFocusHours(context: Context): Boolean {
        try {
            // Try primary backup location first
            var backupFile = File(getBackupDir(context), "auto_focus_hours.json")
            
            // If not found, try secondary location
            if (!backupFile.exists()) {
                backupFile = File(getSecondaryBackupDir(context), "auto_focus_hours.json")
            }
            
            if (!backupFile.exists()) {
                Log.d(TAG, "No auto-focus hours backup file found in either location")
                return false
            }
            
            Log.d(TAG, "Found auto-focus hours backup at: ${backupFile.absolutePath}")
            
            val prefs = context.getSharedPreferences("auto_focus_hours", Context.MODE_PRIVATE)
            val currentJson = prefs.getString("auto_focus_list", null)
            
            // Only restore if current data is empty
            if (!currentJson.isNullOrEmpty()) {
                Log.d(TAG, "Auto-focus hours data exists, skipping restore")
                return false
            }
            
            Log.d(TAG, "Restoring auto-focus hours data from backup")
            
            val json = FileReader(backupFile).use { it.readText() }
            val restored = prefs.edit().putString("auto_focus_list", json).commit()
            if (restored) {
                Log.d(TAG, "Restored auto-focus hours data from backup")
            }
            return restored
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring auto-focus hours data", e)
            return false
        }
    }
    
    fun backupFocusMode(context: Context) {
        try {
            val prefs = context.getSharedPreferences("focus_mode", Context.MODE_PRIVATE)
            val focusModeJson = prefs.getString("focus_mode", null)
            val selectedAppsJson = prefs.getString("selected_apps", null)
            
            if (focusModeJson.isNullOrEmpty() && selectedAppsJson.isNullOrEmpty()) return

            // If we are about to backup empty focus mode data, check if we have a non-empty backup
            if (focusModeJson == null || focusModeJson.contains("\"modeType\":0")) { // Default empty mode
                 val primaryFile = File(getBackupDir(context), "focus_mode.json")
                 if (primaryFile.exists() && primaryFile.length() > 50) {
                     Log.d(TAG, "Skipping backup of potentially empty focus mode to avoid overwriting")
                     return
                 }
            }
            
            val gson = Gson()
            val backupData = mapOf(
                "focus_mode" to focusModeJson,
                "selected_apps" to selectedAppsJson
            )
            val json = gson.toJson(backupData)
            
            // Backup to primary location
            val backupFile = File(getBackupDir(context), "focus_mode.json")
            FileWriter(backupFile).use { it.write(json) }
            
            // Also backup to secondary location
            val secondaryBackupFile = File(getSecondaryBackupDir(context), "focus_mode.json")
            FileWriter(secondaryBackupFile).use { it.write(json) }
            
            Log.d(TAG, "Backed up focus mode data")
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up focus mode data", e)
        }
    }
    
    fun restoreFocusMode(context: Context): Boolean {
        try {
            // Try primary backup location first
            var backupFile = File(getBackupDir(context), "focus_mode.json")
            
            // If not found, try secondary location
            if (!backupFile.exists()) {
                backupFile = File(getSecondaryBackupDir(context), "focus_mode.json")
            }
            
            if (!backupFile.exists()) {
                Log.d(TAG, "No focus mode backup file found in either location")
                return false
            }
            
            Log.d(TAG, "Found focus mode backup at: ${backupFile.absolutePath}")
            
            val prefs = context.getSharedPreferences("focus_mode", Context.MODE_PRIVATE)
            val currentFocusMode = prefs.getString("focus_mode", null)
            val currentSelectedApps = prefs.getString("selected_apps", null)
            
            // Only restore if current data is empty
            if (!currentFocusMode.isNullOrEmpty() || !currentSelectedApps.isNullOrEmpty()) {
                Log.d(TAG, "Focus mode data exists, skipping restore")
                return false
            }
            
            Log.d(TAG, "Restoring focus mode data from backup")
            
            val gson = Gson()
            val json = FileReader(backupFile).use { it.readText() }
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String?>>() {}.type
            val data = gson.fromJson<Map<String, String?>>(json, type) ?: return false
            
            val editor = prefs.edit()
            data["focus_mode"]?.let { editor.putString("focus_mode", it) }
            data["selected_apps"]?.let { editor.putString("selected_apps", it) }
            val restored = editor.commit()
            if (restored) {
                Log.d(TAG, "Restored focus mode data from backup")
            }
            return restored
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring focus mode data", e)
            return false
        }
    }
    
    fun backupFirstLaunch(context: Context) {
        try {
            val prefs = context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            val isFirstLaunchComplete = prefs.getBoolean("isFirstLaunchComplete", false)
            
            if (!isFirstLaunchComplete) return
            
            val gson = Gson()
            val data = mapOf("isFirstLaunchComplete" to isFirstLaunchComplete)
            val json = gson.toJson(data)
            
            // Backup to primary location
            val backupFile = File(getBackupDir(context), "first_launch.json")
            FileWriter(backupFile).use { it.write(json) }
            
            // Also backup to secondary location
            val secondaryBackupFile = File(getSecondaryBackupDir(context), "first_launch.json")
            FileWriter(secondaryBackupFile).use { it.write(json) }
            
            Log.d(TAG, "Backed up first launch flag to both locations")
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up first launch flag", e)
        }
    }
    
    fun restoreFirstLaunch(context: Context): Boolean {
        try {
            // Try primary backup location first
            var backupFile = File(getBackupDir(context), "first_launch.json")
            
            // If not found, try secondary location
            if (!backupFile.exists()) {
                backupFile = File(getSecondaryBackupDir(context), "first_launch.json")
            }
            
            if (!backupFile.exists()) {
                Log.d(TAG, "No first launch backup file found in either location")
                return false
            }
            
            Log.d(TAG, "Found first launch backup at: ${backupFile.absolutePath}")
            
            val prefs = context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            val currentValue = prefs.getBoolean("isFirstLaunchComplete", false)
            
            // Only restore if current value is false
            if (currentValue) {
                Log.d(TAG, "First launch flag is already set, skipping restore")
                return false
            }
            
            Log.d(TAG, "Restoring first launch flag from backup")
            
            val gson = Gson()
            val json = FileReader(backupFile).use { it.readText() }
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Boolean>>() {}.type
            val data = gson.fromJson<Map<String, Boolean>>(json, type) ?: return false
            
            val restored = prefs.edit().putBoolean("isFirstLaunchComplete", data["isFirstLaunchComplete"] ?: false).commit()
            if (restored) {
                Log.d(TAG, "Restored first launch flag from backup")
            }
            return restored
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring first launch flag", e)
            return false
        }
    }
    
    fun backupUsageLimits(context: Context) {
        try {
            val prefs = context.getSharedPreferences("usage_limits", Context.MODE_PRIVATE)
            val allData = prefs.all
            
            if (allData.isEmpty()) return
            
            // Overwrite protection: Don't backup if the current config is empty but a good backup exists
            val configJson = allData["usage_limits_config"] as? String
            if (configJson != null && configJson.contains("\"limits\":[]")) {
                val primaryFile = File(getBackupDir(context), "usage_limits.json")
                if (primaryFile.exists() && primaryFile.length() > 50) {
                    Log.d(TAG, "Skipping backup of empty usage limits to protect existing backup")
                    return
                }
            }
            
            val gson = Gson()
            val json = gson.toJson(allData)
            
            // Backup to primary location
            val backupFile = File(getBackupDir(context), "usage_limits.json")
            FileWriter(backupFile).use { it.write(json) }
            
            // Also backup to secondary location
            val secondaryBackupFile = File(getSecondaryBackupDir(context), "usage_limits.json")
            FileWriter(secondaryBackupFile).use { it.write(json) }
            
            Log.d(TAG, "Backed up usage limits data (standard pattern)")
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up usage limits data", e)
        }
    }
    
    fun restoreUsageLimits(context: Context): Boolean {
        try {
            // Try primary backup location first
            var backupFile = File(getBackupDir(context), "usage_limits.json")
            
            // If not found, try secondary location
            if (!backupFile.exists()) {
                backupFile = File(getSecondaryBackupDir(context), "usage_limits.json")
            }
            
            if (!backupFile.exists()) {
                Log.d(TAG, "No usage limits backup file found in either location")
                return false
            }
            
            Log.d(TAG, "Found usage limits backup at: ${backupFile.absolutePath}")
            
            val prefs = context.getSharedPreferences("usage_limits", Context.MODE_PRIVATE)
            val currentData = prefs.all
            val currentJson = prefs.getString("usage_limits_config", null)
            
            // Aggressive restore: if current list of limits is empty, restore it
            val shouldRestore = if (currentData.isEmpty() || currentJson.isNullOrEmpty()) {
                true
            } else {
                try {
                    val gson = Gson()
                    val config = gson.fromJson(currentJson, mina.infoxus.data.UsageLimitsConfig::class.java)
                    config == null || config.limits.isEmpty()
                } catch (e: Exception) {
                    true // If corrupted, restore from backup
                }
            }
            
            if (!shouldRestore) {
                Log.d(TAG, "Usage limits data exists and is not empty, skipping restore")
                return false
            }
            
            Log.d(TAG, "Restoring usage limits data using standard key-iteration pattern")
            
            val gson = Gson()
            val json = FileReader(backupFile).use { it.readText() }
            
            // Use the same robust logic as restoreAntiUninstall
            return try {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
                val data = gson.fromJson<Map<String, Any>>(json, type) ?: return false
                
                val editor = prefs.edit()
                data.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is String -> editor.putString(key, value)
                        is Double -> {
                            // Gson parses all numbers as Double by default
                            if (value == value.toInt().toDouble()) editor.putInt(key, value.toInt())
                            else editor.putFloat(key, value.toFloat())
                        }
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
                    }
                }
                editor.commit()
            } catch (e: Exception) {
                Log.e(TAG, "Error in standard restore, trying legacy string restore", e)
                // Fallback for old simple string format
                prefs.edit().putString("usage_limits_config", json).commit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error restoring usage limits data", e)
            return false
        }
    }
    
    fun restoreAllData(context: Context) {
        Log.d(TAG, "Attempting to restore all data from backups...")
        var anyRestored = false
        
        if (restoreFirstLaunch(context)) anyRestored = true
        if (restoreAntiUninstall(context)) anyRestored = true
        if (restoreLocations(context)) anyRestored = true
        if (restoreAutoFocusHours(context)) anyRestored = true
        if (restoreFocusMode(context)) anyRestored = true
        if (restoreUsageLimits(context)) anyRestored = true
        
        if (anyRestored) {
            Log.d(TAG, "Successfully restored some data from backups - refreshing services")
            // After restoring, trigger services to reload
            try {
                context.sendBroadcast(android.content.Intent(MainAccessibilityService.ACTION_REFRESH_ALL))
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing services after restore", e)
            }
        } else {
            Log.d(TAG, "No data to restore from backups")
        }
    }
    
    fun backupAllData(context: Context) {
        Log.d(TAG, "Backing up all critical data...")
        backupFirstLaunch(context)
        backupAntiUninstall(context)
        backupLocations(context)
        backupAutoFocusHours(context)
        backupFocusMode(context)
        backupUsageLimits(context)
        Log.d(TAG, "Backup complete")
    }
}


