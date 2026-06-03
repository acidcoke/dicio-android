package org.stypox.dicio.voiceaccess

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * A clickable element discovered in the current UI, together with the number shown to the user.
 */
data class LabeledNode(
    val number: Int,
    val node: AccessibilityNodeInfo,
    val bounds: Rect,
)

/**
 * Walks the accessibility node tree of all interactive windows and collects the elements that the
 * user can act on, assigning each a sequential number (top-to-bottom, then left-to-right) just like
 * Google Voice Access. The numbering is what [VoiceAccessService.clickLabel] later resolves.
 */
object ClickableNodeScanner {

    fun scan(windows: List<AccessibilityWindowInfo>): List<LabeledNode> {
        val collected = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val seenBounds = HashSet<String>()

        for (window in windows) {
            val root = window.root ?: continue
            collectFrom(root, collected, seenBounds)
        }

        // order top-to-bottom, then left-to-right, so numbers read naturally
        collected.sortWith(compareBy({ it.second.top }, { it.second.left }))

        return collected.mapIndexed { index, (node, bounds) ->
            LabeledNode(number = index + 1, node = node, bounds = bounds)
        }
    }

    private fun collectFrom(
        node: AccessibilityNodeInfo,
        out: MutableList<Pair<AccessibilityNodeInfo, Rect>>,
        seenBounds: MutableSet<String>,
    ) {
        if (!node.isVisibleToUser) {
            // still descend into invisible containers, their children might be visible
            forEachChild(node) { collectFrom(it, out, seenBounds) }
            return
        }

        if (isActionable(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                // dedup overlapping items (e.g. a clickable row that also reports a clickable child
                // at the same bounds) so we don't draw two labels in the same spot
                val key = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                if (seenBounds.add(key)) {
                    out.add(Pair(node, bounds))
                }
            }
        }

        forEachChild(node) { collectFrom(it, out, seenBounds) }
    }

    private fun isActionable(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        return node.isClickable || node.isLongClickable ||
            (node.isFocusable && node.isScreenReaderFocusable)
    }

    private inline fun forEachChild(
        node: AccessibilityNodeInfo,
        action: (AccessibilityNodeInfo) -> Unit,
    ) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            action(child)
        }
    }
}
