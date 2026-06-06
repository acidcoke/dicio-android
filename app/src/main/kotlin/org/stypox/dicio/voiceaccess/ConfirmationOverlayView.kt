package org.stypox.dicio.voiceaccess

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.stypox.dicio.R

/**
 * A touchable, modal-style overlay asking the user whether to keep using Voice Access after several
 * commands could not be understood. It offers "Continue" and "Stop" buttons; while it is shown, only
 * the voice commands "continue"/"resume" (and these buttons) are honored.
 */
class ConfirmationOverlayView(
    context: Context,
    onContinue: () -> Unit,
    onStop: () -> Unit,
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).toInt()

    init {
        // dim scrim behind the card to signal that a response is needed
        setBackgroundColor(Color.argb(140, 0, 0, 0))

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(ContextCompat.getColor(context, R.color.va_confirmation_bg))
            }
            val pad = dp(20f)
            setPadding(pad, pad, pad, pad)
            elevation = dp(8f).toFloat()
        }

        card.addView(TextView(context).apply {
            text = context.getString(R.string.voice_access_continue_prompt)
            setTextColor(ContextCompat.getColor(context, R.color.va_confirmation_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(16f)
            layoutParams = lp
        }

        buttonRow.addView(Button(context).apply {
            text = context.getString(R.string.voice_access_stop)
            setOnClickListener { onStop() }
        })
        buttonRow.addView(Button(context).apply {
            text = context.getString(R.string.voice_access_continue)
            setOnClickListener { onContinue() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginStart = dp(8f)
            layoutParams = lp
        })
        card.addView(buttonRow)

        val cardLp = LayoutParams(dp(300f), LayoutParams.WRAP_CONTENT)
        cardLp.gravity = Gravity.CENTER
        addView(card, cardLp)
    }
}
