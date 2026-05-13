package mina.infoxus.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mina.infoxus.R
import mina.infoxus.databinding.ActivityManageSitesBinding
import mina.infoxus.databinding.DialogAddSiteBinding
import mina.infoxus.services.MainAccessibilityService
import mina.infoxus.utils.SavedPreferencesLoader

class ManageSitesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManageSitesBinding
    lateinit var savedSitesList: ArrayList<String>
    private lateinit var siteAdapter: SiteAdapter
    private lateinit var savedPreferencesLoader: SavedPreferencesLoader
    private var oldSize = 0
    private var isAntiUninstallEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityManageSitesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        savedSitesList = intent.getStringArrayListExtra("PRE_SAVED_SITES") ?: arrayListOf()
        oldSize = savedSitesList.size

        savedPreferencesLoader = SavedPreferencesLoader(this)

        val antiUninstallInfo = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
        isAntiUninstallEnabled = antiUninstallInfo.getBoolean("is_anti_uninstall_on", false) && 
                                 antiUninstallInfo.getBoolean("is_configuring_blocked", false)

        siteAdapter = SiteAdapter(savedSitesList)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = siteAdapter

        binding.switchAdultSites.isChecked = savedPreferencesLoader.loadIsAdultSitesBlockerEnabled()
        binding.switchAdultSites.setOnCheckedChangeListener { _, isChecked ->
            if (isAntiUninstallEnabled && !isChecked) {
                Toast.makeText(this, "Deactivating adult site blocking is disabled by anti-uninstall", Toast.LENGTH_SHORT).show()
                binding.switchAdultSites.isChecked = true
                return@setOnCheckedChangeListener
            }
            savedPreferencesLoader.saveIsAdultSitesBlockerEnabled(isChecked)
            
            // Notify service to reload the list
            val intent = Intent(MainAccessibilityService.ACTION_REFRESH_KEYWORDS)
            sendBroadcast(intent)
        }

        binding.confirmSelectionSites.setOnClickListener {
            val resultIntent = intent.apply {
                putStringArrayListExtra("SELECTED_SITES", savedSitesList)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
        binding.btnAddSite.setOnClickListener { makeAddSiteDialog() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (oldSize != savedSitesList.size) {
                    showExitDialog()
                } else {
                    finish()
                }
            }
        })
    }

    private fun showExitDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.discard_changes))
            .setMessage(getString(R.string.are_you_sure_you_want_to_discard_all_changes_and_exit))
            .setPositiveButton(getString(R.string.yes)) { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun makeAddSiteDialog() {
        val dialogBinding = DialogAddSiteBinding.inflate(layoutInflater)

        val filter = InputFilter { source, _, _, _, _, _ ->
            if (source.contains(" ")) {
                ""
            } else {
                source
            }
        }

        dialogBinding.siteInput.filters = arrayOf(filter)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_a_new_site))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.add)) { dialog, _ ->
                var site = dialogBinding.siteInput.text.toString().trim()
                if (site.isEmpty()) {
                    return@setPositiveButton
                }
                
                // Extract domain if user entered a full URL
                if (Patterns.WEB_URL.matcher(site).matches()) {
                    val regex = Regex("^(?:https?://)?(?:www\\.)?([^/]+)")
                    site = regex.find(site)?.groupValues?.get(1) ?: site
                }

                if (savedSitesList.contains(site)) {
                    Toast.makeText(this, "Site already exists", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                savedSitesList.add(site)
                siteAdapter.notifyItemInserted(savedSitesList.size - 1)

                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    inner class SiteAdapter(private val sites: ArrayList<String>) :
        RecyclerView.Adapter<SiteAdapter.SiteViewHolder>() {

        inner class SiteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val siteTextView: TextView = itemView.findViewById(R.id.site_txt)
            val removeBtn: Button = itemView.findViewById(R.id.btn_remove_site)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SiteViewHolder {
            val view =
                LayoutInflater.from(parent.context).inflate(R.layout.site_item, parent, false)
            return SiteViewHolder(view)
        }

        override fun onBindViewHolder(holder: SiteViewHolder, position: Int) {
            holder.siteTextView.text = sites[position]
            holder.removeBtn.setOnClickListener {
                if (isAntiUninstallEnabled) {
                    Toast.makeText(this@ManageSitesActivity, "Deleting sites is disabled by anti-uninstall", Toast.LENGTH_SHORT).show()
                } else {
                    savedSitesList.removeAt(position)
                    notifyItemRemoved(position)
                }
            }
        }

        override fun getItemCount(): Int = sites.size
    }
}
