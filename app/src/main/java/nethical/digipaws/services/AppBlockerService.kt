package nethical.digipaws.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import nethical.digipaws.Constants
import nethical.digipaws.blockers.AppBlocker
import nethical.digipaws.blockers.FocusModeBlocker
import nethical.digipaws.ui.activity.MainActivity
import nethical.digipaws.ui.activity.WarningActivity
import nethical.digipaws.utils.getCurrentKeyboardPackageName
import nethical.digipaws.utils.getDefaultLauncherPackageName
import nethical.digipaws.utils.LocationPreferencesManager
import nethical.digipaws.data.LocationMode

class AppBlockerService : BaseBlockingService() {

    enum class LocationBlockingDecision {
        BLOCK,       // Location says to block this app
        ALLOW,       // Location says to allow this app (overrides focus mode)
        NO_OPINION   // Location has no opinion, continue with normal blocking logic
    }

    companion object {

        const val INTENT_ACTION_REFRESH_APP_BLOCKER = "nethical.digipaws.refresh.appblocker"

        const val INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN =
            "nethical.digipaws.refresh.appblocker.cooldown"

        const val INTENT_ACTION_REFRESH_FOCUS_MODE = "nethical.digipaws.refresh.focus_mode"
    }

    private var appBlockerWarning = MainActivity.WarningData()
    private val appBlocker = AppBlocker()

    private val focusModeBlocker = FocusModeBlocker()
    private val locationPreferencesManager by lazy { LocationPreferencesManager(this) }

    // responsible to trigger a recheck for what app user is currently using even when no event is received. Used in putting the usage recheck logic into
    // cooldown for an app and later when the cooldown duration is over, trigger a recheck
    private val handler = Handler(Looper.getMainLooper())

    private var updateRunnable: Runnable? = null

    private var lastPackage = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName.toString()
        if (lastPackage == packageName || packageName == getPackageName()) return

        lastPackage = packageName
        Log.d("AppBlockerService", "Switched to app $packageName")

        // Step 1: Check location decision first
        val locationDecision = getLocationBlockingDecision(packageName)
        when (locationDecision) {
            LocationBlockingDecision.BLOCK -> {
                // Location says block - block immediately
                Toast.makeText(this, "This app is blocked at your current location", Toast.LENGTH_LONG).show()
                pressHome()
                return
            }
            LocationBlockingDecision.ALLOW -> {
                // Location says allow - skip focus mode and app blocker
                Log.d("AppBlockerService", "Location allows $packageName, skipping blocking checks")
                return
            }
            LocationBlockingDecision.NO_OPINION -> {
                // Continue with normal blocking logic
            }
        }

        // Step 2: Check focus mode (but first check if location allows it)
        if (isFocusModeAllowedByLocation()) {
            val focusModeResult = focusModeBlocker.doesAppNeedToBeBlocked(packageName)
            if (focusModeResult.isBlocked) {
                handleFocusModeBlockerResult(focusModeResult)
                return
            }
        } else {
            // Location-based control is enabled and we're not in a focus mode zone
            // Skip focus mode check entirely - allow the app
            Log.d("AppBlockerService", "Not in focus mode zone, skipping focus mode check for $packageName")
        }

        // Step 3: Check regular app blocker
        handleAppBlockerResult(appBlocker.doesAppNeedToBeBlocked(packageName), packageName)
    }

    private fun handleAppBlockerResult(result: AppBlocker.AppBlockerResult, packageName: String) {
        Log.d("AppBlockerService", "$packageName result : $result")

        if (result.cheatHoursEndTime != -1L) {
            setUpForcedRefreshChecker(packageName, result.cheatHoursEndTime)
        }
        if (result.cooldownEndTime != -1L) {
            setUpForcedRefreshChecker(packageName, result.cooldownEndTime)
        }

        if (!result.isBlocked) return

        if (appBlockerWarning.isWarningDialogHidden) {
            pressHome()
            return
        }

        pressHome()
        Thread.sleep(300)
        val dialogIntent = Intent(this, WarningActivity::class.java)
        dialogIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        dialogIntent.putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
        dialogIntent.putExtra("result_id", packageName)
        startActivity(dialogIntent)

    }

    private fun handleFocusModeBlockerResult(result: FocusModeBlocker.FocusModeResult) {
        if (result.isRequestingToUpdateSPData) {
            savedPreferencesLoader.saveFocusModeData(focusModeBlocker.focusModeData)
        }

        if (!result.isBlocked) return

        pressHome()
        Toast.makeText(this, "This app is currently under focus mode", Toast.LENGTH_LONG).show()
    }

    override fun onInterrupt() {
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        setupAppBlocker()
        setupFocusMode()

        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_FOCUS_MODE)
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER)
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_FOCUS_MODE -> setupFocusMode()
                INTENT_ACTION_REFRESH_APP_BLOCKER -> setupAppBlocker()
                INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN -> {
                    val interval =
                        intent.getIntExtra("selected_time", appBlockerWarning.timeInterval)
                    val coolPackage = intent.getStringExtra("result_id") ?: ""
                    val cooldownUntil =
                        SystemClock.uptimeMillis() + interval
                    appBlocker.putCooldownTo(
                        coolPackage,
                        cooldownUntil
                    )
                    setUpForcedRefreshChecker(coolPackage, cooldownUntil)

                }
            }

        }
    }

    private fun setUpForcedRefreshChecker(coolPackage: String, endMillis: Long) {
        if (updateRunnable != null) {
            updateRunnable?.let { handler.removeCallbacks(it) }
            updateRunnable = null
        }
        updateRunnable = Runnable {

            Log.d("AppBlockerService", "Triggered Recheck for  $coolPackage")
            try {
                if (rootInActiveWindow.packageName == coolPackage) {
                    handleAppBlockerResult(
                        AppBlocker.AppBlockerResult(true),
                        coolPackage
                    )
                    lastPackage = ""
                    appBlocker.removeCooldownFrom(coolPackage)
                }
            } catch (e: Exception) {
                Log.e("AppBlockerService", e.toString())
                setUpForcedRefreshChecker(coolPackage, endMillis + 60_000) // recheck after a minute
            }
        }

        handler.postAtTime(updateRunnable!!, endMillis)
    }
    private fun setupAppBlocker() {
        appBlocker.blockedAppsList = savedPreferencesLoader.loadBlockedApps().toHashSet()
        appBlocker.refreshCheatHoursData(savedPreferencesLoader.loadAppBlockerCheatHoursList())

        appBlockerWarning = savedPreferencesLoader.loadAppBlockerWarningInfo()
    }

    fun setupFocusMode() {
        focusModeBlocker.refreshCheatHoursData(savedPreferencesLoader.loadAutoFocusHoursList())

        val selectedFocusModeApps = savedPreferencesLoader.getFocusModeSelectedApps().toHashSet()
        val focusModeData = savedPreferencesLoader.getFocusModeData()

        // As all apps wil get blocked except the selected ones, add essential packages that need not be blocked
        // to the list of selected apps
        if (focusModeData.modeType == Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED) {
            selectedFocusModeApps.add("com.android.systemui")
            getDefaultLauncherPackageName(packageManager)?.let { selectedFocusModeApps.add(it) }
            getCurrentKeyboardPackageName(this)?.let { selectedFocusModeApps.add(it) }
        }

        focusModeData.selectedApps = selectedFocusModeApps
        focusModeBlocker.focusModeData = focusModeData

    }

    private fun isFocusModeAllowedByLocation(): Boolean {
        val config = locationPreferencesManager.getConfig()

        // If auto-control focus mode is disabled, focus mode works everywhere (old behavior)
        if (!config.autoControlFocusMode) {
            return true
        }

        // If location blocking is disabled, focus mode works everywhere
        if (!config.isEnabled) {
            return true
        }

        // Get current location state from SharedPreferences (updated by LocationMonitoringService)
        val locationStatePrefs = getSharedPreferences("location_blocker", Context.MODE_PRIVATE)
        val insideZoneIdsJson = locationStatePrefs.getString("current_inside_zones", null)

        if (insideZoneIdsJson.isNullOrEmpty()) {
            // Not inside any zone - focus mode disabled
            return false
        }

        // Parse zone IDs
        val insideZoneIds = try {
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(insideZoneIdsJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // Check if any of the zones we're inside has focus mode enabled
        val insideZones = config.locations.filter { it.id in insideZoneIds && it.enabled }
        return insideZones.any { it.enableFocusModeInZone }
    }

    private fun getLocationBlockingDecision(packageName: String): LocationBlockingDecision {
        val config = locationPreferencesManager.getConfig()

        // If location blocking is disabled, return NO_OPINION
        if (!config.isEnabled) {
            return LocationBlockingDecision.NO_OPINION
        }

        // Get current location state from SharedPreferences (updated by LocationMonitoringService)
        val locationStatePrefs = getSharedPreferences("location_blocker", Context.MODE_PRIVATE)
        val insideZoneIdsJson = locationStatePrefs.getString("current_inside_zones", null)

        if (insideZoneIdsJson.isNullOrEmpty()) {
            // Not inside any zone - check global mode
            return when (config.globalMode) {
                LocationMode.ENABLE_IN_ZONES -> {
                    // Blocking only enabled in zones, we're outside - allow
                    LocationBlockingDecision.ALLOW
                }
                LocationMode.DISABLE_IN_ZONES -> {
                    // Blocking disabled in zones, we're outside - continue normal blocking
                    LocationBlockingDecision.NO_OPINION
                }
                LocationMode.BLOCK_IN_ZONES -> {
                    // Block specific apps in zones, we're outside - continue normal blocking
                    LocationBlockingDecision.NO_OPINION
                }
            }
        }

        // We're inside one or more zones - parse zone IDs
        val insideZoneIds = try {
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(insideZoneIdsJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val insideZones = config.locations.filter { it.id in insideZoneIds && it.enabled }

        when (config.globalMode) {
            LocationMode.BLOCK_IN_ZONES -> {
                // Check if any zone blocks this app
                val blockingZone = insideZones.find { it.shouldBlockApp(packageName) }
                if (blockingZone != null) {
                    return LocationBlockingDecision.BLOCK
                }
                // Not blocked by any zone - continue normal blocking
                return LocationBlockingDecision.NO_OPINION
            }
            LocationMode.ENABLE_IN_ZONES -> {
                // Blocking only enabled in zones - we're inside, so block if not explicitly allowed
                // This mode typically blocks all apps except those in selected list
                // For now, return NO_OPINION to continue with normal blocking logic
                return LocationBlockingDecision.NO_OPINION
            }
            LocationMode.DISABLE_IN_ZONES -> {
                // Blocking disabled in zones - we're inside a zone, so allow
                return LocationBlockingDecision.ALLOW
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(refreshReceiver)
    }

}