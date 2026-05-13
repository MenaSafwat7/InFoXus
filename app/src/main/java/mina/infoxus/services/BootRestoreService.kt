package mina.infoxus.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import mina.infoxus.R
import mina.infoxus.ui.activity.MainActivity
import mina.infoxus.utils.DataBackupManager
import mina.infoxus.services.MainAccessibilityService

class BootRestoreService : Service() {

    companion object {
        private const val TAG = "BootRestoreService"
        private const val NOTIFICATION_ID = 5002
        private const val CHANNEL_ID = "boot_restore_channel"
        const val ACTION_START_RESTORE = "mina.infoxus.action.START_BOOT_RESTORE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RESTORE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID, 
                        createNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }
                // Perform restore in background to avoid blocking the main thread
                Thread {
                    performRestore()
                }.start()
            }
        }
        // Stick around briefly to finish restore even if the system tries to reclaim the service
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Boot Restore Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Restoring app data after boot"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("InFoXus")
            .setContentText("Restoring app settings...")
            .setSmallIcon(R.drawable.app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun performRestore() {
        Log.d(TAG, "Starting boot restore process")
        
        // Restore data from backup
        try {
            DataBackupManager.restoreAllData(this)
            Log.d(TAG, "Data restoration complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring data", e)
        }

        // Send refresh broadcasts to services
        refreshAllServices()

        // Stop the service after a delay to ensure everything is done
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.d(TAG, "Boot restore service stopped")
        }, 30000) // Run for 30 seconds to ensure all services get refreshed
    }

    private fun refreshAllServices() {
        try {
            val packageName = packageName
            val refreshAll = Intent(MainAccessibilityService.ACTION_REFRESH_ALL).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }

            // Send immediately
            sendBroadcast(refreshAll)
            Log.d(TAG, "Sent initial unified refresh broadcast")

            // Send again after 5 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                sendBroadcast(refreshAll)
                Log.d(TAG, "Sent delayed unified refresh broadcast (5s)")
            }, 5000)

            // Send again after 10 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                sendBroadcast(refreshAll)
                Log.d(TAG, "Sent delayed unified refresh broadcast (10s)")
            }, 10000)

            // Start location monitoring if needed
            val locationPrefsManager = mina.infoxus.utils.LocationPreferencesManager(this)
            val config = locationPrefsManager.getConfig()
            if (config.isEnabled && config.locations.isNotEmpty()) {
                val startIntent = Intent(this, LocationMonitoringService::class.java).apply {
                    action = LocationMonitoringService.ACTION_START_MONITORING
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(startIntent)
                    } else {
                        startService(startIntent)
                    }
                    Log.d(TAG, "Started location monitoring service")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting location monitoring service", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing services", e)
        }
    }
}


