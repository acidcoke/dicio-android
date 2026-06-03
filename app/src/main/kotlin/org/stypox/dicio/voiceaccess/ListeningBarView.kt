package org.stypox.dicio.voiceaccess

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.stypox.dicio.R

/**
 * The "listening" status bar shown at the top of the screen while a Voice Access session is active.
 * Shape mirrors Google Voice Access's feedback overlay (round mic + pill with the live transcript),
 * but colored with dicio's own primary green instead of Voice Access blue.
 */
class ListeningBarView(context: Context) : LinearLayout(context) {

    private val transcriptView: TextView
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val bgColor = ContextCompat.getColor(context, R.color.va_listening_bg)
        background = GradientDrawable().apply {
            cornerRadius = dp(28f).toFloat()
            setColor(bgColor)
        }
        val padH = dp(16f)
        val padV = dp(10f)
        setPadding(padH, padV, padH, padV)
        elevation = dp(4f).toFloat()

        val mic = ImageView(context).apply {
            setImageResource(R.drawable.ic_hearing_white)
            val size = dp(24f)
            layoutParams = LayoutParams(size, size)
        }
        addView(mic)

        transcriptView = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.va_listening_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(10f)
            layoutParams = lp
        }
        addView(transcriptView)

        setTranscript("", isFinal = false)
    }

    fun setTranscript(text: String, isFinal: Boolean) {
        transcriptView.text = if (text.isBlank()) {
            context.getString(R.string.voice_access_listening)
        } else {
            text
        }
        // dim slightly once a final result is in, to signal the turn is ending
        transcriptView.setTextColor(
            if (isFinal) Color.argb(200, 255, 255, 255)
            else ContextCompat.getColor(context, R.color.va_listening_text)
        )
    }
}
