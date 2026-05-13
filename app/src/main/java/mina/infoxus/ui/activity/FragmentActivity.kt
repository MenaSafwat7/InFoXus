package mina.infoxus.ui.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import mina.infoxus.R
import mina.infoxus.ui.fragments.anti_uninstall.ChooseModeFragment
import mina.infoxus.ui.fragments.installation.AccessibilityGuide
import mina.infoxus.ui.fragments.installation.PermissionsFragment
import mina.infoxus.ui.fragments.usage.AllAppsUsageFragment

class FragmentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_fragment)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        var fragment: Fragment? = null
        if (intent.getStringExtra("fragment") != null) {
            when (intent.getStringExtra("fragment")) {
                ChooseModeFragment.FRAGMENT_ID -> {
                    fragment = ChooseModeFragment()
                }
                AllAppsUsageFragment.FRAGMENT_ID -> {
                    fragment = AllAppsUsageFragment()
                }
                PermissionsFragment.FRAGMENT_ID -> {
                    fragment = PermissionsFragment()
                }
                AccessibilityGuide.FRAGMENT_ID ->
                    fragment = AccessibilityGuide()
            }
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.fragment_holder,
                    fragment!!
                ) 
                .commit() 
        }
    }
}
