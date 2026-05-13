package mina.infoxus

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import mina.infoxus.utils.DataBackupManager

class InFoXusApp: Application() {
  override fun onCreate() {
    super.onCreate()
    
    // Setup Theme
    val prefs = getSharedPreferences("theme_pref", android.content.Context.MODE_PRIVATE)
    val savedMode = prefs.getInt("mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(savedMode)
    
    DynamicColors.applyToActivitiesIfAvailable(this)
    Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this))
    
    // Restore data from backup files if SharedPreferences were cleared
    // Restore data from backup files if SharedPreferences were cleared
    // CRITICAL: Perform this in a background thread to avoid ANR on startup
    Thread {
        try {
            DataBackupManager.restoreAllData(this)
        } catch (e: Exception) {
            Log.e("InFoXusApp", "Error restoring data from backup", e)
        }
    }.start()
  }
}

