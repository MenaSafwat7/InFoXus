package nethical.digipaws.ui.fragments.installation

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
import nethical.digipaws.R
import nethical.digipaws.databinding.FragmentPermissionsBinding

class PermissionsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "permission_fragment"
    }

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!  // Safe getter for binding

    private var nGivenPermissions = 0
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            setPermissionIcon(isGranted, binding.notifPermIcon)
        }

    private val batteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            setPermissionIcon(isBackgroundPermissionGiven(), binding.bgPermIcon)
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
            sharedPreferences.edit().putBoolean("isFirstLaunchComplete", true).apply()

            // Finish setup - no longer navigate to Quick Setup Guide
            requireActivity().finish()
        }
        setPermissionIcon(isBackgroundPermissionGiven(), binding.bgPermIcon)
        setPermissionIcon(isNotificationPermissionGiven(), binding.notifPermIcon)

        binding.notifPermRoot.setOnClickListener {
            if (isNotificationPermissionGiven()) return@setOnClickListener
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.bgPermRoot.setOnClickListener {
            if (isBackgroundPermissionGiven()) return@setOnClickListener
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            batteryOptimizationLauncher.launch(intent)
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setPermissionIcon(isEnabled: Boolean, icon: ImageView) {
        if (isEnabled) {
            icon.setImageResource(R.drawable.baseline_done_24)
            icon.setColorFilter(R.color.md_theme_onSurface)
            nGivenPermissions++
            if (nGivenPermissions > 1) {
                binding.btnNext.isEnabled = true
            }
        } else {
            icon.setImageResource(R.drawable.baseline_close_24)
            icon.setColorFilter(R.color.error_color)
        }
    }

    private fun isBackgroundPermissionGiven(): Boolean {
        val powerManager =
            requireContext().getSystemService(POWER_SERVICE) as PowerManager
        val packageName = requireContext().packageName
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isNotificationPermissionGiven(): Boolean {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

}