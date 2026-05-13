package mina.infoxus.ui.activity

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mina.infoxus.R
import mina.infoxus.data.AppUsageLimit
import mina.infoxus.data.UsageLimitsConfig
import mina.infoxus.databinding.ActivityManageUsageLimitsBinding
import mina.infoxus.databinding.DialogAddUsageLimitBinding
import mina.infoxus.databinding.ItemUsageLimitBinding
import mina.infoxus.services.MainAccessibilityService
import mina.infoxus.utils.LocationPreferencesManager
import mina.infoxus.utils.SavedPreferencesLoader

class ManageUsageLimitsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManageUsageLimitsBinding
    private lateinit var savedPreferencesLoader: SavedPreferencesLoader
    private lateinit var locationPrefsManager: LocationPreferencesManager
    private lateinit var adapter: UsageLimitsAdapter
    private var config: UsageLimitsConfig = UsageLimitsConfig()
    private var isAntiUninstallEnabled = false

    private var editingLimit: AppUsageLimit? = null
    private val editAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (selectedApps != null && editingLimit != null) {
                val newList = config.limits.toMutableList()
                val index = newList.indexOfFirst { it.name == editingLimit?.name }
                if (index != -1) {
                    newList[index] = newList[index].copy(packageNames = selectedApps.toSet())
                    config = config.copy(limits = newList)
                    saveConfig()
                    loadLimits()
                }
            }
        }
        editingLimit = null
    }

    private var selectedPackagesForNewLimit: Set<String> = emptySet()

    private val selectAppLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (!selectedApps.isNullOrEmpty()) {
                selectedPackagesForNewLimit = selectedApps.toSet()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityManageUsageLimitsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        savedPreferencesLoader = SavedPreferencesLoader(this)
        locationPrefsManager = LocationPreferencesManager(this)

        val antiUninstallInfo = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
        isAntiUninstallEnabled = antiUninstallInfo.getBoolean("is_anti_uninstall_on", false)
        // Note: The original code also checked is_configuring_blocked, but usually if anti-uninstall is ON, we should block changes.
        // The user specifically said "this feature can't edit while anti uninstall enabled".

        setupToolbar()
        setupRecyclerView()
        loadLimits()
        setupResetTimeUI()

        binding.fabAddLimit.setOnClickListener {
            // "i can add new limit apps by press + in the buttom" - Enabled even if anti-uninstall is on
            showAddLimitDialog()
        }
    }

    private fun setupResetTimeUI() {
        binding.btnChangeResetTime.setOnClickListener {
            if (isAntiUninstallEnabled) {
                Toast.makeText(this, "Changing reset time is disabled by anti-uninstall", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showTimePickerDialog()
        }
        updateResetTimeText()
    }

    private fun updateResetTimeText() {
        val hour = config.resetHour
        val minute = config.resetMinute
        val amPm = if (hour < 12) "AM" else "PM"
        val hour12 = if (hour == 0 || hour == 12) 12 else hour % 12
        binding.tvResetTime.text = String.format("%d:%02d %s", hour12, minute, amPm)
    }

    private fun showTimePickerDialog() {
        val timePicker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_12H)
            .setHour(config.resetHour)
            .setMinute(config.resetMinute)
            .setTitleText("Select Reset Time")
            .build()

        timePicker.addOnPositiveButtonClickListener {
            config = config.copy(resetHour = timePicker.hour, resetMinute = timePicker.minute)
            saveConfig()
            updateResetTimeText()
        }

        timePicker.show(supportFragmentManager, "reset_time_picker")
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = UsageLimitsAdapter(
            onToggle = { limit, isEnabled ->
                if (isAntiUninstallEnabled) {
                    Toast.makeText(this, "Toggling limits is disabled by anti-uninstall", Toast.LENGTH_SHORT).show()
                    adapter.notifyDataSetChanged()
                } else {
                    limit.enabled = isEnabled
                    saveConfig()
                }
            },
            onClick = { limit ->
                showLimitOptions(limit)
            },
            onLongClick = { limit ->
                if (isAntiUninstallEnabled) {
                    Toast.makeText(this, "Deleting limits is disabled by anti-uninstall", Toast.LENGTH_SHORT).show()
                } else {
                    showDeleteConfirmation(limit)
                }
            }
        )
        binding.rvUsageLimits.layoutManager = LinearLayoutManager(this)
        binding.rvUsageLimits.adapter = adapter
    }

    private fun loadLimits() {
        config = savedPreferencesLoader.loadUsageLimits()
        adapter.setLimits(config.limits)
        binding.tvEmptyState.visibility = if (config.limits.isEmpty()) View.VISIBLE else View.GONE
        if (::binding.isInitialized) {
            updateResetTimeText()
        }
    }

    private fun saveConfig() {
        savedPreferencesLoader.saveUsageLimits(config)
        sendBroadcast(Intent(MainAccessibilityService.ACTION_REFRESH_USAGE_LIMITS))
    }

    private fun showAddLimitDialog() {
        val dialogBinding = DialogAddUsageLimitBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnSelectApp.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(selectedPackagesForNewLimit))
            selectAppLauncher.launch(intent)
            
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val checkRunnable = object : Runnable {
                override fun run() {
                    if (selectedPackagesForNewLimit.isNotEmpty()) {
                        val text = if (selectedPackagesForNewLimit.size == 1) {
                            val name = getAppName(selectedPackagesForNewLimit.first())
                            if (dialogBinding.etLimitName.text.isNullOrEmpty()) {
                                dialogBinding.etLimitName.setText(name)
                            }
                            name
                        } else {
                            "${selectedPackagesForNewLimit.size} apps selected"
                        }
                        dialogBinding.tvSelectedApp.text = text
                    } else {
                        handler.postDelayed(this, 500)
                    }
                }
            }
            handler.post(checkRunnable)
        }

        dialogBinding.checkboxLocationRestricted.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.tilLocation.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val locations = locationPrefsManager.getConfig().locations
        val locationNames = locations.map { it.name }
        val locationAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, locationNames)
        dialogBinding.spinnerLocation.setAdapter(locationAdapter)

        dialogBinding.btnCancel.setOnClickListener {
            selectedPackagesForNewLimit = emptySet()
            dialog.dismiss()
        }

        dialogBinding.btnSave.setOnClickListener {
            val pkgs = selectedPackagesForNewLimit
            val name = dialogBinding.etLimitName.text.toString().trim()
            val timeLimitStr = dialogBinding.etTimeLimit.text.toString()

            if (pkgs.isEmpty()) {
                Toast.makeText(this, "Please select at least one app", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter a name for this limit", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (timeLimitStr.isEmpty()) {
                Toast.makeText(this, "Please enter a time limit", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val timeLimit = timeLimitStr.toIntOrNull() ?: 0
            if (timeLimit <= 0) {
                Toast.makeText(this, "Invalid time limit", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var locationId: String? = null
            var locationName: String? = null
            if (dialogBinding.checkboxLocationRestricted.isChecked) {
                val selectedName = dialogBinding.spinnerLocation.text.toString()
                val location = locations.find { it.name == selectedName }
                if (location == null) {
                    Toast.makeText(this, "Please select a valid location", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                locationId = location.id
                locationName = location.name
            }

            val newLimit = AppUsageLimit(
                packageNames = pkgs,
                name = name,
                timeLimitMinutes = timeLimit,
                isLocationRestricted = locationId != null,
                locationId = locationId,
                locationName = locationName
            )

            val newList = config.limits.toMutableList()
            newList.add(newLimit)
            config = config.copy(limits = newList)
            saveConfig()
            loadLimits()

            selectedPackagesForNewLimit = emptySet()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showLimitOptions(limit: AppUsageLimit) {
        val options = arrayOf("View Breakdown", "Add/Edit Apps")
        MaterialAlertDialogBuilder(this)
            .setTitle(limit.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAppUsageBreakdown(limit)
                    1 -> showEditAppsDialog(limit)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun showEditAppsDialog(limit: AppUsageLimit) {
        editingLimit = limit
        val intent = Intent(this, SelectAppsActivity::class.java)
        intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(limit.packageNames))
        editAppsLauncher.launch(intent)
    }

    private fun showAppUsageBreakdown(limit: AppUsageLimit) {
        val message = StringBuilder()
        message.append("Total Group Limit: ${limit.timeLimitMinutes}m\n")
        message.append("Total Group Used: ${limit.currentUsageMinutes.toInt()}m\n\n")
        message.append("Individual App Usage:\n")

        if (limit.packageNames.isEmpty()) {
            message.append("No apps in this group.")
        } else {
            limit.packageNames.sortedByDescending { limit.appUsageMap[it] ?: 0f }.forEach { pkg ->
                val appName = getAppName(pkg)
                val used = limit.appUsageMap[pkg] ?: 0f
                message.append("• $appName: ${used.toInt()}m\n")
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("${limit.name} Breakdown")
            .setMessage(message.toString())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showDeleteConfirmation(limit: AppUsageLimit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Limit")
            .setMessage("Are you sure you want to delete the usage limit for ${limit.name}?")
            .setPositiveButton("Delete") { _, _ ->
                val newList = config.limits.toMutableList()
                newList.remove(limit)
                config = config.copy(limits = newList)
                saveConfig()
                loadLimits()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    inner class UsageLimitsAdapter(
        private val onToggle: (AppUsageLimit, Boolean) -> Unit,
        private val onClick: (AppUsageLimit) -> Unit,
        private val onLongClick: (AppUsageLimit) -> Unit
    ) : RecyclerView.Adapter<UsageLimitsAdapter.ViewHolder>() {

        private var limits: List<AppUsageLimit> = emptyList()

        fun setLimits(newLimits: List<AppUsageLimit>) {
            limits = newLimits
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemUsageLimitBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(limits[position])
        }

        override fun getItemCount(): Int = limits.size

        inner class ViewHolder(private val binding: ItemUsageLimitBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(limit: AppUsageLimit) {
                binding.tvAppName.text = limit.name
                binding.tvLimitInfo.text = "Limit: ${limit.timeLimitMinutes}m | Used: ${limit.currentUsageMinutes.toInt()}m"
                
                if (limit.isLocationRestricted) {
                    binding.tvLocationRestricted.visibility = View.VISIBLE
                    binding.tvLocationRestricted.text = "Restricted to: ${limit.locationName}"
                } else {
                    binding.tvLocationRestricted.visibility = View.GONE
                }

                binding.switchEnabled.setOnCheckedChangeListener(null)
                binding.switchEnabled.isChecked = limit.enabled
                binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(limit, isChecked)
                }

                binding.root.setOnClickListener {
                    onClick(limit)
                }

                binding.root.setOnLongClickListener {
                    onLongClick(limit)
                    true
                }

                try {
                    // Show icon of the first app in the list
                    val icon = packageManager.getApplicationIcon(limit.packageNames.first())
                    binding.ivAppIcon.setImageDrawable(icon)
                } catch (e: Exception) {
                    binding.ivAppIcon.setImageResource(R.mipmap.ic_launcher)
                }

                binding.progressUsage.progress = ((limit.currentUsageMinutes / limit.timeLimitMinutes) * 100).toInt()
            }
        }
    }
}
