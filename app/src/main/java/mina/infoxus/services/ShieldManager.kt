package mina.infoxus.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import mina.infoxus.data.*
import java.util.concurrent.ConcurrentHashMap

/**
 * ShieldManager v50 — THE SONIC BOOM.
 * 
 * Major Fixes:
 * 1. ZERO-FRAME ANIMATION VULNERABILITY DEFEATED: Discovered that GLOBAL_ACTION_BACK triggers a 200ms slide-down animation in the Active Apps dialog, leaving the buttons briefly clickable to muscle-memory taps.
 * 2. MULTI-VECTOR KICKOUT: The Proximity Assassin now fires a 'Sonic Boom' attack: It triggers GLOBAL_ACTION_HOME (which bypasses slide-down animations for an instant snap), instantly strips focus from the 'Stop' button in memory, and physically scrolls the layout to rip the button out from under the user's finger in the exact same frame.
 */
object ShieldManager {
    private const val TAG = "ShieldManager"

    var config = ShieldConfig()
    val isSessionActive = MutableStateFlow(false)
    
    @Volatile var isAntiUninstallOn = false
        set(value) {
            field = value
            Log.d(TAG, "isAntiUninstallOn changed to: $value")
        }

    var shieldedPackages = setOf<String>()
    var timeLimitedApps  = mapOf<String, Long>()
    var shieldRules      = listOf<ShieldRule>()

    private val _events = MutableSharedFlow<ShieldEvent>(extraBufferCapacity = 10)
    val events = _events.asSharedFlow()

    private val appStartTimes   = ConcurrentHashMap<String, Long>()
    private val appElapsedTimes = ConcurrentHashMap<String, Long>()
    private var currentForegroundApp: String? = null
    private var shieldOverlay: ShieldOverlayWindow? = null
    private var lastSeenOurAppInSettingsTime = 0L

    private val INSTALLER_PKGS = setOf(
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.google.android.packagemanager"
    )

    private val LAUNCHERS = setOf(
        "com.android.launcher3", "com.google.android.apps.nexuslauncher", "com.miui.home", "com.sec.android.app.launcher"
    )

    private val SAFE = setOf(
        "mina.infoxus", "com.google.android.gms", "com.google.android.gsf"
    )

    private val ALIASES = listOf("infoxus", "mina.infoxus", "hn5eb")
    private val FORCE_STOP_DIALOG = listOf("misbehave", "force stop?", "errors", "cause errors", "إيقاف إجباري؟")
    private val APP_INFO_KICKOUT = listOf("app info", "application info", "معلومات التطبيق", "uninstall", "إلغاء التثبيت", "force stop", "إيقاف إجباري")
    private val HIDE_APPS = listOf("hide apps", "hide applist", "hidden apps", "إخفاء التطبيقات", "التطبيقات المخفية")
    private val ACCESS = listOf("use infoxus", "stop infoxus")
    private val UNINSTALL_DIALOG = listOf("uninstall", "delete", "إلغاء التثبيت", "حذف")
    private val TARGET_BTNS = listOf("force", "stop", "uninstall", "clear", "disable", "ok", "confirm", "close", "إيقاف", "إلغاء", "مسح", "تعطيل", "حسنا", "تأكيد", "إجباري", "إغلاق")
    private val ACTIVE_APPS_PAGES = listOf("active apps", "apps active", "تطبيقات نشطة", "التطبيقات النشطة", "running apps", "background apps", "app is active", "apps are active")
    private val PRIVACY_HUB = listOf("microphone, camera & location", "in use by", "recent app use", "close this app", "استخدام الكاميرا", "استخدام الموقع", "إغلاق هذا التطبيق")
    private val LAUNCHER_SIGNATURE = listOf("hn5eb wla eh", "hn5eb")

    fun initShield(service: AccessibilityService) {
        if (shieldOverlay == null) shieldOverlay = ShieldOverlayWindow(service)
    }

    fun onAccessibilityEvent(event: AccessibilityEvent, service: AccessibilityService) {
        if (shieldOverlay == null) initShield(service)
        
        val pkg = event.packageName?.toString() ?: return
        val lower = pkg.lowercase()
        
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !isSafe(pkg)) {
            shieldOverlay?.unblockAll()
        }

        if (!isAntiUninstallOn && !isTimeLimited(pkg)) { 
            shieldOverlay?.hideFull()
            return 
        }

        if (isSafe(pkg)) {
            currentForegroundApp = pkg
            shieldOverlay?.hideFull()
            return
        }

        currentForegroundApp = pkg

        val root = service.rootInActiveWindow ?: return
        try {
            if (isInstaller(lower) || isLauncher(lower) || lower.contains("settings") || lower.contains("systemui")) {
                
                // INSTANT FIRST STRIKE: Zero-delay event-driven defenses
                if (isAntiUninstallOn) {
                    if (hasTextRecursively(root, FORCE_STOP_DIALOG)) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        return
                    }

                    val isOurApp = hasTextRecursively(root, ALIASES)

                    if (isOurApp && lower.contains("systemui")) {
                        val dangerousBtn = getDangerousButtonNearApp(root, ALIASES, listOf("stop", "إيقاف", "kill", "close", "إغلاق"))
                        if (dangerousBtn != null) {
                            // MULTI-VECTOR SONIC BOOM KICKOUT
                            // 1. HOME snaps the UI shut without the 200ms slide-down animation
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            
                            // 2. Clear focus in memory
                            dangerousBtn.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                            
                            // 3. Physically scroll the layout to move the button away from the finger
                            dangerousBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            dangerousBtn.parent?.parent?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            return
                        }

                        if (hasTextRecursively(root, ACTIVE_APPS_PAGES) || hasTextRecursively(root, PRIVACY_HUB)) {
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            return
                        }

                        applySystemUIPreemptiveBlockRecursively(root)
                        applySystemUIBlockRecursively(root)
                    }
                    
                    if (isOurApp && lower.contains("settings")) {
                        if (hasTextRecursively(root, APP_INFO_KICKOUT) || hasTextRecursively(root, HIDE_APPS) || hasTextRecursively(root, ACCESS)) {
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            return
                        }
                        applyTargetedSettingsBlockRecursively(root)
                    }

                    if (isOurApp && isInstaller(lower)) {
                        if (hasTextRecursively(root, UNINSTALL_DIALOG)) {
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            return
                        }
                        applyTargetedUninstallBlockRecursively(root)
                    }
                }

                if (isLauncher(lower)) {
                    if (isAntiUninstallOn && hasTextRecursively(root, LAUNCHER_SIGNATURE)) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        return
                    }
                    if (isAntiUninstallOn && hasTextRecursively(root, LAUNCHER_SIGNATURE)) {
                        applyTargetedLauncherBlockRecursively(root)
                    }
                }
            } else {
                handleTimeLimitOrLower(pkg, service)
            }
        } finally { root.recycle() }
    }

    // ── FAST RECURSIVE SCANNERS & PROXIMITY ALGORITHMS ───────────────

    private fun hasTextRecursively(node: AccessibilityNodeInfo?, targets: List<String>): Boolean {
        if (node == null) return false
        val t = node.text?.toString()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if (t.isNotEmpty() || d.isNotEmpty()) {
            for (i in targets.indices) {
                if (t.contains(targets[i]) || d.contains(targets[i])) return true
            }
        }
        for (i in 0 until node.childCount) {
            if (hasTextRecursively(node.getChild(i), targets)) return true
        }
        return false
    }

    private fun getDangerousButtonNearApp(node: AccessibilityNodeInfo?, appAliases: List<String>, dangerousTexts: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val t = node.text?.toString()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        
        if (t.isNotEmpty() || d.isNotEmpty()) {
            if (dangerousTexts.any { t.contains(it) || d.contains(it) }) {
                var parent = node.parent
                var depth = 0
                while (parent != null && depth < 4) {
                    val pt = parent.text?.toString()?.lowercase() ?: ""
                    val pd = parent.contentDescription?.toString()?.lowercase() ?: ""
                    if (appAliases.any { pt.contains(it) || pd.contains(it) }) return node
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChild(i) ?: continue
                        val ct = child.text?.toString()?.lowercase() ?: ""
                        val cd = child.contentDescription?.toString()?.lowercase() ?: ""
                        if (appAliases.any { ct.contains(it) || cd.contains(it) }) return node
                    }
                    parent = parent.parent
                    depth++
                }
            }
        }
        for (i in 0 until node.childCount) {
            val res = getDangerousButtonNearApp(node.getChild(i), appAliases, dangerousTexts)
            if (res != null) return res
        }
        return null
    }

    private fun applyTargetedSettingsBlockRecursively(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val t = node.text?.toString()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if (t.isNotEmpty() || d.isNotEmpty()) {
            if (TARGET_BTNS.any { t.contains(it) || d.contains(it) }) {
                shieldOverlay?.blockNode("settings_${node.hashCode()}", node)
                node.parent?.let { p -> shieldOverlay?.blockNode("settings_p_${p.hashCode()}", p) }
            }
        }
        for (i in 0 until node.childCount) {
            applyTargetedSettingsBlockRecursively(node.getChild(i))
        }
    }

    private fun applyTargetedLauncherBlockRecursively(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val t = node.text?.toString()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if (listOf("app info", "uninstall", "kill", "pause", "معلومات", "إلغاء", "إيقاف").any { t.contains(it) || d.contains(it) }) {
            shieldOverlay?.blockNode("shortcut_${node.hashCode()}", node)
            node.parent?.let { p -> shieldOverlay?.blockNode("shortcut_p_${p.hashCode()}", p) }
        }
        for (i in 0 until node.childCount) {
            applyTargetedLauncherBlockRecursively(node.getChild(i))
        }
    }

    private fun applyTargetedUninstallBlockRecursively(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val t = node.text?.toString()?.lowercase() ?: ""
        if (listOf("ok", "uninstall", "confirm", "delete", "إلغاء", "حسنا", "تأكيد", "حذف").any { t.contains(it) }) {
            shieldOverlay?.blockNode("confirm_${node.hashCode()}", node)
            node.parent?.let { p -> shieldOverlay?.blockNode("confirm_p_${p.hashCode()}", p) }
        }
        for (i in 0 until node.childCount) {
            applyTargetedUninstallBlockRecursively(node.getChild(i))
        }
    }

    private fun applySystemUIPreemptiveBlockRecursively(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val t = node.text?.toString()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if (ACTIVE_APPS_PAGES.any { t.contains(it) || d.contains(it) }) {
            shieldOverlay?.blockNode("systemui_preempt_${node.hashCode()}", node)
            node.parent?.let { p -> shieldOverlay?.blockNode("systemui_preempt_p_${p.hashCode()}", p) }
        }
        for (i in 0 until node.childCount) {
            applySystemUIPreemptiveBlockRecursively(node.getChild(i))
        }
    }

    private fun applySystemUIBlockRecursively(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val t = node.text?.toString()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if (listOf("stop", "إيقاف", "kill", "close", "إغلاق").any { t.contains(it) || d.contains(it) }) {
            shieldOverlay?.blockNode("systemui_${node.hashCode()}", node)
            node.parent?.let { p -> shieldOverlay?.blockNode("systemui_p_${p.hashCode()}", p) }
        }
        for (i in 0 until node.childCount) {
            applySystemUIBlockRecursively(node.getChild(i))
        }
    }

    // ── HELPER METHODS ───────────────────────────────────────────────

    private fun handleTimeLimitOrLower(pkg: String, service: AccessibilityService) {
        if (config.timeLimitsEnabled && timeLimitedApps.containsKey(pkg) && isTimeLimitHit(pkg)) {
            shieldOverlay?.showFull()
            launchBlockActivity(service, pkg)
        } else { 
            shieldOverlay?.hideFull() 
        }
    }

    private fun isInstaller(l: String) = INSTALLER_PKGS.any { l.contains(it) } || l.contains("packageinstaller")
    private fun isLauncher(l: String)  = LAUNCHERS.any { l.contains(it) }
    private fun isSafe(p: String)      = SAFE.any { p == it || p.startsWith("$it.") }
    private fun isTimeLimited(p: String) = timeLimitedApps.containsKey(p)

    private fun isTimeLimitHit(pkg: String): Boolean {
        val limit = timeLimitedApps[pkg] ?: return false
        val e = (appElapsedTimes[pkg] ?: 0L) + if ((appStartTimes[pkg] ?: 0L) > 0) System.currentTimeMillis() - appStartTimes[pkg]!! else 0L
        return e >= limit
    }

    private fun launchBlockActivity(service: AccessibilityService, pkg: String) {
        try { service.startActivity(Intent().apply { setClassName(service.packageName, "mina.infoxus.ui.activity.ShieldBlockActivity"); putExtra("EXTRA_PACKAGE_NAME", pkg); putExtra("EXTRA_REASON", "TIME_LIMIT"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) {}
    }

    fun heartbeatScan(service: AccessibilityService) {
        if (!isAntiUninstallOn) return
        val root = service.rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString()?.lowercase() ?: currentForegroundApp?.lowercase() ?: return
            
            if (!pkg.contains("settings") && !pkg.contains("systemui") && !isInstaller(pkg) && !isLauncher(pkg)) return

            if (hasTextRecursively(root, FORCE_STOP_DIALOG)) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return
            }

            val isOurApp = hasTextRecursively(root, ALIASES)

            if (isOurApp) {
                if (pkg.contains("settings")) {
                    if (hasTextRecursively(root, APP_INFO_KICKOUT) || 
                        hasTextRecursively(root, HIDE_APPS) || 
                        hasTextRecursively(root, ACCESS)) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        return
                    }
                    applyTargetedSettingsBlockRecursively(root)
                }

                if (isInstaller(pkg)) {
                    if (hasTextRecursively(root, UNINSTALL_DIALOG)) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        return
                    }
                    applyTargetedUninstallBlockRecursively(root)
                }

                if (pkg.contains("systemui")) {
                    val dangerousBtn = getDangerousButtonNearApp(root, ALIASES, listOf("stop", "إيقاف", "kill", "close", "إغلاق"))
                    if (dangerousBtn != null) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        dangerousBtn.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                        dangerousBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        return
                    }
                    if (hasTextRecursively(root, ACTIVE_APPS_PAGES) || hasTextRecursively(root, PRIVACY_HUB)) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        return
                    }
                    applySystemUIPreemptiveBlockRecursively(root)
                    applySystemUIBlockRecursively(root)
                }
            }
            
            if (isLauncher(pkg) && isAntiUninstallOn && hasTextRecursively(root, LAUNCHER_SIGNATURE)) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return
            }
        } finally { root.recycle() }
    }

    fun showOverlay()    = shieldOverlay?.showFull()
    fun hideOverlay()    = shieldOverlay?.hideFull()
    fun disarm()         { isAntiUninstallOn = false; shieldOverlay?.hideFull(); shieldOverlay?.unblockAll() }
    fun resetTimeLimitState() { appStartTimes.clear(); appElapsedTimes.clear(); currentForegroundApp = null }
}
