package nethical.digipaws.ui.activity

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
import nethical.digipaws.Constants
import nethical.digipaws.R
import nethical.digipaws.databinding.ActivityMainBinding
import nethical.digipaws.databinding.DialogPermissionInfoBinding
import nethical.digipaws.databinding.DialogRemoveAntiUninstallBinding
import nethical.digipaws.receivers.AdminReceiver
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.services.GeneralFeaturesService
import nethical.digipaws.ui.dialogs.StartFocusMode
import nethical.digipaws.ui.dialogs.TweakAppBlockerWarning
import nethical.digipaws.ui.fragments.anti_uninstall.ChooseModeFragment
import nethical.digipaws.ui.fragments.installation.WelcomeFragment
import nethical.digipaws.utils.SavedPreferencesLoader
import nethical.digipaws.utils.LocationPreferencesManager
import nethical.digipaws.utils.ServiceStateManager
import nethical.digipaws.ui.activity.LocationBlockerActivity
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var selectPinnedAppsLauncher: ActivityResultLauncher<Intent>

    private lateinit var selectBlockedAppsLauncher: ActivityResultLauncher<Intent>

    private lateinit var selectFocusModeUnblockedAppsLauncher: ActivityResultLauncher<Intent>

    private lateinit var addCheatHoursActivity: ActivityResultLauncher<Intent>

    private lateinit var addAutoFocusHoursActivity: ActivityResultLauncher<Intent>

    private val savedPreferencesLoader = SavedPreferencesLoader(this)
    private val serviceStateManager: ServiceStateManager by lazy { ServiceStateManager(this) }
    private lateinit var options: ActivityOptionsCompat
    private var isDeviceAdminOn = false
    private var isAntiUninstallOn = false
    private var doesAntiUninstallBlockView = false

    private var isGeneralSettingsOn = false
    private var hasShownServiceLossDialogThisSession = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {

                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()

            } else {

                Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()

            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        options = ActivityOptionsCompat.makeCustomAnimation(this, R.anim.fade_in, R.anim.fade_out)
        setupActivityLaunchers()
        setupClickListeners()

        if (!isFirstLaunchComplete()) {
            val intent = Intent(this, FragmentActivity::class.java)
            intent.putExtra("fragment", WelcomeFragment.FRAGMENT_ID)
            startActivity(intent, options.toBundle())
        } else {

            checkServiceStateLossAfterUpdate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
    override fun onResume() {
        super.onResume()
        checkPermissions()

        checkServiceStateLossAfterUpdate()
    }

    private fun checkServiceStateLossAfterUpdate() {

        if (hasShownServiceLossDialogThisSession) {
            return
        }

        lifecycleScope.launch {
            val serviceLoss = withContext(Dispatchers.IO) {
                serviceStateManager.detectServiceStateLoss()
            }

            if (serviceLoss.hasLoss && serviceLoss.lostServices.isNotEmpty()) {
                hasShownServiceLossDialogThisSession = true
                withContext(Dispatchers.Main) {
                    showServiceStateLossDialog(serviceLoss.lostServices)
                }
            }
        }
    }

    private fun showServiceStateLossDialog(lostServices: List<String>) {
        val servicesList = lostServices.joinToString("\n• ", "• ")
        val hasDeviceAdmin = lostServices.contains("Device Admin")
        val hasAccessibilityServices = lostServices.any { it != "Device Admin" }

        val message = """
            ⚠️ Critical Security Alert ⚠️

            The following services have been disabled:

            $servicesList

            This typically happens after reinstalling or updating the app. This is a security issue! Without these services enabled, you may be able to uninstall the app, which could compromise your protection.

            Please re-enable these services immediately to restore full protection.
        """.trimIndent()

        val dialogBuilder = MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Services Disabled")
            .setMessage(message)
            .setCancelable(false)

        if (hasAccessibilityServices) {
            dialogBuilder.setPositiveButton("Re-enable Services") { _, _ ->

                val serviceToOpen = if (lostServices.contains("App Blocker")) {
                    AppBlockerService::class.java
                } else {
                    GeneralFeaturesService::class.java
                }
                openAccessibilityServiceScreen(serviceToOpen)
            }
        }

        if (hasDeviceAdmin) {
            val buttonText = if (hasAccessibilityServices) "Enable Device Admin" else "Enable Device Admin"
            dialogBuilder.setNeutralButton(buttonText) { _, _ ->
                makeDeviceAdminPermissionDialog()
            }
        }

        if (hasAccessibilityServices && !hasDeviceAdmin) {
            dialogBuilder.setNeutralButton("Open Settings") { _, _ ->

                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        dialogBuilder.show()
    }

    private fun setupActivityLaunchers() {

        selectPinnedAppsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                selectedApps?.let {
                    savedPreferencesLoader.savePinned(it.toSet())
                }
            }
        }

        selectBlockedAppsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                    selectedApps?.let {
                        savedPreferencesLoader.saveBlockedApps(it.toSet())
                        sendRefreshRequest(AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER)
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
                sendRefreshRequest(AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER)
            }

        addAutoFocusHoursActivity =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
                sendRefreshRequest(AppBlockerService.INTENT_ACTION_REFRESH_FOCUS_MODE)
            }
    }

    private fun setupClickListeners() {

        binding.selectPinnedApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(savedPreferencesLoader.loadPinnedApps())
            )

            selectPinnedAppsLauncher.launch(intent, options)

        }
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
        binding.btnConfigAppblockerWarning.setOnClickListener {
            TweakAppBlockerWarning(savedPreferencesLoader).show(
                supportFragmentManager,
                "tweak_app_blocker_warning"
            )
        }
        binding.btnUnlockAntiUninstall.setOnClickListener {
            makeRemoveAntiUninstallDialog()
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS,options)
                    return@setOnClickListener
                }
            }

            createFocusModeShortcut()

            StartFocusMode(savedPreferencesLoader, onPositiveButtonPressed = {
                binding.selectFocusBlockedApps.isEnabled = false
                binding.startFocusMode.isEnabled = false

            }).show(
                supportFragmentManager,
                "start_focus_mode"
            )

        }

        binding.antiUninstallCardChip.setOnClickListener {
            if (!isDeviceAdminOn) {
                makeDeviceAdminPermissionDialog()
            } else {
                if (binding.antiUninstallWarning.visibility == View.GONE) {
                    val intent = Intent(this, FragmentActivity::class.java)
                    intent.putExtra("fragment", ChooseModeFragment.FRAGMENT_ID)
                    startActivity(intent, options.toBundle())
                } else {
                    makeAccessibilityInfoDialog(
                        "General Features",
                        GeneralFeaturesService::class.java
                    )
                }
            }
        }

        binding.focusModeStatusChip.setOnClickListener {
            makeAccessibilityInfoDialog("App Blocker", AppBlockerService::class.java)
        }
        binding.appBlockerStatusChip.setOnClickListener {
            makeAccessibilityInfoDialog("App Blocker", AppBlockerService::class.java)
        }
        binding.btnOpenLocationBlocker.setOnClickListener {
            if (doesAntiUninstallBlockView && isAntiUninstallOn) {
                Toast.makeText(this, "Settings are locked by anti-uninstall", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, LocationBlockerActivity::class.java)
            startActivity(intent, options.toBundle())
        }

        binding.btnContactLinkedin.setOnClickListener {
            openUrl("https://www.linkedin.com")
        }
        binding.btnContactGithub.setOnClickListener {
            openUrl("https://github.com")
        }
        binding.btnContactWhatsapp.setOnClickListener {
            openWhatsApp("+201274707196")
        }
    }

    private fun checkPermissions() {
        lifecycleScope.launch {
            val isAppBlockerOn =
                withContext(Dispatchers.IO) { isAccessibilityServiceEnabled(AppBlockerService::class.java) }
            isGeneralSettingsOn =
                withContext(Dispatchers.IO) { isAccessibilityServiceEnabled(GeneralFeaturesService::class.java) }

            val devicePolicyManager =
                getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(applicationContext, AdminReceiver::class.java)

            isDeviceAdminOn = devicePolicyManager.isAdminActive(componentName)

            serviceStateManager.saveDeviceAdminState(isDeviceAdminOn)

            val antiUninstallInfo = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
            isAntiUninstallOn = antiUninstallInfo.getBoolean("is_anti_uninstall_on", false)
            doesAntiUninstallBlockView =
                antiUninstallInfo.getBoolean("is_configuring_blocked", false)

            val locationPrefsManager = LocationPreferencesManager(this@MainActivity)
            val locationConfig = locationPrefsManager.getConfig()
            val isLocationBlockerEnabled = locationConfig.isEnabled && locationConfig.locations.isNotEmpty()

            withContext(Dispatchers.Main) {

                updateChip(isAppBlockerOn, binding.appBlockerStatusChip, binding.appBlockerWarning)
                binding.apply {
                    selectBlockedApps.isEnabled = isAppBlockerOn
                    btnConfigAppblockerWarning.isEnabled = isAppBlockerOn
                    appBlockerSelectCheatHours.isEnabled = isAppBlockerOn
                }

                updateChip(
                    isAppBlockerOn,
                    binding.focusModeStatusChip,
                    binding.focusModeWarning
                )
                binding.apply {
                    startFocusMode.isEnabled = isAppBlockerOn
                    selectFocusBlockedApps.isEnabled = isAppBlockerOn
                    autoFocus.isEnabled = isAppBlockerOn
                }

                updateChip(
                    isLocationBlockerEnabled,
                    binding.locationBlockerStatusChip,
                    binding.locationBlockerWarning
                )
                binding.btnOpenLocationBlocker.isEnabled = !(doesAntiUninstallBlockView && isAntiUninstallOn)

                binding.btnUnlockAntiUninstall.isEnabled = isAntiUninstallOn

                if (!isDeviceAdminOn) {
                    binding.antiUninstallWarning.text =
                        getString(R.string.please_enable_device_admin)
                } else if (!isGeneralSettingsOn) {
                    binding.antiUninstallWarning.text = getString(R.string.warning_general_settings)
                }

                if (isDeviceAdminOn && isGeneralSettingsOn) {
                    updateChip(true, binding.antiUninstallCardChip, binding.antiUninstallWarning)
                    binding.antiUninstallCardChip.isEnabled = !isAntiUninstallOn
                    binding.antiUninstallCardChip.text =
                        if (isAntiUninstallOn) getString(R.string.setup_complete) else getString(R.string.enter_setup)
                }

                if (doesAntiUninstallBlockView && isAntiUninstallOn) {
                    binding.apply {
                        btnConfigAppblockerWarning.isEnabled = false
                        selectBlockedApps.isEnabled = false
                        appBlockerSelectCheatHours.isEnabled = false
                        startFocusMode.isEnabled = false
                        autoFocus.isEnabled = false  
                        selectFocusBlockedApps.isEnabled = false  
                        btnOpenLocationBlocker.isEnabled = false  
                    }
                }
                if (isAppBlockerOn) {
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

    private fun updateChip(isEnabled: Boolean,statusChip: Chip,warningText:TextView) {
        if (isEnabled) {
            statusChip.text = getString(R.string.enabled)
            statusChip.chipIcon = null
            warningText.visibility = View.GONE
        } else {
            statusChip.text = getString(R.string.disabled)
            statusChip.setChipIconResource(R.drawable.baseline_warning_24)
            warningText.visibility = View.VISIBLE
        }
    }
    private fun sendRefreshRequest(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }
    private fun isAccessibilityServiceEnabled(serviceClass: Class<out AccessibilityService>): Boolean {
        val serviceName = ComponentName(this, serviceClass).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val isAccessibilityEnabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        return isAccessibilityEnabled == 1 && enabledServices.contains(serviceName)
    }

    private fun makeDeviceAdminPermissionDialog() {
        val dialogDeviceAdmin =
            DialogPermissionInfoBinding.inflate(layoutInflater)
        dialogDeviceAdmin.title.text = getString(R.string.enable_2, "Device Admin")
        dialogDeviceAdmin.desc.text = getString(R.string.device_admin_perm)
        dialogDeviceAdmin.point1.text =
            getString(R.string.prevent_uninstallation_attempts_until_a_set_condition_is_met)
        dialogDeviceAdmin.point2.visibility = View.GONE
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogDeviceAdmin.root)
            .show()

        dialogDeviceAdmin.btnReject.setOnClickListener {
            dialog.dismiss()
        }
        dialogDeviceAdmin.btnAccept.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            val componentName = ComponentName(this, AdminReceiver::class.java)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enable admin to enable anti uninstall."
            )
            startActivity(intent, options.toBundle())

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
        val antiUninstallInfo = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
        val mode = antiUninstallInfo.getInt("mode", -1)
        when (mode) {

            Constants.ANTI_UNINSTALL_TIMED_MODE -> {
                val dateString = antiUninstallInfo.getString("date", null)
                val parts: List<String> = dateString!!.split("/")
                val selectedDate = Calendar.getInstance()
                selectedDate.set(
                    Integer.parseInt(parts[2]),  
                    Integer.parseInt(parts[0]) - 1,  
                    Integer.parseInt(parts[1]),  
                    0,  
                    0,  
                    0   
                )
                selectedDate.set(Calendar.MILLISECOND, 0)

                val today = Calendar.getInstance()
                today.set(Calendar.HOUR_OF_DAY, 0)
                today.set(Calendar.MINUTE, 0)
                today.set(Calendar.SECOND, 0)
                today.set(Calendar.MILLISECOND, 0)

                val daysDiff =
                    (selectedDate.timeInMillis - today.timeInMillis) / (1000 * 60 * 60 * 24)
                if (selectedDate.before(today) || daysDiff.toInt() == 0) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.anti_uninstall_removed),
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                    antiUninstallInfo.edit().putBoolean("is_anti_uninstall_on", false).commit()
                    sendRefreshRequest(GeneralFeaturesService.INTENT_ACTION_REFRESH_ANTI_UNINSTALL)

                } else {

                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.failed))
                        .setMessage(getString(R.string.remaining_time_anti_uninstall, daysDiff))
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
                            sendRefreshRequest(GeneralFeaturesService.INTENT_ACTION_REFRESH_ANTI_UNINSTALL)

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

    private fun openWhatsApp(phoneNumber: String) {
        try {
            val cleanNumber = phoneNumber.replace("+", "").replace(" ", "")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://wa.me/$cleanNumber")
            startActivity(intent, options.toBundle())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    data class WarningData(
        val message: String = "You can setup a custom message to appear here!",
        val timeInterval: Int = 120000, 
        val isDynamicIntervalSettingAllowed: Boolean = false,
        val isProceedDisabled: Boolean = false,
        val isWarningDialogHidden: Boolean = false, 
        val proceedDelayInSecs: Int = 15
    )

}