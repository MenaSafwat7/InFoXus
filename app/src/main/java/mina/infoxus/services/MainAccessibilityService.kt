package mina.infoxus.services

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import mina.infoxus.Constants
import mina.infoxus.blockers.AppBlocker
import mina.infoxus.blockers.FocusModeBlocker
import mina.infoxus.blockers.KeywordBlocker
import mina.infoxus.blockers.ViewBlocker
import mina.infoxus.data.LocationMode
import mina.infoxus.data.UsageLimitsConfig
import mina.infoxus.data.blockers.KeywordPacks
import mina.infoxus.ui.activity.MainActivity
import mina.infoxus.ui.activity.WarningActivity
import mina.infoxus.ui.overlay.UsageStatOverlayManager
import mina.infoxus.utils.LocationPreferencesManager
import mina.infoxus.utils.TimeTools
import mina.infoxus.utils.getCurrentKeyboardPackageName
import mina.infoxus.utils.getDefaultLauncherPackageName
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import mina.infoxus.utils.UsageStatsHelper
import mina.infoxus.ui.fragments.usage.AllAppsUsageFragment
import mina.infoxus.data.*
import mina.infoxus.services.ShieldManager

class MainAccessibilityService : BaseBlockingService() {

    // --- App Blocker & Focus Mode State ---
    private var appBlockerWarning = MainActivity.WarningData()
    private val appBlocker = AppBlocker()
    private val focusModeBlocker = FocusModeBlocker()
    private val locationPreferencesManager by lazy { LocationPreferencesManager(this) }
    private val blockerHandler = Handler(Looper.getMainLooper())
    private var blockerUpdateRunnable: Runnable? = null
    private var lastAppBlockerPackage = ""

    // --- Anti-Uninstall State ---
    private var isAntiUninstallOn = false
    private var lastSawInfoxusManagementTime = 0L
    private var appDisplayName: String? = null
    private val ANTI_UNINSTALL_TAG = "AntiUninstall"
    private var lastAntiUninstallCheckTime = 0L
    private val ANTI_UNINSTALL_CHECK_INTERVAL = 100L

    // --- Keyword Blocker State ---
    private val keywordBlocker by lazy { KeywordBlocker(this) }
    
    // --- Usage Stats & Digital Wellbeing Sync ---
    private val usageStatsHelper by lazy { UsageStatsHelper(this) }
    private val lastUsageSyncMap = mutableMapOf<String, Long>()
    private val SYNC_INTERVAL_MS = 60000L // Sync with system every minute
    private var keyboardIgnoredApps: HashSet<String> = hashSetOf()
    private var lastKeywordSearchTime = 0L
    private var keywordRefreshCooldown = 1000

    // --- View Blocker State ---
    private val viewBlocker = ViewBlocker()
    private var viewBlockerWarningConfig = MainActivity.WarningData()
    private var lastViewBlockerTime = 0L

    // --- Browser Cache ---
    private val browserCache = mutableMapOf<String, Boolean>()
    
    private var adminHeaderFound = false

    // --- Usage Tracking State ---
    private var screenOnTime: Long = 0
    private var accumulatedTime: Long = 0
    private var isScreenOn = false
    private val trackingHandler = Handler(Looper.getMainLooper())
    private var trackingUpdateRunnable: Runnable? = null
    private val usageStatOverlayManager by lazy { UsageStatOverlayManager(this) }
    private var userYSwipeEventCounter: Long = 0
    private var attentionSpanDataList = mutableMapOf<String, MutableList<AttentionSpanVideoItem>>()
    private var lastVideoViewFoundTime: Long? = null
    private var reelCountData = mutableMapOf<String, Int>()
    private var isReelCountToBeDisplayed = false
    private var isTimeElapsedCounterOn = false
    private var displayOverlayApps = hashSetOf("")
    private var lastScrollTime: Long = 0
    private var lastScrollY: Float = 0f
    private var isScrollInProgress = false
    private var lastTrackingEventTime = 0L
    private var currentPackage: String? = null
    
    // --- Usage Session State (High Precision) ---
    private var appSessionStartMs: Long = 0L
    private var lastPeriodicCommitMs: Long = 0L
    private val PERIODIC_COMMIT_INTERVAL = 30000L // Commit to disk every 30s for safety
    private var appUsageLimitsConfig = UsageLimitsConfig()
    private val exceededLimitPackages = mutableSetOf<String>()
    private val limitCheckHandler = Handler(Looper.getMainLooper())
    private var limitCheckRunnable: Runnable? = null
    
    // Cached location state for usage tracking
    private var cachedInsideZoneIds = mutableSetOf<String>()
    private var lastInsideZoneUpdateMs = 0L
    private val LOCATION_GRACE_PERIOD_MS = 60000L // 1 minute grace for GPS flicker

    companion object {
        const val TAG = "MainAccessibility"
        
        // Intent Actions
        const val ACTION_REFRESH_ALL = "mina.infoxus.refresh.all"
        const val ACTION_REFRESH_FOCUS_MODE = "mina.infoxus.refresh.focus_mode"
        const val ACTION_REFRESH_APP_BLOCKER = "mina.infoxus.refresh.appblocker"
        const val ACTION_REFRESH_APP_BLOCKER_COOLDOWN = "mina.infoxus.refresh.appblocker.cooldown"
        const val ACTION_REFRESH_ANTI_UNINSTALL = "mina.infoxus.refresh.anti_uninstall"
        const val ACTION_REFRESH_KEYWORDS = "mina.infoxus.refresh.keywordblocker.blockedwords"
        const val ACTION_REFRESH_KEYWORD_CONFIG = "mina.infoxus.refresh.keywordblocker.config"
        const val ACTION_REFRESH_VIEW_BLOCKER = "mina.infoxus.refresh.viewblocker"
        const val ACTION_REFRESH_VIEW_BLOCKER_COOLDOWN = "mina.infoxus.refresh.viewblocker.cooldown"
        const val ACTION_REFRESH_USAGE_TRACKER = "mina.infoxus.refresh.usage_tracker"
        const val ACTION_REFRESH_USAGE_LIMITS = "mina.infoxus.refresh.usage_limits"
        
        private val ADMIN_LIST_TITLES = listOf(
            "device admin apps", "device administrators", "admin apps",
            "activate device admin", "deactivate device admin", "device admin"
        )

        private const val TRACKING_UPDATE_INTERVAL = 1000L
        private const val SCROLL_DEBOUNCE_TIME = 100L
        private const val MIN_SCROLL_DISTANCE = 10f
        
        private val MIN_SCROLL_THRESHOLD = mapOf(
            "com.ss.android.ugc.trill" to 1,
            "com.zhiliaoapp.musically" to 1,
            "com.ss.android.ugc.aweme" to 1,
            "com.google.android.youtube" to 2,
            "app.revanced.android.youtube" to 2,
            "com.facebook.katana" to 2,
            "com.instagram.android" to 2
        )

        private val SUPPORTED_TRACKING_APPS = hashSetOf(
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.aweme",
            "com.instagram.android",
            "com.google.android.youtube",
            "app.revanced.android.youtube",
            "com.facebook.katana"
        )

        private val TIKTOK_PACKAGES = hashSetOf(
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.aweme"
        )
        const val VIDEO_TYPE_REEL = 1
        private const val HEARTBEAT_INTERVAL = 100L
    }

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                ShieldManager.heartbeatScan(this@MainAccessibilityService)
            } catch (e: Exception) {}
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL)
        }
    }

    private fun scanAllWindowsForActiveApps(): Boolean {
        try {
            val wins = windows ?: return false
            val appNames = listOf(appDisplayName?.lowercase() ?: "infoxus", "mina.infoxus")
            // Use specific phrases to avoid false positives with common words
            val markers = listOf("active apps", "running apps", "foreground services", "apps are active")
            
            for (win in wins) {
                val root = win.root ?: continue
                try {
                    val pkg = root.packageName?.toString()?.lowercase() ?: ""

                    // Packages that are NOT system/security apps ط£آ¢أ¢â€ڑآ¬أ¢â‚¬â€Œ their windows must never trigger anti-uninstall
                    val SAFE_PRODUCTIVITY_PACKAGES = listOf(
                        "com.google.android.keep",       // Google Keep
                        "com.samsung.android.app.notes", // Samsung Notes
                        "com.microsoft.onenote",          // OneNote
                        "com.simplenote",                 // Simplenote
                        "com.evernote",                   // Evernote
                        "com.obsidian",                   // Obsidian
                        "org.joplin",                     // Joplin
                        "com.notewise",                   // Notewise
                        "com.ichi2.anki",                 // Anki
                        "net.cozic.joplin"
                    )

                    // CRITICAL: Skip windows from known note/productivity apps to avoid false positives
                    if (SAFE_PRODUCTIVITY_PACKAGES.any { pkg == it || pkg.startsWith(it) }) {
                        continue
                    }
                    
                    // Also skip any package that is NOT a system/settings/security app
                    val isSystemSecurityPkg = pkg.contains("settings") ||
                        pkg.contains("systemui") ||
                        pkg.contains("packageinstaller") ||
                        pkg.contains("installer") ||
                        pkg.contains("security") ||
                        pkg.contains("safetycenter") ||
                        pkg.contains("permission") ||
                        pkg.contains("canta") ||
                        pkg.contains("shizuku") ||
                        pkg.contains("ladb") ||
                        pkg.contains("wireless.adb") ||
                        pkg == "android"
                    
                    if (!isSystemSecurityPkg) {
                        continue
                    }

                    // CRITICAL: Never block if the window belongs to a launcher or our own app
                    if (pkg.contains("mina.infoxus") || isLauncher(pkg)) {
                        continue
                    }
                    
                    val text = getNodeText(root, 0, 15).lowercase()
                    val hasAppName = appNames.any { text.contains(it) }
                    
                    // If we see the "Active apps" header AND our app name AND a stop button, it's the target
                    // Note: We avoid "app is running" here because it triggers on normal notifications
                    val hasActiveHeader = text.contains("active apps") || 
                                         text.contains("running apps") || 
                                         text.contains("foreground services") || 
                                         text.contains("apps are active")
                    val hasStopButton = text.contains("stop")
                    
                    if (hasAppName && (hasActiveHeader && hasStopButton)) {
                        Log.d(ANTI_UNINSTALL_TAG, "Active Apps dialog detected in pkg: $pkg")
                        return true
                    }
                } finally {
                    try { root.recycle() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
        return false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action == null) return
            
            val action = intent.action
            Log.d(TAG, "Received broadcast: $action")
            when (action) {
                ACTION_REFRESH_ALL -> refreshAllData()
                ACTION_REFRESH_FOCUS_MODE -> setupFocusMode()
                ACTION_REFRESH_APP_BLOCKER -> {
                    setupAppBlocker()
                    updateLocationCache()
                    syncUsageWithSystem()
                }
                ACTION_REFRESH_APP_BLOCKER_COOLDOWN -> handleAppBlockerCooldown(intent)
                ACTION_REFRESH_ANTI_UNINSTALL -> setupAntiUninstall()
                ACTION_REFRESH_KEYWORDS -> setupBlockedWords()
                ACTION_REFRESH_KEYWORD_CONFIG -> setupKeywordConfig()
                ACTION_REFRESH_USAGE_TRACKER -> setupUsageTracker()
                ACTION_REFRESH_USAGE_LIMITS -> {
                    setupUsageLimits()
                    syncUsageWithSystem()
                }
                Intent.ACTION_TIME_TICK -> checkDayChange()
            }
        }
    }

    private fun updateLocationCache() {
        try {
            val locationPrefs = getSharedPreferences("location_blocker", Context.MODE_PRIVATE)
            val json = locationPrefs.getString("current_inside_zones", null)
            if (json != null) {
                val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
                val zoneIds: Set<String> = com.google.gson.Gson().fromJson(json, type) ?: emptySet()
                if (zoneIds.isNotEmpty()) {
                    cachedInsideZoneIds.clear()
                    cachedInsideZoneIds.addAll(zoneIds)
                    lastInsideZoneUpdateMs = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating location cache", e)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isTimeElapsedCounterOn) return
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""
        Log.d("SHIELD_DEBUG", "EVENT: type=${event.eventType}, pkg=$packageName, class=$className")

        // 1. ShieldManager Implementation (Active Defense)
        // This handles Uninstall, Settings, Time Limits, and Rule blocking via Window Overlays.
        ShieldManager.onAccessibilityEvent(event, this)

        // 2. Keyword & Browser Protection (Throttled for performance)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            // Block unapproved browsers if Anti-Uninstall is on
            if (isAntiUninstallOn && isPackageABrowser(packageName) && 
                packageName != "com.android.chrome" && 
                packageName != "com.brave.browser" &&
                packageName != "com.android.vending") {
                
                pressHome(0)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "Only Chrome and Brave are allowed", Toast.LENGTH_LONG).show()
                }
                return
            }
        }

        // 3. Usage Limit Hard-Block Fallback
        if (packageName != this.packageName &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            exceededLimitPackages.contains(packageName)) {
            
            val hour = appUsageLimitsConfig.resetHour
            val minute = appUsageLimitsConfig.resetMinute
            val amPm = if (hour < 12) "AM" else "PM"
            val hour12 = if (hour == 0 || hour == 12) 12 else hour % 12
            val resetTimeStr = String.format("%02d:%02d %s", hour12, minute, amPm)
            
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Daily limit reached. Available after $resetTimeStr", Toast.LENGTH_LONG).show()
            }
            pressHome(0)
            return
        }

        // 3.5 Usage Tracking
        handleUsageTrackingEvents(event)

        // 4. App Blocker & Focus Mode
        if (packageName != lastAppBlockerPackage && packageName != this.packageName) {
            lastAppBlockerPackage = packageName
            Log.d(TAG, "App switched to: $packageName")

            val locationDecision = getLocationBlockingDecision(packageName)
            if (locationDecision == LocationBlockingDecision.BLOCK) {
                Toast.makeText(this, "This app is blocked at your current location", Toast.LENGTH_LONG).show()
                pressHome(0)
                return
            }

            if (locationDecision != LocationBlockingDecision.ALLOW) {
                if (isFocusModeAllowedByLocation()) {
                    val focusModeResult = focusModeBlocker.doesAppNeedToBeBlocked(packageName)
                    if (focusModeResult.isBlocked) {
                        handleFocusModeBlockerResult(focusModeResult)
                        return
                    }
                }

                val appBlockerResult = appBlocker.doesAppNeedToBeBlocked(packageName)
                if (appBlockerResult.isBlocked) {
                    handleAppBlockerResult(appBlockerResult, packageName)
                    return
                }
            }
        }

        // 5. Usage Tracking & Overlay Handling
        handleUsageTrackingEvents(event)

        // 6. Keyword Blocker (Throttled)
        if (SystemClock.uptimeMillis() - lastKeywordSearchTime > keywordRefreshCooldown) {
            // CRITICAL: Never block keywords in launchers, home screens, or our own app to avoid false positives in the App Drawer.
            if (packageName != this.packageName && !isLauncher(packageName) && !keyboardIgnoredApps.contains(packageName)) {
                val isBrowser = KeywordBlocker.URL_BAR_ID_LIST.containsKey(packageName)
                if (isBrowser || keywordBlocker.isSearchAllTextFields) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        val result = keywordBlocker.checkIfUserGettingFreaky(root, event)
                        if (result.resultDetectWord != null) {
                            handleKeywordBlockerResult(result)
                        }
                        try { root.recycle() } catch (e: Exception) {}
                    }
                    lastKeywordSearchTime = SystemClock.uptimeMillis()
                }
            }
        }

        // 5. View Blocker (Throttled)
        
        // 6. Final: Update package tracking and usage stats
        handlePackageChange(packageName, event.eventType)
    }

    // --- Core Logic Implementation ---

    private fun handlePackageChange(newPkg: String, eventType: Int) {
        if (newPkg.isEmpty() || newPkg == currentPackage) return

        // 1. Commit the session for the app we are LEAVING
        commitCurrentSession()

        // 2. Switch to the new package
        currentPackage = newPkg
        Log.d("UsageTracking", "Package changed to: $newPkg")

        // 3. Start a new session if the new app is in any limit group
        val now = System.currentTimeMillis()
        appSessionStartMs = if (appUsageLimitsConfig.limits.any { it.enabled && it.packageNames.contains(newPkg) }) {
            now
        } else {
            0L
        }
        lastPeriodicCommitMs = now

        // 4. Run enforcement immediately on app switch
        enforceUsageLimits()
    }

    private fun handleUsageTrackingEvents(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""
        
        // Overlay Management
        if (displayOverlayApps.contains(packageName)) {
            if (Settings.canDrawOverlays(this)) {
                usageStatOverlayManager.startDisplaying()
            }
        } else if (usageStatOverlayManager.isOverlayVisible) {
            usageStatOverlayManager.removeOverlay()
        }

        // Reel Tracking (TYPE_WINDOW_CONTENT_CHANGED)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && 
            SystemClock.uptimeMillis() - lastTrackingEventTime > 2000) {
            
            if (SUPPORTED_TRACKING_APPS.contains(packageName)) {
                var foundBlockedView = false
                val root = rootInActiveWindow
                if (root != null) {
                    ViewBlocker.BLOCKED_VIEW_ID_LIST.forEach { viewId ->
                        val viewNode = ViewBlocker.findElementById(root, viewId)
                        if (viewNode != null) {
                            foundBlockedView = true
                            viewNode.recycle()
                        }
                    }
                    root.recycle()
                }
                if (!foundBlockedView) hideReelTrackingView()
            } else {
                hideReelTrackingView()
            }
            lastTrackingEventTime = SystemClock.uptimeMillis()
        }

        // Scroll Tracking (TYPE_VIEW_SCROLLED)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val currentTime = System.currentTimeMillis()
            val scrollY = event.scrollY.toFloat()

            if (!isScrollInProgress || (currentTime - lastScrollTime > SCROLL_DEBOUNCE_TIME && 
                Math.abs(scrollY - lastScrollY) > MIN_SCROLL_DISTANCE)) {

                handleSpecificAppScroll(event)
                lastScrollY = scrollY
                lastScrollTime = currentTime
            }
        }
    }

    private fun handleSpecificAppScroll(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""
        val className = event.source?.className?.toString() ?: ""
        val root = rootInActiveWindow ?: return

        when {
            TIKTOK_PACKAGES.contains(packageName) && className == "androidx.viewpager.widget.ViewPager" -> {
                handleScrollEvent(packageName)
            }
            packageName == "com.facebook.katana" && className == "androidx.recyclerview.widget.RecyclerView" -> {
                val nodes = root.findAccessibilityNodeInfosByText("FbShortsComposerAttachmentComponentSpec_STICKER")
                if (nodes.isNotEmpty()) handleScrollEvent(packageName)
            }
            packageName == "com.instagram.android" && className == "androidx.viewpager.widget.ViewPager" -> {
                if (ViewBlocker.findElementById(root, "com.instagram.android:id/root_clips_layout") != null) {
                    handleScrollEvent(packageName)
                } else {
                    hideReelTrackingView()
                }
            }
            (packageName == "com.google.android.youtube" || packageName == "app.revanced.android.youtube") && 
            className == "android.support.v7.widget.RecyclerView" -> {
                val reelId = if (packageName.contains("revanced")) "app.revanced.android.youtube:id/reel_recycler" 
                             else "com.google.android.youtube:id/reel_recycler"
                val commentId = if (packageName.contains("revanced")) "app.revanced.android.youtube:id/engagement_panel_content" 
                                else "com.google.android.youtube:id/engagement_panel_content"
                
                if (ViewBlocker.findElementById(root, reelId) != null && 
                    ViewBlocker.findElementById(root, commentId) == null) {
                    handleScrollEvent(packageName)
                } else {
                    hideReelTrackingView()
                }
            }
        }
        try { root.recycle() } catch (e: Exception) {}
    }

    // --- Setup & Configuration Methods ---

    private fun refreshAllData() {
        setupAppBlocker()
        setupFocusMode()
        setupAntiUninstall()
        setupBlockedWords()
        setupKeywordConfig()
        setupViewBlocker()
        setupUsageTracker()
        setupUsageLimits()
    }

    private fun setupAppBlocker() {
        try {
            val blockedApps = savedPreferencesLoader.loadBlockedApps()
            val cheatHours = savedPreferencesLoader.loadAppBlockerCheatHoursList()
            appBlockerWarning = savedPreferencesLoader.loadAppBlockerWarningInfo()
            viewBlockerWarningConfig = appBlockerWarning
            appBlocker.blockedAppsList = blockedApps.toHashSet()
            appBlocker.refreshCheatHoursData(cheatHours)
            Log.d(TAG, "App Blocker setup: ${blockedApps.size} apps")
        } catch (e: Exception) { Log.e(TAG, "AppBlocker setup error", e) }
    }

    private fun setupFocusMode() {
        try {
            val focusData = savedPreferencesLoader.getFocusModeData()
            val selectedApps = savedPreferencesLoader.getFocusModeSelectedApps().toHashSet()
            val autoFocus = savedPreferencesLoader.loadAutoFocusHoursList()
            
            if (focusData.modeType == Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED) {
                selectedApps.add("com.android.systemui")
                getDefaultLauncherPackageName(packageManager)?.let { selectedApps.add(it) }
                getCurrentKeyboardPackageName(this)?.let { selectedApps.add(it) }
            }
            focusData.selectedApps = selectedApps
            focusModeBlocker.focusModeData = focusData
            focusModeBlocker.refreshCheatHoursData(autoFocus)

            // Wire Focus Mode state to ShieldManager (session-scoped features only)
            ShieldManager.isSessionActive.value = focusData.isTurnedOn
            if (!focusData.isTurnedOn) {
                // Only reset time-limit tracking — do NOT touch the anti-uninstall overlay
                ShieldManager.resetTimeLimitState()
            }
        } catch (e: Exception) { Log.e(TAG, "FocusMode setup error", e) }
    }

    private fun setupAntiUninstall() {
        try {
            val prefs = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
            isAntiUninstallOn = prefs.getBoolean("is_anti_uninstall_on", false)
            
            // Sync isAntiUninstallOn with ShieldManager — this is the master gate
            ShieldManager.isAntiUninstallOn = isAntiUninstallOn
            
            // Debug Toast
            Handler(Looper.getMainLooper()).post {
                val status = if (isAntiUninstallOn) "ENABLED" else "DISABLED"
                Toast.makeText(this, "InFoXus Shield: $status", Toast.LENGTH_SHORT).show()
            }
            
            ShieldManager.config = ShieldManager.config.copy(
                selfProtectionEnabled = isAntiUninstallOn,
                uninstallProtectionEnabled = isAntiUninstallOn
            )
            ShieldManager.shieldedPackages = if (isAntiUninstallOn) {
                setOf("mina.infoxus")
            } else {
                emptySet()
            }
            Log.d(TAG, "ShieldManager armed: isAntiUninstallOn=$isAntiUninstallOn")
            
            // Check if timed mode has expired
            if (isAntiUninstallOn) {
                val mode = prefs.getInt("mode", -1)
                if (mode == Constants.ANTI_UNINSTALL_TIMED_MODE) {
                    val dateString = prefs.getString("date", null)
                    if (!dateString.isNullOrEmpty()) {
                        try {
                            val parts = dateString.split("/")
                            if (parts.size >= 3) {
                                val targetDate = java.util.Calendar.getInstance().apply {
                                    set(parts[2].trim().toInt(), parts[0].trim().toInt() - 1, parts[1].trim().toInt(), 0, 0, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                val today = java.util.Calendar.getInstance().apply {
                                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                                    set(java.util.Calendar.MINUTE, 0)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                if (targetDate.before(today)) {
                                    isAntiUninstallOn = false
                                    ShieldManager.isAntiUninstallOn = false  // ADD THIS LINE
                                    ShieldManager.disarm()                   // ADD THIS LINE
                                    prefs.edit().putBoolean("is_anti_uninstall_on", false).apply()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking timed mode expiry", e)
                        }
                    }
                }
            }
            
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appDisplayName = pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) { 
            Log.e(TAG, "AntiUninstall setup error", e)
            appDisplayName = "InFoXus"
        }
    }

    private fun setupBlockedWords() {
        Thread {
            try {
                val keywords = savedPreferencesLoader.loadBlockedKeywords().toMutableSet()
                val sites = savedPreferencesLoader.loadBlockedSites().toHashSet()
                val sp = getSharedPreferences("keyword_blocker_packs", Context.MODE_PRIVATE)
                if (sp.getBoolean("adult_blocker", false)) keywords.addAll(KeywordPacks.adultKeywords)
                
                if (savedPreferencesLoader.loadIsAdultSitesBlockerEnabled()) {
                    try {
                        assets.open("hosts.txt").bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                val parts = line.split(Regex("\\s+"))
                                if (parts.size >= 2) sites.add(parts[1].lowercase(Locale.ROOT).trim())
                            }
                        }
                    } catch (e: Exception) { Log.e(TAG, "hosts.txt error", e) }
                }
                keywordBlocker.blockedKeyword = keywords.toHashSet()
                keywordBlocker.blockedSites = sites
            } catch (e: Exception) { Log.e(TAG, "Keyword setup error", e) }
        }.start()
    }

    private fun setupKeywordConfig() {
        val sp = getSharedPreferences("keyword_blocker_configs", Context.MODE_PRIVATE)
        keywordBlocker.isSearchAllTextFields = sp.getBoolean("search_all_text_fields", false)
        keywordBlocker.redirectUrl = sp.getString("redirect_url", "https://www.google.com") ?: "https://www.google.com"
        keywordRefreshCooldown = if (keywordBlocker.isSearchAllTextFields) 5000 else 1000
        keyboardIgnoredApps = savedPreferencesLoader.getKeywordBlockerIgnoredApps().toHashSet()
    }

    private fun setupViewBlocker() {
        viewBlockerWarningConfig = savedPreferencesLoader.loadAppBlockerWarningInfo()
        val cheatPrefs = getSharedPreferences("cheat_hours", Context.MODE_PRIVATE)
        viewBlocker.cheatMinuteStartTime = cheatPrefs.getInt("view_blocker_start_time", -1)
        viewBlocker.cheatMinutesEndTIme = cheatPrefs.getInt("view_blocker_end_time", -1)
        
        val reelPrefs = getSharedPreferences("config_reels", Context.MODE_PRIVATE)
        viewBlocker.isIGInboxReelAllowed = reelPrefs.getBoolean("is_reel_inbox", false)
        viewBlocker.isFirstReelInFeedAllowed = reelPrefs.getBoolean("is_reel_first", false)
    }

    private fun setupUsageTracker() {
        val sp = getSharedPreferences("config_tracker", Context.MODE_PRIVATE)
        isReelCountToBeDisplayed = sp.getBoolean("is_reel_counter", false)
        isTimeElapsedCounterOn = sp.getBoolean("is_time_elapsed", false)
        displayOverlayApps = savedPreferencesLoader.getOverlayApps().toHashSet()
        if (isReelCountToBeDisplayed) displayOverlayApps.addAll(SUPPORTED_TRACKING_APPS)
        
        if (isTimeElapsedCounterOn) {
            usageStatOverlayManager.binding?.timeElapsedTxt?.visibility = View.VISIBLE
            if ((getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) handleScreenOn()
        } else {
            usageStatOverlayManager.binding?.timeElapsedTxt?.visibility = View.GONE
            handleScreenOff()
        }
        attentionSpanDataList = savedPreferencesLoader.loadUsageHoursAttentionSpanData()
        reelCountData = savedPreferencesLoader.getReelsScrolled()
    }

    private fun setupUsageLimits() {
        appUsageLimitsConfig = savedPreferencesLoader.loadUsageLimits()
        val today = TimeTools.getUsageCycleDate(appUsageLimitsConfig.resetHour, appUsageLimitsConfig.resetMinute)
        
        // Prevent reset if the system clock is not yet synchronized (e.g., year is 1970)
        val currentYear = try { today.split(" ").last().toInt() } catch (e: Exception) { 0 }
        if (currentYear < 2024) {
            Log.w(TAG, "System clock not synchronized ($today), skipping usage limit reset")
            return
        }
        
        exceededLimitPackages.clear()
        
        var modified = false
        appUsageLimitsConfig.limits.forEach { limit ->
            if (limit.lastResetDate != today) {
                limit.currentUsageMinutes = 0f
                limit.appUsageMap = emptyMap()
                limit.lastResetDate = today
                modified = true
            } else if (limit.isLimitExceeded()) {
                exceededLimitPackages.addAll(limit.packageNames)
            }
        }
        if (modified) savedPreferencesLoader.saveUsageLimits(appUsageLimitsConfig)
    }

    private fun checkDayChange() {
        val today = TimeTools.getUsageCycleDate(appUsageLimitsConfig.resetHour, appUsageLimitsConfig.resetMinute)
        
        // Prevent reset if the system clock is not yet synchronized
        val currentYear = try { today.split(" ").last().toInt() } catch (e: Exception) { 0 }
        if (currentYear < 2024) return

        var modified = false
        appUsageLimitsConfig.limits.forEach { limit ->
            if (limit.lastResetDate != today) {
                limit.currentUsageMinutes = 0f
                limit.appUsageMap = emptyMap()
                limit.lastResetDate = today
                modified = true
            }
        }
        
        exceededLimitPackages.clear()
        appUsageLimitsConfig.limits.forEach { limit ->
            if (limit.enabled && limit.isLimitExceeded()) {
                exceededLimitPackages.addAll(limit.packageNames)
            }
        }
        if (modified) {
            savedPreferencesLoader.saveUsageLimits(appUsageLimitsConfig)
            Log.d(TAG, "Daily usage limits reset for date: $today")
        }
    }

    // --- Helper Logic & Event Handlers ---

    private fun checkAndBlockSettings() {
        if (!isAntiUninstallOn) return
        
        val rootNodeToScan = rootInActiveWindow ?: return
        var targetRoot: AccessibilityNodeInfo? = null
        var windowTitle = ""
        
        try {
            // Priority: Try to get the actual active application window title
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val currentWindows = windows
                    val activeWindow = currentWindows?.find { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    if (activeWindow != null) {
                        targetRoot = activeWindow.root
                        windowTitle = activeWindow.title?.toString()?.lowercase() ?: ""
                    }
                }
            } catch (e: Exception) {
                Log.d(ANTI_UNINSTALL_TAG, "Error accessing windows", e)
            }

            val currentPackage = rootNodeToScan.packageName?.toString() ?: ""
            
            // Never block our own apps or safe productivity/note apps
            val SAFE_PRODUCTIVITY_PACKAGES = listOf(
                "com.google.android.keep",       // Google Keep
                "com.samsung.android.app.notes", // Samsung Notes
                "com.microsoft.onenote",          // OneNote
                "com.simplenote",                 // Simplenote
                "com.evernote",                   // Evernote
                "com.obsidian",                   // Obsidian
                "org.joplin",                     // Joplin
                "com.notewise",                   // Notewise
                "com.ichi2.anki",                 // Anki
                "net.cozic.joplin"
            )
            
            if (currentPackage.contains("mina.infoxus") || 
                SAFE_PRODUCTIVITY_PACKAGES.any { currentPackage == it || currentPackage.startsWith(it) }) return
            
            val appNames = listOf(
                appDisplayName?.lowercase() ?: "infoxus", 
                "mina.infoxus"
            ).filter { it.isNotEmpty() }
            
            val dangerousKeywords = listOf(
                "uninstall", "force stop", "deactivate", "clear data", "clear storage",
                "disable", "remove", "delete", "stop", "turn off", "off", "usb debugging",
                "app info", "kill", "hide", "hidden", "misbehave", "active apps", "running apps"
            )

            // Fallback to rootInActiveWindow if window search failed
            val nodeToScan = targetRoot ?: rootNodeToScan

            // Optimized: Use native search instead of recursive getNodeText for initial scan
            // This is significantly faster and prevents ANRs
            val hasAppNameNodes = appNames.any { name -> 
                nodeToScan.findAccessibilityNodeInfosByText(name).isNotEmpty() 
            }
            
            // Native optimization: early detection of dangerous keywords
            val nativeHasDangerousNodes = dangerousKeywords.any { 
                nodeToScan.findAccessibilityNodeInfosByText(it).isNotEmpty()
            }

            val isLauncher = isLauncher(currentPackage)
            
            // --- GLOBAL WINDOW FAST-PATH (Multi-Window Scanning) ---
            val isSystemContext = currentPackage.contains("settings") || currentPackage == "android" || currentPackage.contains("systemui")
            
            // We scan ALL windows for the app name and dangerous keywords.
            // This catches popups (window A) that appear over Settings (window B).
            var globalHasAppName = false
            var globalHasDangerous = false
            
            try {
                val allWindows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) windows else emptyList()
                for (window in allWindows) {
                    val root = try { window.root } catch (e: Exception) { null } ?: continue
                    
                    if (!globalHasAppName) {
                        globalHasAppName = appNames.any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() } ||
                                          root.findAccessibilityNodeInfosByText("InFoXus").isNotEmpty()
                    }
                    
                    if (!globalHasDangerous) {
                        // For system popups, 'OK' is a dangerous trigger if we already suspect InFoXus
                        globalHasDangerous = dangerousKeywords.any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() } ||
                                            (currentPackage == "android" && root.findAccessibilityNodeInfosByText("OK").isNotEmpty())
                    }
                    
                    try { root.recycle() } catch (e: Exception) {}
                    if (globalHasAppName && globalHasDangerous) break
                }
            } catch (e: Exception) {}

            // --- SURGICAL FAST-PATH (Package-Specific) ---
            // We only block in specific security-sensitive contexts to avoid false positives.
            
            // 1. SETTINGS / APP INFO SHIELD:
            if (currentPackage.contains("settings") || currentPackage.contains("securitycenter")) {
                // We ONLY block if we see the app name AND specific management buttons.
                // This allows the user to scroll through the 'Manage Apps' list and see the app,
                // relying on the onAccessibilityEvent click handler to block the actual press.
                if (globalHasAppName) {
                    // Require strict App Info row markers to differentiate from the list screen
                    val hasManagementButtons = nodeToScan.findAccessibilityNodeInfosByText("archive").isNotEmpty() || 
                                              (nodeToScan.findAccessibilityNodeInfosByText("force stop").isNotEmpty() &&
                                               nodeToScan.findAccessibilityNodeInfosByText("storage").isNotEmpty() &&
                                               nodeToScan.findAccessibilityNodeInfosByText("permissions").isNotEmpty())
                    
                    if (hasManagementButtons) {
                        Log.d(ANTI_UNINSTALL_TAG, "APP INFO FAST-PATH TRIGGERED")
                        triggerAntiUninstallBlock("InFoXus Management")
                        return
                    }
                }
            }
            
            // 2. SYSTEMUI / ACTIVE APPS SHIELD:
            if (currentPackage.contains("systemui")) {
                // Only block if we see InFoXus AND we are in an 'Active Apps' context.
                val isActiveAppsContext = nodeToScan.findAccessibilityNodeInfosByText("active apps").isNotEmpty() ||
                                         nodeToScan.findAccessibilityNodeInfosByText("running apps").isNotEmpty()
                
                if (globalHasAppName && isActiveAppsContext && globalHasDangerous) {
                    Log.d(ANTI_UNINSTALL_TAG, "ACTIVE APPS FAST-PATH TRIGGERED")
                    triggerAntiUninstallBlock("Active Apps")
                    return
                }
            }
            
            // 3. SYSTEM DIALOGS (Confirmations):
            if (currentPackage == "android" && globalHasDangerous) {
                // If a system popup (like "Force stop?") appears while InFoXus was recently on screen
                if (globalHasAppName) {
                    Log.d(ANTI_UNINSTALL_TAG, "SYSTEM POPUP FAST-PATH TRIGGERED")
                    triggerAntiUninstallBlock("Deactivation Popup")
                    return
                }
            }
            
            // If the app name is not even on screen, we can skip heavy text extraction
            // Unless we are in a dangerous app like an ADB tool
            val isPackageInstaller = currentPackage.contains("packageinstaller") || 
                                     currentPackage.contains("installer")
            val isADBTool = currentPackage.contains("canta") || 
                            currentPackage.contains("shizuku") || 
                            currentPackage.contains("ladb") ||
                            currentPackage.contains("samruston.canta") ||
                            currentPackage.contains("samolego.canta") ||
                            currentPackage.contains("wireless.adb")
            
            val isSettingsApp = currentPackage.contains("settings") || 
                                 currentPackage.contains("vending") ||
                                 currentPackage.contains("securitycenter") ||
                                 currentPackage.contains("security") ||
                                 currentPackage.contains("safetycenter") ||
                                 currentPackage.contains("packageinstaller") ||
                                 currentPackage.contains("systemui") ||
                                 currentPackage == "android" ||
                                 isADBTool
            
            // Since launchers return early above, we only need to check settings/installer here.
            // We use the initial node search result here.
            if (!hasAppNameNodes && !isSettingsApp && !isPackageInstaller) return

            // If we have an app name or are in a settings app, then we do the text extraction
            // but we use a version with depth limit to be safe
            // For launchers, we use a deeper scan to find the app name in complex drawers
            val scanDepth = if (isLauncher || isSettingsApp || packageName.lowercase().contains("securitycenter")) 20 else 10
            val screenText = getNodeText(nodeToScan, maxDepth = scanDepth).lowercase()

            // Robust check: even if native search fails, check the full screen text
            val hasAppName = hasAppNameNodes || appNames.any { screenText.contains(it.lowercase()) }
            val nativeHasDangerous = nativeHasDangerousNodes || dangerousKeywords.any { screenText.contains(it.lowercase()) }
            
            // If we couldn't get title from WindowInfo, check if it's on screen (top-level node)
            if (windowTitle.isEmpty()) {
                windowTitle = nodeToScan.text?.toString()?.lowercase() ?: ""
            }
            
            val appNameInTitle = appNames.any { name -> windowTitle.contains(name) }
            // val hasAppName already computed above
            
            // Refined: Is this an individual App Info screen vs just a list?
            // Broadened patterns for MIUI/HyperOS/custom ROMs that use different text.
            // Refined: Is this an individual App Info screen vs just a list?
            // We require multiple markers to be present to avoid false positives in lists.
            val isAppInfoScreen = (screenText.contains("force stop") && screenText.contains("uninstall") && screenText.contains("storage")) || 
                                  screenText.contains("archive") || screenText.contains("force stop") || 
                                  (screenText.contains("storage") && screenText.contains("data usage") && screenText.contains("permissions"))
            
            // Refined: Is this a general list of all apps? (Expanded for Custom ROMs)
            val isGeneralAppList = screenText.contains("all apps") || 
                                   windowTitle.contains("all apps") ||
                                   screenText.contains("installed apps") || 
                                   windowTitle.contains("installed apps") ||
                                   screenText.contains("search apps") || 
                                   screenText.contains("choose app") ||
                                   screenText.contains("app list") ||
                                   screenText.contains("app management") ||
                                   screenText.contains("manage apps") ||
                                   windowTitle.contains("manage apps") ||
                                   screenText.contains("apps (") ||
                                   screenText.contains("app permissions") ||
                                   screenText.contains("special app access") ||
                                   windowTitle.contains("management") ||
                                   screenText.contains("hide apps") ||
                                   screenText.contains("hidden apps")
            
            val hasDangerous = (nativeHasDangerous || dangerousKeywords.any { keyword -> screenText.contains(keyword) }) && !isGeneralAppList
            
             // Special handling for ADB tools (Canta, Shizuku, etc.)
             // If we are in one of these tools and see our app name, BLOCK IMMEDIATELY.
             // We also check all windows for the app name if we're in an ADB tool to be extra safe
             var hasAppNameInAnyWindow = hasAppName
             if (isADBTool && !hasAppNameInAnyWindow) {
                 try {
                     val wins = windows
                     if (wins != null) {
                         for (win in wins) {
                             val winRoot = win.root ?: continue
                             val winText = getNodeText(winRoot).lowercase()
                             if (appNames.any { winText.contains(it) }) {
                                 hasAppNameInAnyWindow = true
                                 winRoot.recycle()
                                 break
                             }
                             winRoot.recycle()
                         }
                     }
                 } catch (e: Exception) {}
             }

            val isDangerousADBAction = isADBTool && hasAppNameInAnyWindow

            // EXTREMELY SPECIFIC ADMIN DEACTIVATION TRIGGERS
            val isDeactivationScreen = (screenText.contains("deactivate this device admin app") || 
                                       screenText.contains("deactivate this admin")) && hasAppName
            
            // Accessibility Setting Protection:
            // Block the specific accessibility settings page for our app to prevent disabling the service.
            val isAccessibilityContext = screenText.contains("accessibility") || 
                                        windowTitle.contains("accessibility") ||
                                        screenText.contains("downloaded apps") ||
                                        screenText.contains("services") ||
                                        appNameInTitle
            
            val isOurAccessibilitySettings = isAccessibilityContext && hasAppName && !isGeneralAppList && 
                (screenText.contains("use ") || screenText.contains("shortcut") || 
                 screenText.contains("service") || screenText.matches(Regex(".*\\bstop\\b.*")) ||
                 screenText.matches(Regex(".*\\boff\\b.*")) || screenText.matches(Regex(".*\\bon\\b.*")))

            // 1. Critical: Scan all windows for the "Active apps" dialog markers
            // We do this regardless of currentPackage because the dialog is an overlay window.
            var isActiveAppsInAnyWindow = false
            try {
                val wins = windows
                if (wins != null) {
                    for (win in wins) {
                        val winRoot = win.root ?: continue
                        try {
                            val winPkg = winRoot.packageName?.toString()?.lowercase() ?: ""
                            
                            // CRITICAL: If this is a Settings list, don't treat it as an "Active Apps" dialog
                            if (winPkg.contains("settings") && isGeneralAppList) {
                                winRoot.recycle()
                                continue
                            }
                            
                            // CRITICAL: Skip windows from known note/productivity apps to avoid false positives
                            // (e.g. a note titled "infoxus" inside Google Keep must not trigger a block)
                            if (SAFE_PRODUCTIVITY_PACKAGES.any { winPkg == it || winPkg.startsWith(it) }) {
                                winRoot.recycle()
                                continue
                            }
                            
                            // Also skip any package that is NOT a system/settings/security app
                            val isSystemSecurityPkg = winPkg.contains("settings") ||
                                winPkg.contains("systemui") ||
                                winPkg.contains("packageinstaller") ||
                                winPkg.contains("installer") ||
                                winPkg.contains("security") ||
                                winPkg.contains("safetycenter") ||
                                winPkg.contains("permission") ||
                                winPkg.contains("canta") ||
                                winPkg.contains("shizuku") ||
                                winPkg.contains("ladb") ||
                                winPkg.contains("wireless.adb") ||
                                winPkg == "android"
                            
                            // CRITICAL: Skip launchers/drawers and non-security apps in the background scan 
                            if (!isSystemSecurityPkg || isLauncher(winPkg)) {
                                winRoot.recycle()
                                continue
                            }

                            val winText = getNodeText(winRoot).lowercase()
                            
                            val winHasAppName = appNames.any { winText.contains(it) }
                            val winHasActive = winText.contains("active apps") || winText.contains("running apps") || 
                                               winText.contains("apps are active") || winText.contains("foreground services")
                                               
                            // Confirmation popups (system dialogs)
                             val isConfirmDialog = (winPkg == "android" || winPkg.contains("settings") || winPkg.contains("systemui")) && 
                                                  (winText.contains("force stop") || winText.contains("uninstall") || 
                                                   winText.contains("stop app") || winText.contains("clear data") ||
                                                   winText.contains("misbehave"))
                             
                             // Dangerous actions: force stop, kill, uninstall (not in hide context)
                             // We require EXACT word match for "stop" to avoid substring matches in notifications
                             val winHasForceStop = winText.contains("force stop")
                             val winHasDangerousAction = winText.contains("kill") || 
                                 (winText.contains("uninstall") && !winText.contains("hide") && !winText.contains("hidden")) ||
                                 (winText.matches(Regex(".*\\bstop\\b.*")) && winHasActive) ||
                                 isConfirmDialog
                             
                             // Surgical block: Only block if we are NOT in the general app list
                              val isSettingsList = (winPkg.contains("settings") || winPkg.contains("securitycenter")) && (winText.contains("manage apps") || winText.contains("all apps") || winText.contains("app list") || winText.contains("installed apps") || winText.contains("apps (") || winText.contains("search"))
                              
                              if (!isSettingsList && winHasAppName && (winHasActive || winHasForceStop || winHasDangerousAction)) {
                                 Log.d(ANTI_UNINSTALL_TAG, "Detected dangerous action/popup in window: $winPkg")
                                 isActiveAppsInAnyWindow = true
                                 winRoot.recycle()
                                 break
                             }
                        } catch (e: Exception) {
                        } finally {
                            try { winRoot.recycle() } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(ANTI_UNINSTALL_TAG, "Error scanning windows", e)
            }

            val isSystemUIOnly = currentPackage.contains("systemui") || 
                                currentPackage == "android"
            val isSystemUIIncludingSettings = isSystemUIOnly || currentPackage.contains("settings")
            // Stricter markers for the "Active apps" management screen
            // We exclude "app is running" because it's common in the notification shade
            val hasActiveAppMarkers = screenText.contains("active apps") || 
                                     screenText.contains("running apps") || 
                                     screenText.contains("apps are active") ||
                                     screenText.contains("foreground services")
            
            // Active Apps dialog: close when InFoXus is visible in the SystemUI active apps panel.
            // This is intentionally NOT disabled - the panel should close to prevent stopping InFoXus.
            // It is decoupled from the launcher check so long-press menus on other apps are unaffected.
            val isActiveAppsDialog = (isSystemUIOnly && !isLauncher && hasAppName && 
                                     hasActiveAppMarkers && screenText.matches(Regex(".*\\bstop\\b.*")) && 
                                     !screenText.contains("notification")) || 
                                     isActiveAppsInAnyWindow

            // Refined Launcher: Only block if we see our specific security marker shortcut AND it's visible.
            // This is now independent of the 'InFoXus' name to avoid false positives in lists.
            val markerNodes = try { rootInActiveWindow?.findAccessibilityNodeInfosByText("hn5eb wla eh") } catch(e: Exception) { null }
            val isMarkerVisible = markerNodes?.any { it.isVisibleToUser } ?: false
            markerNodes?.forEach { try { it.recycle() } catch(e: Exception) {} }

            val isMarkerBlockTriggered = isMarkerVisible && (hasDangerous || screenText.contains("app info") || screenText.contains("uninstall"))
                                      
            // Detect confirmation dialogs specifically (often package is 'android')
            val isConfirmationDialog = hasAppName && 
                (
                    (currentPackage == "android" && (screenText.contains("force stop") || screenText.contains("uninstall") || screenText.contains("ok"))) ||
                    (isSettingsApp && (screenText.contains("force stop?") || screenText.contains("uninstall this app?")))
                )

            // REFINED BLOCKING CONDITIONS:
            // 1. Marker-based protection (Surgical & Fast - works everywhere)
            var shouldBlock = isMarkerBlockTriggered
            
            // 2. Fundamental system deactivation screens
            // PERFECT SHIELD: If we are in Settings/Security and see "InFoXus" but it's NOT a list,
            // then we are 100% on the App Info or similar management screen. Block immediately.
            // TRIPLE-LOCK: Only block if we see InFoXus AND specific management details (like Cache/Clear/Force)
            // that are NEVER present in the general app list.
            val hasAppSpecificDetails = (screenText.contains("storage") && (screenText.contains("cache") || screenText.contains("clear"))) || 
                                         (screenText.contains("force stop") || screenText.contains("uninstall")) ||
                                         (screenText.contains("battery") && screenText.contains("optimization")) ||
                                         (screenText.contains("permissions") && screenText.contains("allow"))
                                         
            val isInfoxusManagementPage = (isSettingsApp || packageName.lowercase().contains("securitycenter")) && 
                                          hasAppName && hasAppSpecificDetails && !isGeneralAppList
            
            if (!shouldBlock) {
                shouldBlock = isDeactivationScreen || isOurAccessibilitySettings || isDangerousADBAction || isConfirmationDialog || isInfoxusManagementPage
            }
            
            // 3. Sensitive Context Protection (Settings, Installer, ADB tools)
            // These are the ONLY apps where we allow app-name-based blocking.
            val isSensitiveSystemContext = isSettingsApp || isPackageInstaller || isADBTool
            
            if (!shouldBlock && isSensitiveSystemContext) {
                // Use hasAppName (content search) or appNameInTitle (title search)
                // to catch all variations of the App Info screen.
                if ((hasAppName || appNameInTitle) && isAppInfoScreen && !isGeneralAppList) {
                    // On the actual App Info page for InFoXus â€” block immediately.
                    shouldBlock = true
                } else if ((hasAppName || appNameInTitle) && hasDangerous && !isGeneralAppList && !isSettingsApp) {
                    // App name visible + dangerous action buttons present (and definitely not a list)
                    // We EXCLUDE isSettingsApp here to prevent generic list crashes
                    shouldBlock = true
                }
            }
            
            // 3b. Hide Apps screen: block any time InFoXus appears in a hide/hidden settings screen.
            // When the user SEARCHES for InFoXus in the hide apps list, the header disappears,
            // isGeneralAppList becomes false, and the toggle is accessible. This catches that case.
            val isHideAppsContext = isSettingsApp && hasAppName && 
                (screenText.matches(Regex(".*\\bhide\\b.*")) || windowTitle.contains("hide") ||
                 screenText.matches(Regex(".*\\bhidden\\b.*")) || windowTitle.contains("hidden"))
            if (!shouldBlock && isHideAppsContext) {
                shouldBlock = true
            }
            
            // 4. SystemUI Management (Active apps, system popups)
            // We ONLY block here if we are NOT in a launcher/home context.
            if (!shouldBlock && isSystemUIOnly && !isLauncher) {
                if (isActiveAppsDialog) {
                    shouldBlock = true
                }
            }

            // ANTI-DRAWER PROTECTION: Absolute override for launchers and home screens.
            // On launchers, the marker is the ONLY thing that can trigger a block.
            if (isLauncher) {
                shouldBlock = isMarkerBlockTriggered
            }
            if (shouldBlock) {
                triggerAntiUninstallBlock(appDisplayName ?: "InFoXus")
            }
        } catch (e: Exception) {
            Log.e(ANTI_UNINSTALL_TAG, "Error in checkAndBlockSettings", e)
        } finally {
            try { rootNodeToScan.recycle() } catch (e: Exception) {}
            if (targetRoot != null) {
                try { targetRoot.recycle() } catch (e: Exception) {}
            }
        }
    }

    /**
     * Executes the actual blocking action (HOME/BACK) with feedback.
     * Isolated to ensure it can be called from fast-paths.
     */
    private fun triggerAntiUninstallBlock(displayName: String) {
        // OVERLAY IS THE SHIELD — no navigation, no back, no home
        ShieldManager.showOverlay()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                this,
                "🛡 ${displayName} is protected",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun isLauncher(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val lower = packageName.lowercase()
        // IMPORTANT: Keep this list VERY specific to avoid false positives.
        // 'ui' is too broad (matches systemui), 'miui' matches all MIUI apps,
        // 'home' matches things like 'com.android.phone', etc.
        // Only use patterns that ONLY appear in actual launcher packages.
        if (lower.contains("launcher") || lower.contains("trebuchet") || 
            lower.contains("quickstep")) return true
            
        // Most reliable: check if this IS the system's default home activity.
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return packageName == resolveInfo?.activityInfo?.packageName
    }

    private fun isAtDeviceAdminScreen(screenText: String): Boolean {
        // Robust check: use multiple common terms and also check package/class if available
        val lowerText = screenText.lowercase()
        return lowerText.contains("device admin") || 
               lowerText.contains("admin apps") || 
               lowerText.contains("device administrators") ||
               lowerText.contains("administrators") ||
               (lowerText.contains("admin") && lowerText.contains("apps"))
    }

    private fun isPackageABrowser(packageName: String): Boolean {
        if (packageName.isEmpty() || packageName == "com.android.vending") return false
        
        // Cache Chrome and Brave as allowed (not to be blocked)
        if (packageName == "com.android.chrome" || packageName == "com.brave.browser") return false
        
        return browserCache.getOrPut(packageName) {
            try {
                // 1. Broad check for Apps handling http/https (MATCH_ALL)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com"))
                val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                val handlesWeb = resolveInfos.any { it.activityInfo.packageName == packageName }
                
                if (handlesWeb) return@getOrPut true
                
                // 2. Intent category check for browsers
                val browserIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
                val browsers = packageManager.queryIntentActivities(browserIntent, 0)
                if (browsers.any { it.activityInfo.packageName == packageName }) return@getOrPut true
                
                // 3. Known browser package names (fallback for Via, Opera, etc.)
                val knownBrowsers = listOf("mark.via", "com.opera.browser", "org.mozilla.firefox", "com.microsoft.emmx")
                knownBrowsers.any { packageName.contains(it) }
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun getNodeText(node: AccessibilityNodeInfo?, depth: Int = 0, maxDepth: Int = 15): String {
        if (node == null || depth > maxDepth) return ""
        val sb = StringBuilder()
        
        try {
            val text = node.text?.toString() ?: ""
            if (text.isNotEmpty()) {
                sb.append(text).append(" ")
                val lowerText = text.lowercase()
                if (ADMIN_LIST_TITLES.contains(lowerText)) {
                    adminHeaderFound = true
                }
            }
            
            node.contentDescription?.let { sb.append(it).append(" ") }
            
            for (i in 0 until node.childCount) {
                try {
                    val child = node.getChild(i)
                    if (child != null) {
                        sb.append(getNodeText(child, depth + 1))
                        child.recycle() // CRITICAL: Recycle child nodes to prevent leaks
                    }
                } catch (e: Exception) {
                    // Skip stale/recycled child nodes
                }
            }
        } catch (e: Exception) {
            // Node became stale during traversal - safe to ignore
        }
        return sb.toString()
    }

    private fun isPartOfClickable(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) return true
            val parent = current.parent
            // To avoid leaks, we don't recycle 'current' if it's the 'node' passed in, 
            // but we must manage parents.
            if (current != node) try { current.recycle() } catch (e: Exception) {}
            current = parent
        }
        return false
    }

    private fun parentHasAppReference(node: AccessibilityNodeInfo?, appNames: List<String>): Boolean {
        if (node == null) return false
        try {
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    val text = child.text?.toString()?.lowercase() ?: ""
                    val desc = child.contentDescription?.toString()?.lowercase() ?: ""
                    try { child.recycle() } catch (e: Exception) {}
                    if (appNames.any { text.contains(it) || desc.contains(it) }) return true
                }
            }
        } catch (e: Exception) {}
        return false
    }

    private fun handleAppBlockerResult(result: AppBlocker.AppBlockerResult, pkg: String) {
        if (result.cheatHoursEndTime != -1L) setUpForcedRefreshChecker(pkg, result.cheatHoursEndTime)
        if (result.cooldownEndTime != -1L) setUpForcedRefreshChecker(pkg, result.cooldownEndTime)
        if (!result.isBlocked) return
        
        pressHome(0)
        lastAppBlockerPackage = ""
        // Warning screen removed as per request
        /*
        val intent = Intent(this, WarningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
            putExtra("result_id", pkg)
        }
        startActivity(intent)
        */
    }

    private fun handleFocusModeBlockerResult(result: FocusModeBlocker.FocusModeResult) {
        if (result.isRequestingToUpdateSPData) savedPreferencesLoader.saveFocusModeData(focusModeBlocker.focusModeData)
        if (!result.isBlocked) return
        pressHome(0)
        lastAppBlockerPackage = ""
        Toast.makeText(this, "This app is currently under focus mode", Toast.LENGTH_LONG).show()
    }

    private fun handleKeywordBlockerResult(result: KeywordBlocker.KeywordBlockerResult) {
        Toast.makeText(this, "Blocked keyword '${result.resultDetectWord}' found", Toast.LENGTH_LONG).show()
        if (result.isHomePressRequested) pressHome()
    }

    private fun handleViewBlockerResult() {
        pressBack()
        // Warning screen removed as per request
        /*
        if (viewBlockerWarningConfig.isWarningDialogHidden) return
        val intent = Intent(this, WarningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("mode", Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER)
            putExtra("result_id", result.viewId)
            putExtra("is_press_home", result.requestHomePressInstead)
        }
        startActivity(intent)
        */
    }

    private fun handleScrollEvent(pkg: String) {
        if (++userYSwipeEventCounter > (MIN_SCROLL_THRESHOLD[pkg] ?: 2)) {
            isScrollInProgress = true
            userYSwipeEventCounter = 0
            val date = TimeTools.getCurrentDate()
            val newCount = (reelCountData[date] ?: 0) + 1
            reelCountData[date] = newCount
            usageStatOverlayManager.reelsScrolledThisSession = newCount
            
            if (isReelCountToBeDisplayed) {
                usageStatOverlayManager.binding?.reelCounter?.apply {
                    visibility = View.VISIBLE
                    text = newCount.toString()
                }
            }
            trackAttentionSpan()
            savedPreferencesLoader.saveReelsScrolled(reelCountData)
            trackingHandler.postDelayed({ isScrollInProgress = false }, SCROLL_DEBOUNCE_TIME)
        }
    }

    /**
     * Commits the current active session to persistent storage.
     * @param continueSession If true, restarts the session timer immediately to prevent time loss.
     */
    private fun commitCurrentSession(continueSession: Boolean = false) {
        val pkg = currentPackage ?: return
        val sessionStart = appSessionStartMs
        if (sessionStart <= 0) {
            if (continueSession) {
                // If we're supposed to continue but no session exists, try to start one
                if (appUsageLimitsConfig.limits.any { it.enabled && it.packageNames.contains(pkg) }) {
                    appSessionStartMs = System.currentTimeMillis()
                }
            }
            return
        }

        val now = System.currentTimeMillis()
        val elapsed = now - sessionStart
        if (elapsed <= 0) {
            if (!continueSession) appSessionStartMs = 0L
            return
        }

        val elapsedMins = elapsed / 60000f
        
        // Reset the session start IMMEDIATELY before saving to disk to ensure 
        // the time spent saving is accounted for in the NEXT session fragment.
        appSessionStartMs = if (continueSession) now else 0L

        val nowMs = System.currentTimeMillis()
        val isRecentlyInside = { zoneId: String? ->
            zoneId != null && (cachedInsideZoneIds.contains(zoneId) || 
            (nowMs - lastInsideZoneUpdateMs < LOCATION_GRACE_PERIOD_MS))
        }

        var modified = false
        appUsageLimitsConfig.limits.forEach { limit ->
            if (!limit.enabled || !limit.packageNames.contains(pkg)) return@forEach

            val countsAsInside = if (limit.isLocationRestricted) {
                isRecentlyInside(limit.locationId)
            } else true

            if (countsAsInside) {
                limit.currentUsageMinutes += elapsedMins
                val newPkgUsage = (limit.appUsageMap[pkg] ?: 0f) + elapsedMins
                limit.appUsageMap = limit.appUsageMap.toMutableMap().apply { put(pkg, newPkgUsage) }
                modified = true
            }
        }

        if (modified) {
            savedPreferencesLoader.saveUsageLimits(appUsageLimitsConfig)
        }
    }

    /**
     * Enforce limits in real-time.
     * Computes effective usage = stored + live session delta, then blocks if exceeded.
     * Does NOT write to currentUsageMinutes or touch appSessionStartMs.
     */
    private fun enforceUsageLimits() {
        if (appUsageLimitsConfig.limits.isEmpty()) return

        val now = System.currentTimeMillis()
        val liveSessionMins = if (appSessionStartMs > 0 && appSessionStartMs <= now) {
            (now - appSessionStartMs) / 60000f
        } else 0f

        val locationPrefs = getSharedPreferences("location_blocker", Context.MODE_PRIVATE)
        val insideIdsJson = locationPrefs.getString("current_inside_zones", null)
        val insideIds: Set<String> = try {
            val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
            com.google.gson.Gson().fromJson(insideIdsJson, type) ?: emptySet()
        } catch (e: Exception) { emptySet() }

        exceededLimitPackages.clear()
        var anyBlockNeeded = false

        val nowMs = System.currentTimeMillis()
        val isCurrentlyInside = { zoneId: String? ->
            zoneId != null && (cachedInsideZoneIds.contains(zoneId) || 
            (nowMs - lastInsideZoneUpdateMs < LOCATION_GRACE_PERIOD_MS))
        }

        appUsageLimitsConfig.limits.forEach { limit ->
            if (!limit.enabled) return@forEach

            // Add live session delta if current app is in this limit
            val sessionContrib = if (limit.packageNames.contains(currentPackage)) liveSessionMins else 0f
            val effectiveUsage = limit.currentUsageMinutes + sessionContrib

            val isAtLocation = if (limit.isLocationRestricted) {
                isCurrentlyInside(limit.locationId)
            } else true

            if (isAtLocation && effectiveUsage >= limit.timeLimitMinutes) {
                exceededLimitPackages.addAll(limit.packageNames)
                if (limit.packageNames.contains(currentPackage)) anyBlockNeeded = true
            }
        }

        if (anyBlockNeeded) {
            val isAlreadyHome = isLauncher(currentPackage ?: "") || isLauncher(packageName ?: "")
            if (!isAlreadyHome) pressHome(0)
        }
    }

    // Only used by handleScreenOff and startTimeTracking runnable
    private fun updateAppUsageLimit() {
        val now = System.currentTimeMillis()
        
        // 1. Periodic commit to disk every 30s to ensure data safety and UI freshness
        if (appSessionStartMs > 0 && now - lastPeriodicCommitMs >= PERIODIC_COMMIT_INTERVAL) {
            commitCurrentSession(continueSession = true)
            lastPeriodicCommitMs = now
        }
        
        // 2. Real-time enforcement (every 1s)
        enforceUsageLimits()
    }

    private fun accumulateUsageTime() { /* replaced by commitCurrentSession */ }

    private fun syncUsageWithSystem() {
        try {
            val now = System.currentTimeMillis()
            val resetHour = appUsageLimitsConfig.resetHour
            val resetMinute = appUsageLimitsConfig.resetMinute
            
            val cal = java.util.Calendar.getInstance()
            val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val currentMinute = cal.get(java.util.Calendar.MINUTE)
            
            if (currentHour < resetHour || (currentHour == resetHour && currentMinute < resetMinute)) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            }
            cal.set(java.util.Calendar.HOUR_OF_DAY, resetHour)
            cal.set(java.util.Calendar.MINUTE, resetMinute)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            
            val resetTimestamp = cal.timeInMillis
            
            val locationPrefs = getSharedPreferences("location_blocker", Context.MODE_PRIVATE)
            val insideIdsJson = locationPrefs.getString("current_inside_zones", null)
            val insideIds: Set<String> = try {
                val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
                com.google.gson.Gson().fromJson(insideIdsJson, type) ?: emptySet()
            } catch (e: Exception) { emptySet() }

            // Offload the heavy UsageEvents query to a background thread to prevent ANR crashes
            Thread {
                try {
                    val systemStats = usageStatsHelper.getAccurateUsageStatsSince(resetTimestamp, now)
                    
                    Handler(Looper.getMainLooper()).post {
                        try {
                            var modified = false
                            appUsageLimitsConfig.limits.forEach { limit ->
                                if (!limit.enabled) return@forEach
                                
                                // Calculate the SUM of all packages in this limit from system stats
                                val totalSystemUsageMins = limit.packageNames.sumOf { pkg ->
                                    (systemStats[pkg] ?: 0L).toDouble()
                                }.toFloat() / 60000f
                                
                                // Unified Safety Logic: If system says we've used more than we tracked, 
                                // jump forward to match the system. This works for ALL limits.
                                // NOTE: We don't distinguish location here because system stats 
                                // represent a guaranteed 'lower bound' of total device usage.
                                if (totalSystemUsageMins > limit.currentUsageMinutes) {
                                    limit.currentUsageMinutes = totalSystemUsageMins
                                    modified = true
                                    
                                    // Also update per-app map to keep it in sync
                                    val newAppUsageMap = limit.appUsageMap.toMutableMap()
                                    limit.packageNames.forEach { pkg ->
                                        val systemMins = (systemStats[pkg] ?: 0L).toFloat() / 60000f
                                        if (systemMins > (newAppUsageMap[pkg] ?: 0f)) {
                                            newAppUsageMap[pkg] = systemMins
                                        }
                                    }
                                    limit.appUsageMap = newAppUsageMap
                                }
                            }
                            if (modified) {
                                savedPreferencesLoader.saveUsageLimits(appUsageLimitsConfig)
                            }
                        } catch (e: Exception) {
                            Log.e("UsageSync", "Error processing usage stats on main thread", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("UsageSync", "Error querying system usage stats", e)
                }
            }.start()
        } catch (e: Exception) {
            Log.e("UsageSync", "Error starting usage stats sync", e)
        }
    }

    private fun trackAttentionSpan(type: Int = VIDEO_TYPE_REEL) {
        lastVideoViewFoundTime?.let {
            var elapsed = (SystemClock.uptimeMillis() - it) / 1000f
            if (elapsed > 150f) elapsed = 150f
            val date = TimeTools.getCurrentDate()
            attentionSpanDataList.getOrPut(date) { mutableListOf() }.add(AttentionSpanVideoItem(elapsed, TimeTools.getCurrentTime(), type))
            savedPreferencesLoader.saveUsageHoursAttentionSpanData(attentionSpanDataList)
        }
        lastVideoViewFoundTime = SystemClock.uptimeMillis()
    }

    private fun hideReelTrackingView() {
        usageStatOverlayManager.binding?.reelCounter?.visibility = View.GONE
        lastVideoViewFoundTime = null
    }

    private fun handleScreenOn() {
        isScreenOn = true
        screenOnTime = System.currentTimeMillis()
        // If current package is tracked, restart session
        val pkg = currentPackage
        if (pkg != null && appUsageLimitsConfig.limits.any { it.enabled && it.packageNames.contains(pkg) }) {
            appSessionStartMs = System.currentTimeMillis()
        }
        startTimeTracking()
        startLimitChecking()
    }

    private fun handleScreenOff() {
        isScreenOn = false
        commitCurrentSession()  // commit exact session before screen off
        updateAccumulatedTime()
        stopTimeTracking()
        stopLimitChecking()
    }

    private fun startTimeTracking() {
        stopTimeTracking()
        trackingUpdateRunnable = object : Runnable {
            override fun run() {
                if (isScreenOn) {
                    val totalTime = accumulatedTime + (System.currentTimeMillis() - screenOnTime)
                    usageStatOverlayManager.binding?.timeElapsedTxt?.text = formatElapsedTime(totalTime)
                    updateAppUsageLimit()
                    trackingHandler.postDelayed(this, TRACKING_UPDATE_INTERVAL)
                }
            }
        }
        trackingHandler.post(trackingUpdateRunnable!!)
    }

    private fun stopTimeTracking() {
        trackingUpdateRunnable?.let { trackingHandler.removeCallbacks(it) }
        trackingUpdateRunnable = null
    }

    private fun updateAccumulatedTime() {
        if (isScreenOn) {
            accumulatedTime += System.currentTimeMillis() - screenOnTime
            screenOnTime = System.currentTimeMillis()
        }
    }

    private fun formatElapsedTime(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun setUpForcedRefreshChecker(pkg: String, endMillis: Long) {
        blockerUpdateRunnable?.let { blockerHandler.removeCallbacks(it) }
        blockerUpdateRunnable = Runnable {
            try {
                if (rootInActiveWindow.packageName == pkg) {
                    handleAppBlockerResult(AppBlocker.AppBlockerResult(true), pkg)
                    lastAppBlockerPackage = ""
                    appBlocker.removeCooldownFrom(pkg)
                }
            } catch (e: Exception) {
                setUpForcedRefreshChecker(pkg, endMillis + 60000)
            }
        }
        blockerHandler.postAtTime(blockerUpdateRunnable!!, endMillis)
    }

    private fun handleAppBlockerCooldown(intent: Intent) {
        val interval = intent.getIntExtra("selected_time", appBlockerWarning.timeInterval)
        val pkg = intent.getStringExtra("result_id") ?: ""
        val end = SystemClock.uptimeMillis() + interval
        appBlocker.putCooldownTo(pkg, end)
        setUpForcedRefreshChecker(pkg, end)
    }

    private fun handleViewBlockerCooldown(intent: Intent) {
        val interval = intent.getIntExtra("selected_time", viewBlockerWarningConfig.timeInterval)
        val id = intent.getStringExtra("result_id") ?: ""
        viewBlocker.applyCooldown(id, SystemClock.uptimeMillis() + interval)
    }

    private fun isFocusModeAllowedByLocation(): Boolean {
        val config = locationPreferencesManager.getConfig()
        if (!config.autoControlFocusMode || !config.isEnabled) return true
        val prefs = getSharedPreferences("location_blocker", Context.MODE_PRIVATE)
        val insideIdsJson = prefs.getString("current_inside_zones", null) ?: return false
        val insideIds: Set<String> = try {
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            val list: List<String>? = com.google.gson.Gson().fromJson(insideIdsJson, type)
            list?.toSet() ?: emptySet()
        } catch (e: Exception) { emptySet() }
        
        return config.locations.any { insideIds.contains(it.id) && it.enabled && it.enableFocusModeInZone }
    }

    private fun getLocationBlockingDecision(pkg: String): LocationBlockingDecision {
        val config = locationPreferencesManager.getConfig()
        if (!config.isEnabled) return LocationBlockingDecision.NO_OPINION
        val prefs = getSharedPreferences("location_blocker", Context.MODE_PRIVATE)
        val insideIdsJson = prefs.getString("current_inside_zones", null)
        
        if (insideIdsJson.isNullOrEmpty() || insideIdsJson == "[]") {
            return if (config.globalMode == LocationMode.ENABLE_IN_ZONES) LocationBlockingDecision.ALLOW 
                   else LocationBlockingDecision.NO_OPINION
        }
        
        val insideIds: Set<String> = try {
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            val list: List<String>? = com.google.gson.Gson().fromJson(insideIdsJson, type)
            list?.toSet() ?: emptySet()
        } catch (e: Exception) { emptySet() }
        
        val zones = config.locations.filter { insideIds.contains(it.id) && it.enabled }
        return when (config.globalMode) {
            LocationMode.BLOCK_IN_ZONES -> if (zones.any { it.shouldBlockApp(pkg) }) LocationBlockingDecision.BLOCK else LocationBlockingDecision.NO_OPINION
            LocationMode.ENABLE_IN_ZONES -> LocationBlockingDecision.NO_OPINION
            LocationMode.DISABLE_IN_ZONES -> LocationBlockingDecision.ALLOW
        }
    }

    enum class LocationBlockingDecision { BLOCK, ALLOW, NO_OPINION }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("SHIELD_DEBUG", "SERVICE CONNECTED")
        ShieldManager.initShield(this)
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_SCROLLED or
                         AccessibilityEvent.TYPE_VIEW_FOCUSED or
                         AccessibilityEvent.TYPE_VIEW_CLICKED   // ADD
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS // ADD
            notificationTimeout = 100
            packageNames = null // null = monitor ALL packages (critical — do not set a list)
        }

        // canRetrieveWindowContent cannot be set via serviceInfo object —
        // it MUST be declared in the XML config. Verify accessibility_service_config.xml
        // has: android:canRetrieveWindowContent="true"
        // If the XML file is missing, create it (see ROOT CAUSE 3 below).

        refreshAllData()

        val filter = IntentFilter().apply {
            addAction(ACTION_REFRESH_ALL)
            addAction(ACTION_REFRESH_FOCUS_MODE)
            addAction(ACTION_REFRESH_APP_BLOCKER)
            addAction(ACTION_REFRESH_APP_BLOCKER_COOLDOWN)
            addAction(ACTION_REFRESH_ANTI_UNINSTALL)
            addAction(ACTION_REFRESH_KEYWORDS)
            addAction(ACTION_REFRESH_KEYWORD_CONFIG)
            addAction(ACTION_REFRESH_VIEW_BLOCKER)
            addAction(ACTION_REFRESH_VIEW_BLOCKER_COOLDOWN)
            addAction(ACTION_REFRESH_USAGE_TRACKER)
            addAction(ACTION_REFRESH_USAGE_LIMITS)
            addAction(Intent.ACTION_TIME_TICK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        // Overlay permission check for Usage Tracker
        if (!Settings.canDrawOverlays(this)) {
            // Optional: Notify user or handle accordingly
        }
        
        // Start the heartbeat scanner
        heartbeatHandler.post(heartbeatRunnable)

        // Start periodic usage limit check (every 5 seconds)
        startLimitChecking()
        
        // Data restore retry logic (similar to original services)
        val retryIntervals = listOf(3000L, 8000L, 18000L)
        retryIntervals.forEach { delay ->
            Handler(Looper.getMainLooper()).postDelayed({ refreshAllData() }, delay)
        }
    }

    override fun onInterrupt() {
        stopTimeTracking()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service being destroyed")
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Might not be registered
        }
        
        // CRITICAL: Stop all recurring tasks to prevent leaks and crashes
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        limitCheckRunnable?.let { limitCheckHandler.removeCallbacks(it) }
        
        stopTimeTracking()
        stopLimitChecking()
        super.onDestroy()
    }

    private fun startLimitChecking() {
        stopLimitChecking()
        limitCheckRunnable = object : Runnable {
            override fun run() {
                // Only ENFORCE (read + block) — do NOT accumulate time here.
                // Time is accumulated exclusively by the tracking runnable and handlePackageChange.
                enforceUsageLimits()
                limitCheckHandler.postDelayed(this, 1000L)
            }
        }
        limitCheckHandler.post(limitCheckRunnable!!)
    }

    private fun stopLimitChecking() {
        limitCheckRunnable?.let { limitCheckHandler.removeCallbacks(it) }
        limitCheckRunnable = null
    }

}








