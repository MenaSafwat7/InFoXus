package nethical.digipaws.utils

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.services.GeneralFeaturesService

class ServiceStateManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "service_state_manager",
        Context.MODE_PRIVATE
    )

    private val KEY_LAST_VERSION_CODE = "last_version_code"
    private val KEY_APP_BLOCKER_WAS_ENABLED = "app_blocker_was_enabled"
    private val KEY_GENERAL_FEATURES_WAS_ENABLED = "general_features_was_enabled"
    private val KEY_DEVICE_ADMIN_WAS_ENABLED = "device_admin_was_enabled"

    fun saveServiceStates() {
        val isAppBlockerEnabled = isServiceEnabled(AppBlockerService::class.java)
        val isGeneralFeaturesEnabled = isServiceEnabled(GeneralFeaturesService::class.java)

        prefs.edit()
            .putBoolean(KEY_APP_BLOCKER_WAS_ENABLED, isAppBlockerEnabled)
            .putBoolean(KEY_GENERAL_FEATURES_WAS_ENABLED, isGeneralFeaturesEnabled)
            .apply()
    }

    fun saveDeviceAdminState(isEnabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_DEVICE_ADMIN_WAS_ENABLED, isEnabled)
            .apply()
    }

    fun detectServiceStateLoss(): ServiceStateLoss {
        val currentVersionCode = getCurrentVersionCode()
        val lastVersionCode = prefs.getInt(KEY_LAST_VERSION_CODE, -1)

        val appBlockerWasEnabled = prefs.getBoolean(KEY_APP_BLOCKER_WAS_ENABLED, false)
        val generalFeaturesWasEnabled = prefs.getBoolean(KEY_GENERAL_FEATURES_WAS_ENABLED, false)
        val deviceAdminWasEnabled = prefs.getBoolean(KEY_DEVICE_ADMIN_WAS_ENABLED, false)

        val appBlockerIsEnabled = isServiceEnabled(AppBlockerService::class.java)
        val generalFeaturesIsEnabled = isServiceEnabled(GeneralFeaturesService::class.java)
        val deviceAdminIsEnabled = isDeviceAdminEnabled()

        if (lastVersionCode == -1) {
            saveServiceStates()
            saveDeviceAdminState(deviceAdminIsEnabled)
            prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
            return ServiceStateLoss.NONE
        }

        if (lastVersionCode != -1) {

            prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
            saveServiceStates()
            saveDeviceAdminState(deviceAdminIsEnabled)
            return ServiceStateLoss.NONE
        }

        val lostServices = mutableListOf<String>()

        if (appBlockerWasEnabled && !appBlockerIsEnabled) {
            lostServices.add("App Blocker")
        }
        if (generalFeaturesWasEnabled && !generalFeaturesIsEnabled) {
            lostServices.add("General Features")
        }
        if (deviceAdminWasEnabled && !deviceAdminIsEnabled) {
            lostServices.add("Device Admin")
        }

        prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
        saveServiceStates()
        saveDeviceAdminState(deviceAdminIsEnabled)

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
        val serviceName = ComponentName(context, serviceClass).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val isAccessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )

        return isAccessibilityEnabled == 1 && enabledServices.contains(serviceName)
    }

    private fun isDeviceAdminEnabled(): Boolean {
        return try {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            val componentName = ComponentName(
                context,
                nethical.digipaws.receivers.AdminReceiver::class.java
            )
            devicePolicyManager.isAdminActive(componentName)
        } catch (e: Exception) {
            false
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
