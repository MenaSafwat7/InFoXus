package mina.infoxus.ui.activity

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import mina.infoxus.services.ShieldManager

class PinChallengeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        
        val btn = Button(this).apply {
            text = "Pass Challenge (Mock)"
            setOnClickListener {
                // TESTPOINT: Hiding overlay on successful PIN
                ShieldManager.hideOverlay()
                finish()
            }
        }
        
        layout.addView(btn)
        setContentView(layout)
    }
}
