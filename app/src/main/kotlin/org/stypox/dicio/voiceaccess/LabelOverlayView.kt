package org.stypox.dicio.voiceaccess

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import org.stypox.dicio.R

/**
 * Full-screen, non-interactive overlay that paints a numbered chip near every clickable element,
 * mimicking Google Voice Access's labels. The chips never receive touches (the host window is
 * added with FLAG_NOT_TOUCHABLE) because selection happens by voice, not by tapping.
 */
class LabelOverlayView(context: Context) : View(context) {

    private var labels: List<LabeledNode> = emptyList()

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.va_label_bg)
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.va_label_outline)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.va_label_text)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics
        )
        isFakeBoldText = true
    }

    private val chipHeight = dp(24f)
    private val chipHPadding = dp(8f)
    private val chipCorner = dp(12f)

    fun setLabels(newLabels: List<LabeledNode>) {
        labels = newLabels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF()
        val fontMetrics = textPaint.fontMetrics
        val textBaselineOffset = (fontMetrics.descent + fontMetrics.ascent) / 2f

        for (label in labels) {
            val text = label.number.toString()
            val textWidth = textPaint.measureText(text)
            val chipWidth = (textWidth + chipHPadding * 2).coerceAtLeast(chipHeight)

            // anchor the chip at the top-left corner of the element, nudged so it stays on screen
            val anchor = label.bounds
            var left = anchor.left.toFloat()
            var top = anchor.top.toFloat() - chipHeight
            if (top < 0f) {
                // not enough room above: place it just inside the top edge of the element
                top = anchor.top.toFloat()
            }
            left = left.coerceIn(0f, (width - chipWidth).coerceAtLeast(0f))
            top = top.coerceIn(0f, (height - chipHeight).coerceAtLeast(0f))

            rect.set(left, top, left + chipWidth, top + chipHeight)
            canvas.drawRoundRect(rect, chipCorner, chipCorner, bgPaint)
            canvas.drawRoundRect(rect, chipCorner, chipCorner, outlinePaint)
            canvas.drawText(
                text,
                rect.centerX(),
                rect.centerY() - textBaselineOffset,
                textPaint,
            )
        }
    }

    @Suppress("unused")
    private fun Rect.isOnScreen(): Boolean = !isEmpty
}
