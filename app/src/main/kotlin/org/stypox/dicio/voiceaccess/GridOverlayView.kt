package org.stypox.dicio.voiceaccess

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.TypedValue
import android.view.View

/**
 * Full-screen, non-interactive overlay that paints the chess-notation grid: dotted light-grey cell
 * lines, column letters (a–h) along the top and row numbers along the left edge. When a cell has
 * been selected for refinement, a solid border plus a dashed 3×3 sub-grid with its own small a–c /
 * 1–3 headers is drawn inside it. Like [LabelOverlayView], the window never receives touches.
 */
class GridOverlayView(context: Context) : View(context) {

    private var geometry: GridGeometry? = null
    private var subGridCell: Pair<Int, Int>? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private fun sp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics
    )

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    // solid border around the cell being refined, slightly thicker so it stands out
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
        isFakeBoldText = true
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
        isFakeBoldText = true
    }

    init {
        applyOpacity(DEFAULT_OPACITY_PERCENT / 100f)
    }

    /** Recomputes the line/text alpha from the user-configured opacity (0..1). */
    fun applyOpacity(opacity: Float) {
        val alpha = (opacity.coerceIn(0f, 1f) * 255f).toInt()
        val grey = Color.argb(alpha, GREY_TONE, GREY_TONE, GREY_TONE)
        linePaint.color = grey
        solidPaint.color = grey
        textPaint.color = grey
        subTextPaint.color = grey
        // a slight dark shadow keeps the light-grey text readable on white backgrounds
        val shadow = Color.argb(alpha, 0, 0, 0)
        textPaint.setShadowLayer(dp(1.5f), 0f, 0f, shadow)
        subTextPaint.setShadowLayer(dp(1.5f), 0f, 0f, shadow)
        invalidate()
    }

    fun setState(geometry: GridGeometry, subGridCell: Pair<Int, Int>?) {
        this.geometry = geometry
        this.subGridCell = subGridCell
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val geo = geometry ?: return

        // the geometry is in screen coordinates; the overlay window may be inset, so translate
        // screen → view space using our on-screen origin (same trick as LabelOverlayView)
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        canvas.save()
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())

        val width = geo.screenWidth.toFloat()
        val height = geo.screenHeight.toFloat()

        for (col in 1 until geo.cols) {
            val x = col * geo.cellSize
            canvas.drawLine(x, 0f, x, height, linePaint)
        }
        for (row in 1 until geo.rows) {
            val y = row * geo.cellSize
            canvas.drawLine(0f, y, width, y, linePaint)
        }

        // column letters along the top, dipped below the status bar area
        val letterBaseline = dp(14f) - textPaint.fontMetrics.ascent
        for (col in 0 until geo.cols) {
            canvas.drawText(
                ('a' + col).toString(),
                (col + 0.5f) * geo.cellSize,
                letterBaseline,
                textPaint,
            )
        }
        // row numbers just inside the left edge, vertically centered in each row
        val textCenterOffset = (textPaint.fontMetrics.descent + textPaint.fontMetrics.ascent) / 2f
        for (row in 0 until geo.rows) {
            val rect = geo.cellRect(0, row)
            canvas.drawText(
                (row + 1).toString(),
                dp(10f),
                rect.centerY() - textCenterOffset,
                textPaint,
            )
        }

        subGridCell?.let { cell -> drawSubGrid(canvas, geo, cell) }

        canvas.restore()
    }

    private fun drawSubGrid(canvas: Canvas, geo: GridGeometry, cell: Pair<Int, Int>) {
        val rect = geo.cellRect(cell.first, cell.second)
        canvas.drawRect(rect, solidPaint)

        val divisions = GridGeometry.SUB_DIVISIONS
        for (i in 1 until divisions) {
            val x = rect.left + rect.width() * i / divisions
            canvas.drawLine(x, rect.top, x, rect.bottom, linePaint)
            val y = rect.top + rect.height() * i / divisions
            canvas.drawLine(rect.left, y, rect.right, y, linePaint)
        }

        // small a–c headers along the top of the sub-columns, 1–3 along the left of the sub-rows
        val letterBaseline = rect.top + dp(2f) - subTextPaint.fontMetrics.ascent
        val subCellWidth = rect.width() / divisions
        for (subCol in 0 until divisions) {
            canvas.drawText(
                ('a' + subCol).toString(),
                rect.left + (subCol + 0.5f) * subCellWidth,
                letterBaseline,
                subTextPaint,
            )
        }
        val textCenterOffset = (subTextPaint.fontMetrics.descent + subTextPaint.fontMetrics.ascent) / 2f
        val subCellHeight = rect.height() / divisions
        for (subRow in 0 until divisions) {
            canvas.drawText(
                (subRow + 1).toString(),
                rect.left + dp(6f),
                rect.top + (subRow + 0.5f) * subCellHeight - textCenterOffset,
                subTextPaint,
            )
        }
    }

    companion object {
        const val DEFAULT_OPACITY_PERCENT = 70
        private const val GREY_TONE = 0xCC
    }
}
