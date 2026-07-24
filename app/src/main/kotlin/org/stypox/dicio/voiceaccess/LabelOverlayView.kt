package org.stypox.dicio.voiceaccess

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

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
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics
        )
        isFakeBoldText = true
    }

    private val chipHeight = dp(24f)
    private val chipHPadding = dp(8f)
    private val chipCorner = dp(12f)
    // how far the chip dips below the element's top edge, so it sits just above without a big gap
    private val chipDip = dp(22f)

    init {
        applyStyle(LabelStyle.DEFAULT)
    }

    fun setLabels(newLabels: List<LabeledNode>) {
        labels = newLabels
        invalidate()
    }

    /** Recomputes the chip/text/outline colors from the user-configured [style]. */
    fun applyStyle(style: LabelStyle) {
        val alpha = (style.opacity.coerceIn(0f, 1f) * 255f).toInt()
        // contrast drives how far the two tones spread from mid-gray
        val c = style.contrast.coerceIn(0f, 1f)
        val darkTone = ((1f - c) * 0.5f * 255f).toInt()   // → black as contrast rises
        val lightTone = ((0.5f + 0.5f * c) * 255f).toInt() // → white as contrast rises

        val (bgTone, fgTone) = if (style.dark) darkTone to lightTone else lightTone to darkTone
        bgPaint.color = Color.argb(alpha, bgTone, bgTone, bgTone)
        textPaint.color = Color.argb(alpha, fgTone, fgTone, fgTone)
        outlinePaint.color = Color.argb(alpha, fgTone, fgTone, fgTone)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF()
        val fontMetrics = textPaint.fontMetrics
        val textBaselineOffset = (fontMetrics.descent + fontMetrics.ascent) / 2f

        // node bounds are in screen coordinates; the overlay window may be inset (e.g. below the
        // status bar / display cutout), so translate screen → view space using our on-screen origin
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        val originX = origin[0]
        val originY = origin[1]

        for (label in labels) {
            val text = label.text
            val textWidth = textPaint.measureText(text)
            val chipWidth = (textWidth + chipHPadding * 2).coerceAtLeast(chipHeight)

            // every chip is centered horizontally over its element
            val anchorTop = label.bounds.top - originY
            val anchorCenterX = label.bounds.exactCenterX() - originX
            var left = anchorCenterX - chipWidth / 2f
            var top = anchorTop.toFloat() - chipHeight + chipDip
            if (top < 0f) {
                // not enough room above: place it just inside the top edge of the element
                top = anchorTop.toFloat()
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
