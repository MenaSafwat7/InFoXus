package mina.infoxus.views

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import mina.infoxus.R

class CustomMarkerView(context: Context, layoutResource: Int) :
    MarkerView(context, layoutResource) {

    private val tvContent: TextView = findViewById(R.id.tvContent)

    var showDecimal = true
    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        e?.y?.let { value ->
            tvContent.text = if (showDecimal) {
                "%.2f".format(value)  
            } else {
                value.toInt().toString()  
            }
        }

        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF {

        return MPPointF(-(width / 2).toFloat(), -height.toFloat())
    }
}

