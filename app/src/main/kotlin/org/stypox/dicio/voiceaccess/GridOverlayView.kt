package org.stypox.dicio.voiceaccess

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets

/**
 * Full-screen, non-interactive overlay that paints the chess-notation grid: dotted light-grey cell
 * lines, column letters (a–h) along the top and row numbers along the left edge. When a cell has
 * been selected for refinement, a solid border plus a dashed 3×3 sub-grid with its own small a–c /
 * 1–3 headers is drawn inside it. Every line and glyph is drawn twice — a thicker dark pass under
 * the light one — so the grid stays readable on both light and dark app backgrounds. Like
 * [LabelOverlayView], the window never receives touches.
 */
class GridOverlayView(context: Context) : View(context) {

    private var geometry: GridGeometry? = null
    private var subGridCell: Pair<Int, Int>? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private fun sp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics
    )

    private val dashEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = dashEffect
    }
    // dark underlay drawn beneath each dashed line so it stays visible on light backgrounds
    private val lineHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        pathEffect = dashEffect
    }
    // solid border around the cell being refined, slightly thicker so it stands out
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val solidHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(14f)
        isFakeBoldText = true
    }
    private val textHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(14f)
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        isFakeBoldText = true
    }
    private val subTextHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    init {
        applyOpacity(DEFAULT_OPACITY_PERCENT / 100f)
    }

    /** Recomputes the line/text alpha from the user-configured opacity (0..1). */
    fun applyOpacity(opacity: Float) {
        val alpha = (opacity.coerceIn(0f, 1f) * 255f).toInt()
        val grey = Color.argb(alpha, GREY_TONE, GREY_TONE, GREY_TONE)
        val dark = Color.argb(alpha, DARK_TONE, DARK_TONE, DARK_TONE)
        linePaint.color = grey
        solidPaint.color = grey
        textPaint.color = grey
        subTextPaint.color = grey
        lineHaloPaint.color = dark
        solidHaloPaint.color = dark
        textHaloPaint.color = dark
        subTextHaloPaint.color = dark
        invalidate()
    }

    fun setState(geometry: GridGeometry, subGridCell: Pair<Int, Int>?) {
        this.geometry = geometry
        this.subGridCell = subGridCell
        invalidate()
    }

    private fun Canvas.drawHaloLine(startX: Float, startY: Float, stopX: Float, stopY: Float) {
        drawLine(startX, startY, stopX, stopY, lineHaloPaint)
        drawLine(startX, startY, stopX, stopY, linePaint)
    }

    private fun Canvas.drawHaloText(text: String, x: Float, y: Float, small: Boolean) {
        drawText(text, x, y, if (small) subTextHaloPaint else textHaloPaint)
        drawText(text, x, y, if (small) subTextPaint else textPaint)
    }

    /** Top inset of the status bar in screen coordinates, so the column letters sit below it. */
    private fun statusBarInset(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top
                ?.let { return it.toFloat() }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            rootWindowInsets?.systemWindowInsetTop?.let { return it.toFloat() }
        }
        return dp(FALLBACK_STATUS_BAR_DP)
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
            canvas.drawHaloLine(x, 0f, x, height)
        }
        for (row in 1 until geo.rows) {
            val y = row * geo.cellSize
            canvas.drawHaloLine(0f, y, width, y)
        }

        // column letters along the top, below the status bar so they aren't covered by it
        val letterBaseline = statusBarInset() + dp(4f) - textPaint.fontMetrics.ascent
        for (col in 0 until geo.cols) {
            canvas.drawHaloText(
                ('a' + col).toString(),
                (col + 0.5f) * geo.cellSize,
                letterBaseline,
                small = false,
            )
        }
        // row numbers just inside the left edge, vertically centered in each row
        val textCenterOffset = (textPaint.fontMetrics.descent + textPaint.fontMetrics.ascent) / 2f
        for (row in 0 until geo.rows) {
            val rect = geo.cellRect(0, row)
            canvas.drawHaloText(
                (row + 1).toString(),
                dp(12f),
                rect.centerY() - textCenterOffset,
                small = false,
            )
        }

        subGridCell?.let { cell -> drawSubGrid(canvas, geo, cell) }

        canvas.restore()
    }

    private fun drawSubGrid(canvas: Canvas, geo: GridGeometry, cell: Pair<Int, Int>) {
        val rect = geo.cellRect(cell.first, cell.second)
        canvas.drawRect(rect, solidHaloPaint)
        canvas.drawRect(rect, solidPaint)

        val divisions = GridGeometry.SUB_DIVISIONS
        for (i in 1 until divisions) {
            val x = rect.left + rect.width() * i / divisions
            canvas.drawHaloLine(x, rect.top, x, rect.bottom)
            val y = rect.top + rect.height() * i / divisions
            canvas.drawHaloLine(rect.left, y, rect.right, y)
        }

        // small a–c headers along the top of the sub-columns, 1–3 along the left of the sub-rows
        val letterBaseline = rect.top + dp(2f) - subTextPaint.fontMetrics.ascent
        val subCellWidth = rect.width() / divisions
        for (subCol in 0 until divisions) {
            canvas.drawHaloText(
                ('a' + subCol).toString(),
                rect.left + (subCol + 0.5f) * subCellWidth,
                letterBaseline,
                small = true,
            )
        }
        val textCenterOffset = (subTextPaint.fontMetrics.descent + subTextPaint.fontMetrics.ascent) / 2f
        val subCellHeight = rect.height() / divisions
        for (subRow in 0 until divisions) {
            canvas.drawHaloText(
                (subRow + 1).toString(),
                rect.left + dp(7f),
                rect.top + (subRow + 0.5f) * subCellHeight - textCenterOffset,
                small = true,
            )
        }
    }

    companion object {
        const val DEFAULT_OPACITY_PERCENT = 70
        // brighter grey over a dark halo, so the grid reads on light and dark backgrounds alike
        private const val GREY_TONE = 0xE0
        private const val DARK_TONE = 0x20
        // used when the window insets are not available yet
        private const val FALLBACK_STATUS_BAR_DP = 28f
    }
}
