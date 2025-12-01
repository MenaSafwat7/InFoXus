package nethical.digipaws.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import nethical.digipaws.Constants
import java.util.Locale

class GeneralFeaturesService : BaseBlockingService() {

    companion object {
        const val INTENT_ACTION_REFRESH_ANTI_UNINSTALL = "nethical.digipaws.refresh.anti_uninstall"
        private const val TAG = "GeneralFeaturesService"
    }

    private var isAntiUninstallOn = true
    private var appPackageName: String? = null
    private var appDisplayName: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        super.onAccessibilityEvent(event)

        if (!isAntiUninstallOn) {
            return
        }

        val packageName = event?.packageName ?: return

        if (packageName == "com.android.settings") {
            checkAndBlockSettings(event)
        }

        if (packageName == "com.google.android.packageinstaller" || 
            packageName.contains("packageinstaller")) {
            checkAndBlockUninstall(event)
        }
    }

    private fun checkAndBlockSettings(@Suppress("UNUSED_PARAMETER") event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return

        val appNames = listOf(
            appDisplayName?.lowercase(Locale.getDefault()) ?: "",
            "infoxus",
            "digipaws",
            "nethical.digipaws",
            packageName
        ).filter { it.isNotEmpty() }

        val dangerousKeywords = listOf(
            "device admin",
            "device administrator",
            "admin",
            "uninstall",
            "remove",
            "disable",
            "deactivate",
            "delete",
            "clear data"
        )

        val text = getNodeText(root)
        val lowerText = text.lowercase(Locale.getDefault())

        val hasAppName = appNames.any { name -> 
            name.isNotEmpty() && lowerText.contains(name)
        }

        val hasDangerousKeyword = dangerousKeywords.any { keyword ->
            lowerText.contains(keyword)
        } && (lowerText.contains("device") || lowerText.contains("admin") || 
              lowerText.contains("app info") || lowerText.contains("application"))

        if (hasAppName || hasDangerousKeyword) {
            Log.d(TAG, "Blocking Settings - detected app name or dangerous keyword")
            pressHome()
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        traverseNodesForKeywords(root, appNames, dangerousKeywords)
    }

    private fun checkAndBlockUninstall(@Suppress("UNUSED_PARAMETER") event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val text = getNodeText(root).lowercase(Locale.getDefault())

        val appNames = listOf(
            appDisplayName?.lowercase(Locale.getDefault()) ?: "",
            "infoxus",
            "digipaws"
        ).filter { it.isNotEmpty() }

        if (text.contains("uninstall") && appNames.any { text.contains(it) }) {
            Log.d(TAG, "Blocking uninstall attempt")
            pressHome()
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun getNodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        val textBuilder = StringBuilder()

        node.text?.let { textBuilder.append(it).append(" ") }
        node.contentDescription?.let { textBuilder.append(it).append(" ") }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                textBuilder.append(getNodeText(child))
            }
        }

        return textBuilder.toString()
    }

    private fun traverseNodesForKeywords(
        node: AccessibilityNodeInfo?,
        appNames: List<String>,
        dangerousKeywords: List<String>
    ) {
        if (node == null) {
            return
        }

        if (node.className?.contains("TextView") == true) {
            val nodeText = node.text?.toString()?.lowercase(Locale.getDefault()) ?: ""
            val contentDesc = node.contentDescription?.toString()?.lowercase(Locale.getDefault()) ?: ""
            val combinedText = "$nodeText $contentDesc"

            val hasAppName = appNames.any { name -> 
                name.isNotEmpty() && combinedText.contains(name)
            }

            val hasDangerousContext = dangerousKeywords.any { keyword ->
                combinedText.contains(keyword)
            } && (combinedText.contains("device") || combinedText.contains("admin") || 
                  combinedText.contains("uninstall") || combinedText.contains("remove"))

            if (hasAppName || hasDangerousContext) {
                Log.d(TAG, "Blocking Settings - keyword detected: $combinedText")
                pressHome()
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
        }

        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            traverseNodesForKeywords(childNode, appNames, dangerousKeywords)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()

        appPackageName = packageName
        try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appDisplayName = pm.getApplicationLabel(appInfo).toString()
            Log.d(TAG, "App name: $appDisplayName, Package: $appPackageName")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app info", e)
            appDisplayName = "InFoXus" 
        }

        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_ANTI_UNINSTALL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
        setupAntiUninstall()
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) {
                when (intent.action) {
                    INTENT_ACTION_REFRESH_ANTI_UNINSTALL -> {
                        setupAntiUninstall()

                        try {
                            val pm = packageManager
                            val appInfo = pm.getApplicationInfo(packageName, 0)
                            appDisplayName = pm.getApplicationLabel(appInfo).toString()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error refreshing app info", e)
                        }
                    }
                }
            }
        }
    }

    fun setupAntiUninstall() {
        val info = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
        isAntiUninstallOn = info.getBoolean("is_anti_uninstall_on", false)
        Log.d(TAG, "Anti-uninstall is ${if (isAntiUninstallOn) "ON" else "OFF"}")
    }
}