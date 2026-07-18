package org.stypox.dicio.voiceaccess

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.ceil

/**
 * Pure math for the chess-notation grid overlay: [COLS] square columns spanning the screen width,
 * with as many rows as needed to cover the screen height (the bottom row may be clamped shorter).
 * All coordinates are in screen space, the same space used by tap gestures, so a cell center can be
 * fed directly into a dispatched tap.
 */
class GridGeometry(val screenWidth: Int, val screenHeight: Int) {

    val cols = COLS
    val cellSize = screenWidth / COLS.toFloat()
    val rows = ceil(screenHeight / cellSize).toInt()

    /** Rect of the cell at 0-based ([col], [row]); the bottom row is clamped to the screen. */
    fun cellRect(col: Int, row: Int): RectF = RectF(
        col * cellSize,
        row * cellSize,
        (col + 1) * cellSize,
        ((row + 1) * cellSize).coerceAtMost(screenHeight.toFloat()),
    )

    fun cellCenter(col: Int, row: Int): PointF =
        cellRect(col, row).let { PointF(it.centerX(), it.centerY()) }

    /** Rect of the 0-based ([subCol], [subRow]) part of [cell]'s 3×3 refinement split. */
    fun subCellRect(cell: Pair<Int, Int>, subCol: Int, subRow: Int): RectF {
        val rect = cellRect(cell.first, cell.second)
        val width = rect.width() / SUB_DIVISIONS
        val height = rect.height() / SUB_DIVISIONS
        return RectF(
            rect.left + subCol * width,
            rect.top + subRow * height,
            rect.left + (subCol + 1) * width,
            rect.top + (subRow + 1) * height,
        )
    }

    fun subCellCenter(cell: Pair<Int, Int>, subCol: Int, subRow: Int): PointF =
        subCellRect(cell, subCol, subRow).let { PointF(it.centerX(), it.centerY()) }

    companion object {
        const val COLS = 8
        const val SUB_DIVISIONS = 3
    }
}
