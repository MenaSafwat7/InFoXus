package mina.infoxus.ui.activity

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mina.infoxus.Constants
import mina.infoxus.R
import mina.infoxus.databinding.ActivityMainBinding
import mina.infoxus.databinding.DialogPermissionInfoBinding
import mina.infoxus.databinding.DialogRemoveAntiUninstallBinding

import mina.infoxus.services.MainAccessibilityService
import mina.infoxus.ui.activity.ManageKeywordsActivity
import mina.infoxus.ui.activity.ManageSitesActivity
import mina.infoxus.ui.dialogs.StartFocusMode
import mina.infoxus.ui.dialogs.TweakAppBlockerWarning
import mina.infoxus.ui.fragments.anti_uninstall.ChooseModeFragment
import mina.infoxus.ui.fragments.installation.PermissionsFragment
import mina.infoxus.utils.ServiceStateManager
import mina.infoxus.utils.SavedPreferencesLoader
import mina.infoxus.utils.LocationPreferencesManager
import mina.infoxus.utils.TimeTools
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var selectBlockedAppsLauncher: ActivityResultLauncher<Intent>

    private lateinit var selectFocusModeUnblockedAppsLauncher: ActivityResultLauncher<Intent>

    private lateinit var addCheatHoursActivity: ActivityResultLauncher<Intent>

    private lateinit var addAutoFocusHoursActivity: ActivityResultLauncher<Intent>

    private lateinit var manageKeywordsLauncher: ActivityResultLauncher<Intent>
    private lateinit var manageSitesLauncher: ActivityResultLauncher<Intent>

    private val savedPreferencesLoader = SavedPreferencesLoader(this)
    private val serviceStateManager: ServiceStateManager by lazy { ServiceStateManager(this) }
    private lateinit var options: ActivityOptionsCompat

    private var isAntiUninstallOn = false
    private var doesAntiUninstallBlockView = false

    private var isGeneralSettingsOn = false
    private var hasShownServiceLossDialogThisSession = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Push the header down below the status bar
            binding.header.setPadding(
                binding.header.paddingLeft,
                systemBars.top,
                binding.header.paddingRight,
                binding.header.paddingBottom
            )
            // Give the scroll content the bottom nav-bar inset
            findViewById<View>(R.id.main).setPadding(
                systemBars.left, 0, systemBars.right, systemBars.bottom
            )
            insets
        }

        options = ActivityOptionsCompat.makeCustomAnimation(this, R.anim.fade_in, R.anim.fade_out)
        setupActivityLaunchers()
        setupClickListeners()

        // Try to restore data from backup files if SharedPreferences were cleared
        try {
            mina.infoxus.utils.DataBackupManager.restoreAllData(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error restoring data in onCreate", e)
        }

        if (!isFirstLaunchComplete()) {
            val intent = Intent(this, FragmentActivity::class.java)
            intent.putExtra("fragment", PermissionsFragment.FRAGMENT_ID)
            startActivity(intent, options.toBundle())
        } else {
            checkServiceStateLossAfterUpdate()
            // Check battery optimization on app start (only once per session)
            checkBatteryOptimization()
        }

        // Set the correct theme icon on start
        val prefs = getSharedPreferences("theme_pref", Context.MODE_PRIVATE)
        val currentMode = prefs.getInt("mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        if (currentMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES || 
            (currentMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM && 
             (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)) {
            binding.btnThemeToggle.setImageResource(R.drawable.ic_light_mode)
        } else {
            binding.btnThemeToggle.setImageResource(R.drawable.ic_dark_mode)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
    override fun onResume() {
        super.onResume()
        
        // Try to restore data from backup files if SharedPreferences were cleared
        // This runs every time the app resumes as a safety measure
        try {
            mina.infoxus.utils.DataBackupManager.restoreAllData(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error restoring data in onResume", e)
        }
        
        // Force refresh services to reload data when app resumes
        // This ensures data is reloaded even if BootReceiver didn't work
        refreshAllServices()
        
        // Check permissions and update UI safely
        checkPermissions()
        checkServiceStateLossAfterUpdate()
    }
    
    private fun refreshAllServices() {
        try {
            // Send refresh broadcasts to ensure services reload their data
            sendBroadcast(Intent(MainAccessibilityService.ACTION_REFRESH_ALL))
            
            // Also restart location monitoring if enabled
            val locationPrefsManager = mina.infoxus.utils.LocationPreferencesManager(this)
            val config = locationPrefsManager.getConfig()
            if (config.isEnabled && config.locations.isNotEmpty()) {
                val startIntent = Intent(this, mina.infoxus.services.LocationMonitoringService::class.java).apply {
                    action = mina.infoxus.services.LocationMonitoringService.ACTION_START_MONITORING
                }
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(startIntent)
                    } else {
                        startService(startIntent)
                    }
                    Log.d("MainActivity", "Restarted location monitoring service on resume")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error starting location service in onResume", e)
                }
            } else if (!config.isEnabled || config.locations.isEmpty()) {
                // If it's disabled or no locations, stop the service
                val stopIntent = Intent(this, mina.infoxus.services.LocationMonitoringService::class.java)
                stopService(stopIntent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in refreshAllServices", e)
        }
    }

    private fun checkServiceStateLossAfterUpdate() {
        if (hasShownServiceLossDialogThisSession) {
            return
        }
        
        lifecycleScope.launch {
            // Give the OS a moment to update service states before checking
            kotlinx.coroutines.delay(500)
            val serviceLoss = withContext(Dispatchers.IO) {
                serviceStateManager.detectServiceStateLoss()
            }
            
            if (serviceLoss.hasLoss && serviceLoss.lostServices.isNotEmpty()) {
                hasShownServiceLossDialogThisSession = true
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        showServiceStateLossDialog(serviceLoss.lostServices)
                    }
                }
            }
        }
    }
    
    private fun showServiceStateLossDialog(lostServices: List<String>) {
        val servicesList = lostServices.joinToString("\nâ€¢ ", "â€¢ ")
        
        val message = """
            âڑ ï¸ڈ Critical Security Alert âڑ ï¸ڈ
            
            The following services have been disabled:
            
            $servicesList
            
            This typically happens after reinstalling or updating the app. This is a security issue! Without these services enabled, you may be able to uninstall the app, which could compromise your protection.
            
            Please re-enable these services immediately to restore full protection.
        """.trimIndent()
        
        val dialogBuilder = MaterialAlertDialogBuilder(this)
            .setTitle("âڑ ï¸ڈ Services Disabled")
            .setMessage(message)
            .setCancelable(false)
        
        dialogBuilder.setPositiveButton("Re-enable Services") { _, _ ->
            openAccessibilityServiceScreen(MainAccessibilityService::class.java)
        }
        
        dialogBuilder.setNeutralButton("Open Settings") { _, _ ->
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        
        dialogBuilder.show()
    }

    private fun setupActivityLaunchers() {


        selectBlockedAppsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                    selectedApps?.let {
                        savedPreferencesLoader.saveBlockedApps(it.toSet())
                        sendRefreshRequest(MainAccessibilityService.ACTION_REFRESH_APP_BLOCKER)
                    }
                }
            }

        selectFocusModeUnblockedAppsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                    selectedApps?.let {
                        savedPreferencesLoader.saveFocusModeSelectedApps(selectedApps)
                    }
                }
            }

        addCheatHoursActivity =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
                sendRefreshRequest(MainAccessibilityService.ACTION_REFRESH_APP_BLOCKER)
            }

        addAutoFocusHoursActivity =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
                sendRefreshRequest(MainAccessibilityService.ACTION_REFRESH_FOCUS_MODE)
            }

        manageKeywordsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedKeywords = result.data?.getStringArrayListExtra("SELECTED_KEYWORDS")
                    selectedKeywords?.let {
                        savedPreferencesLoader.saveBlockedKeywords(it.toSet())
                        sendBroadcast(Intent(MainAccessibilityService.ACTION_REFRESH_KEYWORDS))
                    }
                }
            }

        manageSitesLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedSites = result.data?.getStringArrayListExtra("SELECTED_SITES")
                    selectedSites?.let {
                        savedPreferencesLoader.saveBlockedSites(it.toSet())
                        sendBroadcast(Intent(MainAccessibilityService.ACTION_REFRESH_KEYWORDS))
                    }
                }
            }
    }

    private fun setupClickListeners() {

        binding.selectBlockedApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(savedPreferencesLoader.loadBlockedApps())
            )
            selectBlockedAppsLauncher.launch(intent, options)
        }
        binding.appBlockerSelectCheatHours.setOnClickListener {
            val intent = Intent(this, TimedActionActivity::class.java)
            intent.putExtra("selected_mode", TimedActionActivity.MODE_APP_BLOCKER_CHEAT_HOURS)
            addCheatHoursActivity.launch(intent, options)
        }
        binding.selectFocusBlockedApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(savedPreferencesLoader.getFocusModeSelectedApps())
            )
            selectFocusModeUnblockedAppsLauncher.launch(intent, options)
        }
        binding.autoFocus.setOnClickListener {
            val intent = Intent(this, TimedActionActivity::class.java)
            intent.putExtra("selected_mode", TimedActionActivity.MODE_AUTO_FOCUS)
            addAutoFocusHoursActivity.launch(intent, options)
        }

        binding.startFocusMode.setOnClickListener {



            createFocusModeShortcut()

            StartFocusMode(savedPreferencesLoader, onPositiveButtonPressed = {
                binding.selectFocusBlockedApps.isEnabled = false
                binding.startFocusMode.isEnabled = false

            }).show(
                supportFragmentManager,
                "start_focus_mode"
            )

        }

        binding.btnThemeToggle.setOnClickListener {
            val prefs = getSharedPreferences("theme_pref", Context.MODE_PRIVATE)
            val currentMode = prefs.getInt("mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
            
            val newMode = if (currentMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            } else {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            }
            
            prefs.edit().putInt("mode", newMode).apply()
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(newMode)
        }

        binding.btnSetupAntiUninstall.setOnClickListener {
            if (!isGeneralSettingsOn) {
                makeAccessibilityInfoDialog(
                    "InFoXus Protection",
                    MainAccessibilityService::class.java
                )
            } else {
                if (isAntiUninstallOn) {
                    makeRemoveAntiUninstallDialog()
                } else if (binding.antiUninstallWarning.visibility == View.GONE) {
                    val intent = Intent(this, FragmentActivity::class.java)
                    intent.putExtra("fragment", ChooseModeFragment.FRAGMENT_ID)
                    startActivity(intent, options.toBundle())
                } else {
                    makeAccessibilityInfoDialog(
                        "InFoXus Protection",
                        MainAccessibilityService::class.java
                    )
                }
            }
        }

        binding.focusModeStatusLabel.setOnClickListener {
            makeAccessibilityInfoDialog("InFoXus Protection", MainAccessibilityService::class.java)
        }
        binding.appBlockerStatusLabel.setOnClickListener {
            makeAccessibilityInfoDialog("InFoXus Protection", MainAccessibilityService::class.java)
        }
        binding.keywordBlockerStatusLabel.setOnClickListener {
            makeAccessibilityInfoDialog("InFoXus Protection", MainAccessibilityService::class.java)
        }
        binding.usageLimitsStatusLabel.setOnClickListener {
            makeAccessibilityInfoDialog("InFoXus Protection", MainAccessibilityService::class.java)
        }
        binding.btnManageKeywords.setOnClickListener {
            val intent = Intent(this, ManageKeywordsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SAVED_KEYWORDS",
                ArrayList(savedPreferencesLoader.loadBlockedKeywords())
            )
            manageKeywordsLauncher.launch(intent, options)
        }
        binding.btnManageSites.setOnClickListener {
            val intent = Intent(this, ManageSitesActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SAVED_SITES",
                ArrayList(savedPreferencesLoader.loadBlockedSites())
            )
            manageSitesLauncher.launch(intent, options)
        }
        binding.btnOpenLocationBlocker.setOnClickListener {
            val intent = Intent(this, LocationBlockerActivity::class.java)
            startActivity(intent, options.toBundle())
        }

        binding.btnContactLinkedin.setOnClickListener {
            openUrl("https://www.linkedin.com/in/mena-safwat/")
        }
        binding.btnContactGithub.setOnClickListener {
            openUrl("https://github.com/MenaSafwat7")
        }
        binding.btnContactEmail.setOnClickListener {
            sendEmail("menasafwatadolf@gmail.com", "InFoXus Bug Report")
        }
        binding.btnContactPortfolio.setOnClickListener {
            openUrl("https://minasafwat.runasp.net/")
        }
        binding.btnDonatePaypal.setOnClickListener {
            openUrl("https://paypal.me/MinaSafwat73")
        }
        binding.btnDonateInstapay.setOnClickListener {
            openUrl("https://ipn.eg/S/minasafwat72/instapay/64AhwX")
        }
        binding.btnManageUsageLimits.setOnClickListener {
            val intent = Intent(this, ManageUsageLimitsActivity::class.java)
            startActivity(intent, options.toBundle())
        }
    }

    private fun checkPermissions() {
        lifecycleScope.launch {
            val isMainServiceOn =
                withContext(Dispatchers.IO) { isAccessibilityServiceEnabled(this@MainActivity, MainAccessibilityService::class.java) }
            
            isGeneralSettingsOn = isMainServiceOn

            val antiUninstallInfo = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
            isAntiUninstallOn = antiUninstallInfo.getBoolean("is_anti_uninstall_on", false)
            doesAntiUninstallBlockView =
                antiUninstallInfo.getBoolean("is_configuring_blocked", false)

            val locationPrefsManager = LocationPreferencesManager(this@MainActivity)
            val locationConfig = locationPrefsManager.getConfig()
            val isLocationBlockerEnabled = locationConfig.isEnabled

            withContext(Dispatchers.Main) {

                updateStatusLabel(isMainServiceOn, binding.appBlockerStatusLabel, binding.appBlockerWarning)
                updateStatusLabel(isMainServiceOn, binding.usageLimitsStatusLabel, binding.usageLimitsWarning)
                binding.apply {
                    selectBlockedApps.isEnabled = isMainServiceOn
                    appBlockerSelectCheatHours.isEnabled = isMainServiceOn
                    btnManageUsageLimits.isEnabled = isMainServiceOn
                }

                updateStatusLabel(
                    isMainServiceOn,
                    binding.focusModeStatusLabel,
                    binding.focusModeWarning
                )
                binding.apply {
                    startFocusMode.isEnabled = isMainServiceOn
                    selectFocusBlockedApps.isEnabled = isMainServiceOn
                    autoFocus.isEnabled = isMainServiceOn
                }

                updateStatusLabel(
                    isMainServiceOn,
                    binding.keywordBlockerStatusLabel,
                    binding.keywordBlockerWarning
                )
                binding.apply {
                    btnManageKeywords.isEnabled = isMainServiceOn
                    btnManageSites.isEnabled = isMainServiceOn
                }

                updateStatusLabel(
                    isLocationBlockerEnabled,
                    binding.locationBlockerStatusLabel,
                    binding.locationBlockerWarning
                )
                binding.btnOpenLocationBlocker.isEnabled = true

                if (!isGeneralSettingsOn) {
                    binding.antiUninstallWarning.text = getString(R.string.warning_general_settings)
                }

                if (isGeneralSettingsOn) {
                    binding.antiUninstallWarning.visibility = View.GONE
                    binding.btnSetupAntiUninstall.isEnabled = true
                    binding.btnSetupAntiUninstall.text =
                        if (isAntiUninstallOn) getString(R.string.remove) else getString(R.string.enter_setup)
                }

                if (doesAntiUninstallBlockView && isAntiUninstallOn) {
                    binding.apply {
                        selectBlockedApps.isEnabled = true // Allowed as per user request
                        appBlockerSelectCheatHours.isEnabled = false
                        startFocusMode.isEnabled = false
                        autoFocus.isEnabled = true  // Allowed as per user request
                        selectFocusBlockedApps.isEnabled = true  // Allowed as per user request
                        btnManageKeywords.isEnabled = true // Allowed as per user request
                        btnManageSites.isEnabled = true
                    }
                }
                if (isMainServiceOn) {
                    val isFocusedModeOn = savedPreferencesLoader.getFocusModeData().isTurnedOn
                    binding.selectFocusBlockedApps.isEnabled = !isFocusedModeOn
                    binding.startFocusMode.isEnabled = !isFocusedModeOn
                }

            }
            
            serviceStateManager.saveServiceStates()
        }
    }

    private fun isFirstLaunchComplete(): Boolean {
        val sharedPreferences = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean("isFirstLaunchComplete", false)
    }

    private fun updateStatusLabel(isEnabled: Boolean, label: TextView, warningText: TextView) {
        if (isEnabled) {
            label.text = getString(R.string.enabled)
            label.setBackgroundResource(R.drawable.chip_enabled_label)
            label.setTextColor(getColor(R.color.chip_enabled_text))
            warningText.visibility = View.GONE
        } else {
            label.text = getString(R.string.disabled)
            label.setBackgroundResource(R.drawable.chip_disabled_label)
            label.setTextColor(getColor(R.color.chip_disabled_text))
            warningText.visibility = View.VISIBLE
        }
    }
    private fun sendRefreshRequest(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }
    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<out AccessibilityService>): Boolean {
        // Method 1: AccessibilityManager (standard way)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        val enabledServices = am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
        
        val isEnabledByManager = enabledServices?.any { 
            it.resolveInfo.serviceInfo.packageName == context.packageName && 
            it.resolveInfo.serviceInfo.name == serviceClass.name 
        } ?: false
        
        if (isEnabledByManager) return true
        
        // Method 2: Settings.Secure (more robust, works even if AccessibilityManager is lagging)
        try {
            val expectedId = ComponentName(context, serviceClass).flattenToString()
            val enabledServicesStr = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return enabledServicesStr?.contains(expectedId) == true
        } catch (e: Exception) {
            return false
        }
    }

    private fun makeAccessibilityInfoDialog(title: String, cls: Class<*>) {
        val dialogAccessibilityServiceInfoBinding =
            DialogPermissionInfoBinding.inflate(layoutInflater)
        dialogAccessibilityServiceInfoBinding.title.text = getString(R.string.enable_2, title)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogAccessibilityServiceInfoBinding.root)
            .show()

        dialogAccessibilityServiceInfoBinding.btnReject.setOnClickListener {
            dialog.dismiss()
        }
        dialogAccessibilityServiceInfoBinding.btnAccept.setOnClickListener {
            Toast.makeText(this, "Find '$title' and press enable", Toast.LENGTH_LONG).show()
            openAccessibilityServiceScreen(cls)
            dialog.dismiss()
        }

        dialogAccessibilityServiceInfoBinding.btnGuide.visibility = View.GONE
    }

    private fun createFocusModeShortcut() {

        val sp = getSharedPreferences("shortcuts",Context.MODE_PRIVATE)
        if(sp.getBoolean("focus_mode",false)){
            return
        }
        val intent = Intent(this, ShortcutActivity::class.java).apply {
            action = Intent.ACTION_CREATE_SHORTCUT
        }
        val shortcutInfo = ShortcutInfoCompat.Builder(this, "infoxus_focus_mode")
            .setShortLabel(getString(R.string.focus_mode))
            .setLongLabel(getString(R.string.focus_mode))
            .setIntent(intent)
            .setIcon(IconCompat.createWithResource(this, R.drawable.focus_mode_icon))
            .build()

        val supported = ShortcutManagerCompat.isRequestPinShortcutSupported(this)
        val dynamicShortcuts = ShortcutManagerCompat.getDynamicShortcuts(this)

        if(supported){
            if(dynamicShortcuts.contains(shortcutInfo)){
                return
            }
        }
        MaterialAlertDialogBuilder(this).apply {
            setTitle("Add Focus Mode to Home Screen")
            setMessage("Would you like to add Focus Mode to your home screen for quick access?")
            setPositiveButton("Ok") { dialog, _ ->
                sp.edit().putBoolean("focus_mode",true).apply()
                val pinnedShortcutCallbackIntent = Intent("example.intent.action.SHORTCUT_CREATED")

                val successCallback = PendingIntent.getBroadcast(
                    this@MainActivity,
                    1000,
                    pinnedShortcutCallbackIntent,
                    FLAG_IMMUTABLE
                )

                ShortcutManagerCompat.requestPinShortcut(
                    this@MainActivity,
                    shortcutInfo,
                    successCallback.intentSender
                )

            }
            setNegativeButton("Cancel", { _,_ ->
                sp.edit().putBoolean("focus_mode",false).apply()
            })
            show()
        }

    }

    private fun openAccessibilityServiceScreen(cls: Class<*>) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val componentName = ComponentName(this, cls)
            intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())
            val bundle = Bundle()
            bundle.putString(":settings:fragment_args_key", componentName.flattenToString())
            intent.putExtra(":settings:show_fragment_args", bundle)
            startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            e.printStackTrace()

            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun makeRemoveAntiUninstallDialog() {
        try {
        val antiUninstallInfo = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
        val mode = antiUninstallInfo.getInt("mode", -1)
            val isEnabled = antiUninstallInfo.getBoolean("is_anti_uninstall_on", false)
            
            Log.d("MainActivity", "makeRemoveAntiUninstallDialog: mode=$mode, isEnabled=$isEnabled")
            
            if (!isEnabled) {
                Snackbar.make(
                    binding.root,
                    "Anti-uninstall is already disabled",
                    Snackbar.LENGTH_SHORT
                ).show()
                return
            }
            
            if (mode == -1) {
                // Mode is missing - try to restore from backup first
                Log.w("MainActivity", "Anti-uninstall mode is missing, attempting to restore from backup...")
                try {
                    val restored = mina.infoxus.utils.DataBackupManager.restoreAntiUninstall(this)
                    if (restored) {
                        // Retry after restore
                        Log.d("MainActivity", "Restored anti-uninstall data, retrying dialog...")
                        Handler(Looper.getMainLooper()).postDelayed({
                            makeRemoveAntiUninstallDialog()
                        }, 500)
                        return
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error restoring anti-uninstall", e)
                }
                
                // If restore failed or no backup, show password dialog as fallback
                Log.d("MainActivity", "No backup found or restore failed, showing password dialog as fallback")
                val dialogRemoveAntiUninstall =
                    DialogRemoveAntiUninstallBinding.inflate(layoutInflater)
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.remove_anti_uninstall))
                    .setView(dialogRemoveAntiUninstall.root)
                    .setPositiveButton(R.string.remove) { _, _ ->
                        val password = antiUninstallInfo.getString("password", null)
                        if (password != null && password == dialogRemoveAntiUninstall.password.text.toString()) {
                            antiUninstallInfo.edit().putBoolean("is_anti_uninstall_on", false)
                                .apply()
                            mina.infoxus.utils.DataBackupManager.backupAntiUninstall(this)
                            sendRefreshRequest(MainAccessibilityService.ACTION_REFRESH_ANTI_UNINSTALL)

                            Snackbar.make(
                                binding.root,
                                "Anti Uninstall removed",
                                Snackbar.LENGTH_SHORT
                            ).show()

                            checkPermissions()
                        } else {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.incorrect_password_please_try_again),
                                Snackbar.LENGTH_SHORT
                            )
                                .setAction(getString(R.string.retry)) {
                                    makeRemoveAntiUninstallDialog()
                                }
                                .show()
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
                return
            }
            
        when (mode) {

            Constants.ANTI_UNINSTALL_TIMED_MODE -> {
                val dateString = antiUninstallInfo.getString("date", null)
                if (dateString.isNullOrEmpty()) {
                    Toast.makeText(this, "Anti-uninstall data corrupted. Please re-enable.", Toast.LENGTH_SHORT).show()
                    return
                }
                val parts = dateString.split("/")
                if (parts.size < 3) {
                     Toast.makeText(this, "Anti-uninstall date format error.", Toast.LENGTH_SHORT).show()
                     return
                }
                val targetDate = Calendar.getInstance()
                try {
                    targetDate.set(
                        parts[2].trim().toInt(),  
                        parts[0].trim().toInt() - 1,  
                        parts[1].trim().toInt(),  
                        0,  
                        0,  
                        0   
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "Error parsing anti-uninstall date.", Toast.LENGTH_SHORT).show()
                    return
                }
                targetDate.set(Calendar.MILLISECOND, 0)

                val today = Calendar.getInstance()
                today.set(Calendar.HOUR_OF_DAY, 0)
                today.set(Calendar.MINUTE, 0)
                today.set(Calendar.SECOND, 0)
                today.set(Calendar.MILLISECOND, 0)

                val daysDiff =
                    (targetDate.timeInMillis - today.timeInMillis) / (1000 * 60 * 60 * 24)
                if (targetDate.before(today) || daysDiff.toInt() <= 0) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.anti_uninstall_removed),
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                    antiUninstallInfo.edit().putBoolean("is_anti_uninstall_on", false).commit()
                    // Backup the updated state
                    mina.infoxus.utils.DataBackupManager.backupAntiUninstall(this)
                    sendRefreshRequest(MainAccessibilityService.ACTION_REFRESH_ANTI_UNINSTALL)

                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.failed))
                        .setMessage(getString(R.string.remaining_time_anti_uninstall, daysDiff.toInt()))
                        .setPositiveButton("Ok", null)
                        .show()
                }

            }

            Constants.ANTI_UNINSTALL_PASSWORD_MODE -> {
                val dialogRemoveAntiUninstall =
                    DialogRemoveAntiUninstallBinding.inflate(layoutInflater)
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.remove_anti_uninstall))
                    .setView(dialogRemoveAntiUninstall.root)
                    .setPositiveButton(R.string.remove) { _, _ ->
                        if (antiUninstallInfo.getString(
                                "password",
                                "pass"
                            ) == dialogRemoveAntiUninstall.password.text.toString()
                        ) {
                            antiUninstallInfo.edit().putBoolean("is_anti_uninstall_on", false)
                                .commit()
                            // Backup the updated state
                            mina.infoxus.utils.DataBackupManager.backupAntiUninstall(this)
                            sendRefreshRequest(MainAccessibilityService.ACTION_REFRESH_ANTI_UNINSTALL)

                            Snackbar.make(
                                binding.root,
                                "Anti Uninstall removed",
                                Snackbar.LENGTH_SHORT
                            )
                                .show()

                            checkPermissions()
                        } else {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.incorrect_password_please_try_again),
                                Snackbar.LENGTH_SHORT
                            )
                                .setAction(getString(R.string.retry)) {
                                    makeRemoveAntiUninstallDialog()
                                }
                                .show()
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            else -> {
                // Unknown mode - show password dialog as fallback
                Log.w("MainActivity", "Unknown anti-uninstall mode: $mode, showing password dialog as fallback")
                val dialogRemoveAntiUninstall =
                    DialogRemoveAntiUninstallBinding.inflate(layoutInflater)
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.remove_anti_uninstall))
                    .setView(dialogRemoveAntiUninstall.root)
                    .setPositiveButton(R.string.remove) { _, _ ->
                        val password = antiUninstallInfo.getString("password", null)
                        if (password != null && password == dialogRemoveAntiUninstall.password.text.toString()) {
                            antiUninstallInfo.edit().putBoolean("is_anti_uninstall_on", false)
                                .commit()
                            mina.infoxus.utils.DataBackupManager.backupAntiUninstall(this)
                            sendRefreshRequest(MainAccessibilityService.ACTION_REFRESH_ANTI_UNINSTALL)

                            Snackbar.make(
                                binding.root,
                                "Anti Uninstall removed",
                                Snackbar.LENGTH_SHORT
                            ).show()

                            checkPermissions()
                        } else {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.incorrect_password_please_try_again),
                                Snackbar.LENGTH_SHORT
                            )
                                .setAction(getString(R.string.retry)) {
                                    makeRemoveAntiUninstallDialog()
                                }
                                .show()
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing remove anti-uninstall dialog", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }

    }
    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent, options.toBundle())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No application found to open the link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendEmail(email: String, subject: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO)
            intent.data = Uri.parse("mailto:")
            intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            intent.putExtra(Intent.EXTRA_SUBJECT, subject)
            startActivity(Intent.createChooser(intent, "Send Email"))
        } catch (e: Exception) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // Show dialog only once per session
                val prefs = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
                val lastBatteryCheck = prefs.getLong("last_battery_optimization_check", 0)
                val now = System.currentTimeMillis()
                
                // Only show dialog if not shown in last 24 hours
                if (now - lastBatteryCheck > 24 * 60 * 60 * 1000) {
                    prefs.edit().putLong("last_battery_optimization_check", now).apply()
                    
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Battery Optimization")
                        .setMessage("For InFoXus to work properly after reboot, please disable battery optimization. This ensures the app can restore your settings automatically.")
                        .setPositiveButton("Disable Optimization") { _, _ ->
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                startActivity(intent)
                            } catch (e: Exception) {
                                // Fallback to battery settings
                                try {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    startActivity(intent)
                                } catch (e2: Exception) {
                                    Log.e("MainActivity", "Error opening battery optimization settings", e2)
                                }
                            }
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
        }
    }

    data class WarningData(
        val message: String = "Access Restricted",
        val timeInterval: Int = 120000, 
        val isDynamicIntervalSettingAllowed: Boolean = false,
        val isProceedDisabled: Boolean = false,
        val isWarningDialogHidden: Boolean = false, 
        val proceedDelayInSecs: Int = 15
    )

}

