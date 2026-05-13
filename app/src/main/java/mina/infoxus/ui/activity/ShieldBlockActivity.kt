package mina.infoxus.ui.activity

import android.os.Bundle
import android.widget.TextView
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import mina.infoxus.services.ShieldManager

class ShieldBlockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView = TextView(this).apply {
            text = "Time Limit Reached\nThis app is shielded by InFoXus."
            textSize = 24f
            gravity = Gravity.CENTER
            setOnClickListener {
                // If the user taps the reason, we check if session ended
                if (!ShieldManager.isSessionActive.value) {
                    ShieldManager.hideOverlay()
                    finish()
                }
            }
        }
        setContentView(textView)
    }

    override fun onDestroy() {
        super.onDestroy()
        // TESTPOINT: Hiding overlay when block screen is dismissed
        ShieldManager.hideOverlay()
    }
}
