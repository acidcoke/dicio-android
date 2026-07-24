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

/**
 * The enter/delete/shift/space keys of a generic on-screen keyboard (IME), found whenever an IME
 * window is showing — unlike [PinPad], this does not require a password field or an all-numeric
 * layout, so it also covers normal text entry.
 */
data class KeyboardKeys(
    val enterKey: PinKeyNode?,
    val deleteKey: PinKeyNode?,
    val shiftKey: PinKeyNode?,
    val spaceKey: PinKeyNode?,
)

/** The result of a single traversal: the numbered labels and, if any, the detected PIN pad /
 * generic keyboard keys. */
data class ScanResult(
    val labels: List<LabeledNode>,
    val pinPad: PinPad?,
    val keyboardKeys: KeyboardKeys?,
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
        "enter", "ok", "done", "submit", "confirm", "go", "next", "search", "send",
        "eingabe", "bestätigen", "bestaetigen", "fertig", "weiter",
        // Gboard's enter key is named after the current IME action
        "los", "suchen", "senden", "eingabetaste",
    )
    // matched by substring, not equality: Gboard renames the key with its state ("Großschreibetaste"
    // → "Umschalttaste-Taste" once shifted, presumably more variants for caps lock)
    private val SHIFT_STEMS = listOf(
        "shift", "caps", "umschalt", "großschreib", "grossschreib",
    )
    private val SPACE_WORDS = setOf(
        "space", "space bar", "spacebar", "leertaste", "leerzeichen",
    )
    private val DELETE_IDS = listOf("delete", "backspace")
    private val ENTER_IDS = listOf("enter", "done", "submit", "confirm")
    private val SHIFT_IDS = listOf("shift")
    private val SPACE_IDS = listOf("space", "spacebar")

    // require most of the ten digits before treating a keypad as a PIN pad
    private const val MIN_PIN_DIGITS = 8
    // how deep under an actionable key to look for the digit text (e.g. key View → child TextView)
    private const val DIGIT_SEARCH_DEPTH = 2

    /** Mutable accumulator carried through the recursive traversal. */
    private class Accumulator {
        val labels = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val seenBounds = HashSet<String>()
        // nodes performClick would actually end up clicking, so we only label each target once
        val seenClickTargets = HashSet<AccessibilityNodeInfo>()
        val digits = HashMap<Int, PinKeyNode>()
        var hasPasswordField = false
        var deleteKey: PinKeyNode? = null
        var enterKey: PinKeyNode? = null
        // digit / single-letter keys found inside the on-screen keyboard (IME) window, used to
        // recognize a numeric-only keypad (a PIN entry whose password field lives in a SECURE
        // window we cannot read, e.g. banking photoTAN apps)
        val imeDigits = HashSet<Int>()
        var imeLetterKeys = 0
        // enter/delete/shift/space keys found specifically inside an IME window, exposed whenever a
        // keyboard is on screen (unlike deleteKey/enterKey above, which feed the PIN-pad-only path)
        var imeEnterKey: PinKeyNode? = null
        var imeDeleteKey: PinKeyNode? = null
        var imeShiftKey: PinKeyNode? = null
        var imeSpaceKey: PinKeyNode? = null
    }

    fun scan(windows: List<AccessibilityWindowInfo>): ScanResult {
        val acc = Accumulator()
        var keyboardVisible = false
        for (window in windows) {
            val isIme = window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            if (isIme) keyboardVisible = true
            val root = window.root ?: continue
            collectFrom(root, acc, isIme)
        }
        // a numeric-only on-screen keyboard (digits, no letters) signals a PIN/number entry even
        // when no readable password field exists (the field may be in a SECURE window)
        val numericImeKeypad = acc.imeDigits.size >= MIN_PIN_DIGITS && acc.imeLetterKeys == 0

        // order top-to-bottom, then left-to-right, so numbers read naturally
        acc.labels.sortWith(compareBy({ it.second.top }, { it.second.left }))
        val labels = acc.labels.mapIndexed { index, (node, bounds) ->
            LabeledNode(number = index + 1, node = node, bounds = bounds)
        }

        val pinTrigger = (acc.hasPasswordField || numericImeKeypad) && acc.digits.size >= MIN_PIN_DIGITS
        val pinPad = if (pinTrigger) {
            PinPad(acc.digits, acc.deleteKey, acc.enterKey)
        } else {
            null
        }

        val keyboardKeys = if (keyboardVisible) {
            KeyboardKeys(acc.imeEnterKey, acc.imeDeleteKey, acc.imeShiftKey, acc.imeSpaceKey)
        } else {
            null
        }

        return ScanResult(labels, pinPad, keyboardKeys)
    }

    private fun collectFrom(node: AccessibilityNodeInfo, acc: Accumulator, isIme: Boolean) {
        if (node.isPassword) acc.hasPasswordField = true

        if (!node.isVisibleToUser) {
            // still descend into invisible containers, their children might be visible
            forEachChild(node) { collectFrom(it, acc, isIme) }
            return
        }

        if (isActionable(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                // one label per click target: a clickable row and its merely screen-reader-focusable
                // children (name, preview text, avatar) all trigger the same action, so a second
                // label there is just noise. Pre-order means the outer node is seen first, putting
                // the surviving label on the row itself, which is where the tap lands anyway.
                val target = clickTargetOf(node)
                val newTarget = target == null || acc.seenClickTargets.add(target)
                // dedup overlapping items (e.g. a clickable row that also reports a clickable child
                // at the same bounds) so we don't draw two labels in the same spot
                val key = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                val newBounds = acc.seenBounds.add(key)
                if (newTarget && newBounds) {
                    acc.labels.add(Pair(node, bounds))
                }
                accumulatePinKey(node, bounds, acc, isIme)
            }
        }

        forEachChild(node) { collectFrom(it, acc, isIme) }
    }

    /**
     * The node `VoiceAccessService.performClick` would click for [node]: the first clickable node
     * from [node] upwards. Null when there is none (performClick falls back to tapping the bounds
     * there) or when the search runs into a scrollable container, since a scrollable ancestor is
     * practically never the intended target and merging across one would collapse a whole list into
     * a single label.
     */
    private fun clickTargetOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            if (current.isScrollable) return null
            current = current.parent
        }
        return null
    }

    /** Classifies an actionable node as a digit / delete / enter key for PIN detection. */
    private fun accumulatePinKey(node: AccessibilityNodeInfo, bounds: Rect, acc: Accumulator, isIme: Boolean) {
        val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        // many keypads (e.g. C24) put the digit on a non-clickable child TextView while the
        // clickable key itself has empty text, so fall back to scanning descendants for the digit
        val digit = singleDigit(label) ?: findDigitInDescendants(node, DIGIT_SEARCH_DEPTH)
        if (digit != null) {
            if (!acc.digits.containsKey(digit)) acc.digits[digit] = PinKeyNode(node, bounds)
            if (isIme) acc.imeDigits.add(digit)
            return
        }
        // a single-letter key in the keyboard means it is a text (QWERTY) layout, not a numeric pad
        if (isIme && label.length == 1 && label[0].isLetter()) acc.imeLetterKeys++
        val low = label.lowercase()
        val resId = node.viewIdResourceName?.lowercase().orEmpty()
        if (acc.deleteKey == null && (low in DELETE_WORDS || DELETE_IDS.any { resId.contains(it) })) {
            acc.deleteKey = PinKeyNode(node, bounds)
        } else if (acc.enterKey == null && (low in ENTER_WORDS || ENTER_IDS.any { resId.contains(it) })) {
            acc.enterKey = PinKeyNode(node, bounds)
        }
        if (isIme) accumulateKeyboardKey(low, resId, node, bounds, acc)
    }

    /** Classifies an actionable IME-window node as the enter/delete/shift/space key of a generic
     * on-screen keyboard, independent of PIN-pad detection. */
    private fun accumulateKeyboardKey(
        low: String,
        resId: String,
        node: AccessibilityNodeInfo,
        bounds: Rect,
        acc: Accumulator,
    ) {
        if (acc.imeDeleteKey == null && (low in DELETE_WORDS || DELETE_IDS.any { resId.contains(it) })) {
            acc.imeDeleteKey = PinKeyNode(node, bounds)
        } else if (acc.imeEnterKey == null && (low in ENTER_WORDS || ENTER_IDS.any { resId.contains(it) })) {
            acc.imeEnterKey = PinKeyNode(node, bounds)
        } else if (acc.imeShiftKey == null && (SHIFT_STEMS.any { low.contains(it) } || SHIFT_IDS.any { resId.contains(it) })) {
            acc.imeShiftKey = PinKeyNode(node, bounds)
        } else if (acc.imeSpaceKey == null && (low in SPACE_WORDS || SPACE_IDS.any { resId.contains(it) })) {
            acc.imeSpaceKey = PinKeyNode(node, bounds)
        }
    }

    private fun singleDigit(label: String): Int? =
        label.singleOrNull()?.takeIf { it.isDigit() }?.digitToInt()

    /**
     * Looks for a single-digit label on [node]'s descendants, up to [depth] levels deep. Bounded so
     * a label-less actionable node on a non-PIN screen doesn't trigger a full subtree walk.
     */
    private fun findDigitInDescendants(node: AccessibilityNodeInfo, depth: Int): Int? {
        if (depth <= 0) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val label = (child.text ?: child.contentDescription)?.toString()?.trim().orEmpty()
            singleDigit(label)?.let { return it }
            findDigitInDescendants(child, depth - 1)?.let { return it }
        }
        return null
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
