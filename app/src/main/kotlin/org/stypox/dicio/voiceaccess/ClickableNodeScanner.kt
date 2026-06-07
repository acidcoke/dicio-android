package org.stypox.dicio.voiceaccess

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * A clickable element discovered in the current UI, together with the text shown to the user.
 * [text] defaults to the [number], but PIN mode uses a phonetic word / "delete" / "enter" instead.
 */
data class LabeledNode(
    val number: Int,
    val node: AccessibilityNodeInfo,
    val bounds: Rect,
    val text: String = number.toString(),
)

/** A single key of a detected numeric PIN pad. */
data class PinKeyNode(
    val node: AccessibilityNodeInfo,
    val bounds: Rect,
)

/**
 * A numeric PIN pad detected on screen: the digit keys (by their value) plus, when found, the
 * delete and enter keys. Only produced when a password field is also present, so the phone dialer
 * (no password field) is not mistaken for a PIN pad.
 */
data class PinPad(
    val digitNodes: Map<Int, PinKeyNode>,
    val deleteKey: PinKeyNode?,
    val enterKey: PinKeyNode?,
)

/** The result of a single traversal: the numbered labels and, if any, the detected PIN pad. */
data class ScanResult(
    val labels: List<LabeledNode>,
    val pinPad: PinPad?,
)

/**
 * Walks the accessibility node tree of all interactive windows and collects the elements that the
 * user can act on, assigning each a sequential number (top-to-bottom, then left-to-right) just like
 * Google Voice Access. In the same pass it detects a numeric PIN pad (digit keys + a password
 * field) so callers can switch to phonetic-word labels.
 */
object ClickableNodeScanner {

    private val DELETE_WORDS = setOf(
        "delete", "backspace", "clear", "löschen", "loeschen", "entfernen", "rücktaste", "ruecktaste",
    )
    private val ENTER_WORDS = setOf(
        "enter", "ok", "done", "submit", "confirm", "go", "next",
        "eingabe", "bestätigen", "bestaetigen", "fertig", "weiter",
    )
    private val DELETE_IDS = listOf("delete", "backspace")
    private val ENTER_IDS = listOf("enter", "done", "submit", "confirm")

    // require most of the ten digits before treating a keypad as a PIN pad
    private const val MIN_PIN_DIGITS = 8

    /** Mutable accumulator carried through the recursive traversal. */
    private class Accumulator {
        val labels = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val seenBounds = HashSet<String>()
        val digits = HashMap<Int, PinKeyNode>()
        var hasPasswordField = false
        var deleteKey: PinKeyNode? = null
        var enterKey: PinKeyNode? = null
    }

    fun scan(windows: List<AccessibilityWindowInfo>): ScanResult {
        val acc = Accumulator()
        for (window in windows) {
            val root = window.root ?: continue
            collectFrom(root, acc)
        }

        // order top-to-bottom, then left-to-right, so numbers read naturally
        acc.labels.sortWith(compareBy({ it.second.top }, { it.second.left }))
        val labels = acc.labels.mapIndexed { index, (node, bounds) ->
            LabeledNode(number = index + 1, node = node, bounds = bounds)
        }

        val pinPad = if (acc.hasPasswordField && acc.digits.size >= MIN_PIN_DIGITS) {
            PinPad(acc.digits, acc.deleteKey, acc.enterKey)
        } else {
            null
        }

        return ScanResult(labels, pinPad)
    }

    private fun collectFrom(node: AccessibilityNodeInfo, acc: Accumulator) {
        if (node.isPassword) acc.hasPasswordField = true

        if (!node.isVisibleToUser) {
            // still descend into invisible containers, their children might be visible
            forEachChild(node) { collectFrom(it, acc) }
            return
        }

        if (isActionable(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                // dedup overlapping items (e.g. a clickable row that also reports a clickable child
                // at the same bounds) so we don't draw two labels in the same spot
                val key = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                if (acc.seenBounds.add(key)) {
                    acc.labels.add(Pair(node, bounds))
                }
                accumulatePinKey(node, bounds, acc)
            }
        }

        forEachChild(node) { collectFrom(it, acc) }
    }

    /** Classifies an actionable node as a digit / delete / enter key for PIN detection. */
    private fun accumulatePinKey(node: AccessibilityNodeInfo, bounds: Rect, acc: Accumulator) {
        val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        val digit = label.singleOrNull()?.takeIf { it.isDigit() }?.digitToInt()
        if (digit != null) {
            if (!acc.digits.containsKey(digit)) acc.digits[digit] = PinKeyNode(node, bounds)
            return
        }
        val low = label.lowercase()
        val resId = node.viewIdResourceName?.lowercase().orEmpty()
        if (acc.deleteKey == null && (low in DELETE_WORDS || DELETE_IDS.any { resId.contains(it) })) {
            acc.deleteKey = PinKeyNode(node, bounds)
        } else if (acc.enterKey == null && (low in ENTER_WORDS || ENTER_IDS.any { resId.contains(it) })) {
            acc.enterKey = PinKeyNode(node, bounds)
        }
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
