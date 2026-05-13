package mina.infoxus.utils

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import mina.infoxus.services.MainAccessibilityService

class ServiceStateManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "service_state_manager",
        Context.MODE_PRIVATE
    )

    private val KEY_LAST_VERSION_CODE = "last_version_code"
    private val KEY_MAIN_SERVICE_WAS_ENABLED = "main_service_was_enabled"

    fun saveServiceStates() {
        val isMainEnabled = isServiceEnabled(MainAccessibilityService::class.java)

        prefs.edit()
            .putBoolean(KEY_MAIN_SERVICE_WAS_ENABLED, isMainEnabled)
            .apply()
    }

    fun detectServiceStateLoss(): ServiceStateLoss {
        val currentVersionCode = getCurrentVersionCode()
        val lastVersionCode = prefs.getInt(KEY_LAST_VERSION_CODE, -1)

        val mainWasEnabled = prefs.getBoolean(KEY_MAIN_SERVICE_WAS_ENABLED, false)
        val mainIsEnabled = isServiceEnabled(MainAccessibilityService::class.java)

        if (lastVersionCode == -1) {
            saveServiceStates()
            prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
            return ServiceStateLoss.NONE
        }

        val lostServices = mutableListOf<String>()

        if (mainWasEnabled && !mainIsEnabled) {
            lostServices.add("Accessibility Protection")
        }

        prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
        saveServiceStates()

        return if (lostServices.isNotEmpty()) {
            ServiceStateLoss(lostServices, true)
        } else {
            ServiceStateLoss.NONE
        }
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .longVersionCode.toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun isServiceEnabled(serviceClass: Class<out AccessibilityService>): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        val enabledServices = am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
        
        val isEnabledByManager = enabledServices?.any { 
            it.resolveInfo.serviceInfo.packageName == context.packageName && 
            it.resolveInfo.serviceInfo.name == serviceClass.name 
        } ?: false

        if (isEnabledByManager) return true

        try {
            val expectedId = ComponentName(context, serviceClass).flattenToString()
            val enabledServicesStr = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return enabledServicesStr?.contains(expectedId) == true
        } catch (e: Exception) {
            return false
        }
    }



    data class ServiceStateLoss(
        val lostServices: List<String> = emptyList(),
        val hasLoss: Boolean = false
    ) {
        companion object {
            val NONE = ServiceStateLoss()
        }
    }
}

