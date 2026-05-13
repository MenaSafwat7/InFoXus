package mina.infoxus.ui.fragments.installation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity.POWER_SERVICE
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import mina.infoxus.R
import mina.infoxus.databinding.FragmentPermissionsBinding
import mina.infoxus.services.MainAccessibilityService

class PermissionsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "permission_fragment"
    }

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!  




    private val batteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            setPermissionIcon(isBackgroundPermissionGiven(), binding.bgPermIcon)
            updateNextButtonState()
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                          permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            setPermissionIcon(granted, binding.locPermIcon)
            updateNextButtonState()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("BatteryLife")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnNext.setOnClickListener {
            val sharedPreferences =
                requireContext().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            sharedPreferences.edit().putBoolean("isFirstLaunchComplete", true).commit()

            // Backup the first launch flag
            mina.infoxus.utils.DataBackupManager.backupFirstLaunch(requireContext())

            requireActivity().finish()
        }
        
        refreshPermissions()


        binding.bgPermRoot.setOnClickListener {
            if (isBackgroundPermissionGiven()) return@setOnClickListener
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            batteryOptimizationLauncher.launch(intent)
        }
        binding.accPermRoot.setOnClickListener {
            if (isAccessibilityServiceEnabled()) return@setOnClickListener
            openAccessibilityServiceScreen()
        }
        binding.locPermRoot.setOnClickListener {
            if (isLocationPermissionGiven()) return@setOnClickListener
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        setPermissionIcon(isBackgroundPermissionGiven(), binding.bgPermIcon)

        setPermissionIcon(isAccessibilityServiceEnabled(), binding.accPermIcon)
        setPermissionIcon(isLocationPermissionGiven(), binding.locPermIcon)
        updateNextButtonState()
    }

    private fun updateNextButtonState() {
        val allGranted = isBackgroundPermissionGiven() &&

                         isAccessibilityServiceEnabled() &&
                         isLocationPermissionGiven()
        binding.btnNext.isEnabled = allGranted
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setPermissionIcon(isEnabled: Boolean, icon: ImageView) {
        if (isEnabled) {
            icon.setImageResource(R.drawable.baseline_done_24)
            icon.setColorFilter(requireContext().getColor(R.color.lavender_primary))
        } else {
            icon.setImageResource(R.drawable.baseline_close_24)
            icon.setColorFilter(requireContext().getColor(R.color.error_color))
        }
    }

    private fun isBackgroundPermissionGiven(): Boolean {
        val powerManager =
            requireContext().getSystemService(POWER_SERVICE) as PowerManager
        val packageName = requireContext().packageName
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isNotificationPermissionGiven(): Boolean {
        return true
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val ctx = context ?: return false
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        val enabledServices = am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
        
        val isEnabledByManager = enabledServices?.any { 
            it.resolveInfo.serviceInfo.packageName == ctx.packageName && 
            it.resolveInfo.serviceInfo.name == MainAccessibilityService::class.java.name 
        } ?: false

        if (isEnabledByManager) return true

        try {
            val expectedId = android.content.ComponentName(ctx, MainAccessibilityService::class.java).flattenToString()
            val enabledServicesStr = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return enabledServicesStr?.contains(expectedId) == true
        } catch (e: Exception) {
            return false
        }
    }

    private fun isLocationPermissionGiven(): Boolean {
        return ActivityCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAccessibilityServiceScreen() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val componentName = android.content.ComponentName(requireContext(), MainAccessibilityService::class.java)
            intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())
            val bundle = Bundle()
            bundle.putString(":settings:fragment_args_key", componentName.flattenToString())
            intent.putExtra(":settings:show_fragment_args", bundle)
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
