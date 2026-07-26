package org.stypox.dicio.voiceaccess

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.stypox.dicio.BuildConfig

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
    // how much two candidates must overlap (intersection over union) to count as the same element:
    // a clickable wrapper around a clickable icon clears this easily, while a genuinely separate
    // target inside a row (the call button of a chat entry) stays far below it
    private const val NEAR_DUPLICATE_IOU = 0.5f
    // a chip on something this large points at nothing in particular: Grayjay marks its whole
    // videodetail_root clickable, which puts a number over blank space at the top of the screen
    private const val MAX_LABEL_AREA_FRACTION = 0.6f
    // a full-width container with nothing in it is a row laid out before its content loaded (the
    // recommendation list of a Grayjay video), so its chip would float over an empty area
    private const val WIDE_CONTAINER_WIDTH_FRACTION = 0.6f
    // how deep to look for any text under a wide container before calling it empty
    private const val CONTENT_SEARCH_DEPTH = 4
    // a system window narrower than this much of the screen is something drawn over an app, not a
    // status/navigation bar, so it may hide what is below it
    private const val SYSTEM_OCCLUDER_MAX_WIDTH = 0.9f
    private const val LOG_TAG = "DicioLabels"

    /** Bounds of the labels logged last, so [logLabels] only prints when something changed. */
    private var lastLoggedSignature = ""

    /** Mutable accumulator carried through the recursive traversal. */
    private class Accumulator {
        val labels = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val seenBounds = HashSet<String>()
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
        // topmost window first, so every window is checked against the ones drawn above it
        val ordered = windows.sortedByDescending { it.layer }
        // bounds of the windows already visited, i.e. those drawn on top of whatever follows
        val occluders = ArrayList<Rect>()
        // the whole display, used to tell a system window drawn over an app (the picture-in-picture
        // controls) from one that spans the screen edge to edge (a status or navigation bar)
        val screen = Rect()
        for (window in windows) {
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) screen.union(bounds)
        }
        val windowNotes = if (BuildConfig.DEBUG) ArrayList<String>() else null
        for (window in ordered) {
            val isIme = window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            if (isIme) keyboardVisible = true
            val windowBounds = Rect()
            window.getBoundsInScreen(windowBounds)
            val root = window.root
            windowNotes?.add(
                "win=${window.id} type=${window.type} layer=${window.layer}" +
                    " ${windowBounds.toShortString()} occludes=${occludes(window, windowBounds, screen)}" +
                    " covered=${isCovered(windowBounds, occluders)}"
            )
            // a window fully hidden behind a higher one (the activity behind a dialog) holds nothing
            // the user can act on, even though its nodes still report isVisibleToUser
            if (root != null && !isCovered(windowBounds, occluders)) {
                collectFrom(root, acc, isIme, occluders, windowBounds)
            }
            if (!windowBounds.isEmpty && occludes(window, windowBounds, screen)) {
                occluders.add(windowBounds)
            }
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

        if (BuildConfig.DEBUG) logLabels(labels, windowNotes.orEmpty())
        return ScanResult(labels, pinPad, keyboardKeys)
    }

    /**
     * Dumps what a scan labeled, to logcat under `DicioLabels` and into [LabelDump], which writes it
     * to a file when the session ends. Debug builds only, and only when the set actually changed,
     * since scans run several times a second.
     */
    private fun logLabels(labels: List<LabeledNode>, windowNotes: List<String>) {
        val signature = labels.joinToString(";") { it.bounds.toShortString() }
        if (signature == lastLoggedSignature) return
        lastLoggedSignature = signature

        val lines = ArrayList<String>(labels.size + windowNotes.size + 1)
        lines.add("--- ${labels.size} labels")
        // the windows the labels came from, so a chip can be traced back to what was on top
        for (note in windowNotes) lines.add("  $note")
        for (label in labels) {
            val node = label.node
            lines.add(
                "${label.number}: ${label.bounds.toShortString()} pkg=${node.packageName}" +
                    " cls=${node.className} id=${node.viewIdResourceName} win=${node.windowId}" +
                    " click=${node.isClickable} vis=${node.isVisibleToUser}" +
                    " txt=${node.text?.take(30)} desc=${node.contentDescription?.take(30)}"
            )
        }
        // one entry per line, since logcat truncates a single long message
        for (line in lines) Log.d(LOG_TAG, line)
        LabelDump.append(lines.joinToString("\n", postfix = "\n"))
    }

    private fun collectFrom(
        node: AccessibilityNodeInfo,
        acc: Accumulator,
        isIme: Boolean,
        occluders: MutableList<Rect>,
        windowBounds: Rect,
    ) {
        if (node.isPassword) acc.hasPasswordField = true

        if (!node.isVisibleToUser) {
            // still descend into invisible containers, their children might be visible
            collectChildren(node, acc, isIme, occluders, windowBounds)
            return
        }

        if (isActionable(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            // isVisibleToUser only covers this node's own window, so it stays true for an element
            // sitting behind another window — e.g. a list item scrolled under the keyboard
            if (!bounds.isEmpty && !isCovered(bounds, occluders)) {
                // only genuinely clickable elements get a label. A merely focusable node (a chat
                // row's name or preview text) is not a target of its own: clicking it just walks up
                // to its clickable ancestor and does whatever that does.
                // The on-screen keyboard is the exception: Gboard exposes its keys as focus-only
                // virtual nodes, so requiring isClickable would leave every key but the special
                // ones (which come from KeyboardKeys) unlabeled. Those keys are tapped by gesture
                // anyway, via performClick's fallback.
                // the keyboard is exempt from the noise filters below: its keys were only just made
                // labelable again and nothing on a keyboard looks like a stray container
                if ((node.isClickable || isIme) &&
                    (isIme || !isNoiseLabel(node, bounds, windowBounds))
                ) {
                    val key = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                    // drop chips that would land on top of each other: the exact same rectangle, or
                    // a nested clickable covering essentially the same area (WhatsApp's chat-list
                    // avatar is a clickable ImageView inside a clickable wrapper)
                    if (acc.seenBounds.add(key) && indexOfNearDuplicate(acc.labels, bounds) < 0) {
                        acc.labels.add(Pair(node, bounds))
                    }
                }
                // PIN / keyboard detection still sees every actionable node, not just clickable
                // ones, since some keypads expose their keys as focus-only nodes
                accumulatePinKey(node, bounds, acc, isIme)
            }
        }

        collectChildren(node, acc, isIme, occluders, windowBounds)
    }

    /**
     * Whether labeling [node] would only add noise. Two shapes, both taken from a Grayjay dump: a
     * clickable container spanning most of [window], whose chip points at nothing in particular
     * (`videodetail_root` covers the entire screen), and a full-width container with no text
     * anywhere inside it, which is what a list row laid out before its content loaded looks like —
     * its chip ends up floating over a blank area.
     *
     * Both tests are about size, so a small icon button without a description is never affected.
     */
    private fun isNoiseLabel(node: AccessibilityNodeInfo, bounds: Rect, window: Rect): Boolean {
        if (window.isEmpty) return false
        val windowArea = window.width().toLong() * window.height()
        val area = bounds.width().toLong() * bounds.height()
        if (area >= windowArea * MAX_LABEL_AREA_FRACTION) return true
        val fullWidth = bounds.width() >= window.width() * WIDE_CONTAINER_WIDTH_FRACTION
        return fullWidth && !hasContentText(node, CONTENT_SEARCH_DEPTH)
    }

    /**
     * Whether [node] or a descendant at most [depth] levels down carries text or a content
     * description. Bounded so this stays cheap; when the text sits deeper the node keeps its label,
     * which is the conservative outcome.
     */
    private fun hasContentText(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) return true
        if (depth <= 0) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasContentText(child, depth - 1)) return true
        }
        return false
    }

    /**
     * Visits [node]'s children topmost first — later siblings are drawn over earlier ones — so a
     * panel and its scrim are already registered as occluders by the time the content they cover is
     * visited. Additions are undone on the way out, so they only apply to this subtree.
     */
    private fun collectChildren(
        node: AccessibilityNodeInfo,
        acc: Accumulator,
        isIme: Boolean,
        occluders: MutableList<Rect>,
        windowBounds: Rect,
    ) {
        val mark = occluders.size
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            val labelsBefore = acc.labels.size
            collectFrom(child, acc, isIme, occluders, windowBounds)
            addIfOccluding(child, acc.labels.size > labelsBefore, occluders)
        }
        while (occluders.size > mark) occluders.removeAt(occluders.size - 1)
    }

    /**
     * Registers [child] as hiding whatever is drawn below it, if it plausibly does. Two things
     * qualify: a clickable node, which is what a scrim or panel that swallows taps looks like, and a
     * node that carries content of its own ([hasContent], i.e. its subtree produced labels), which
     * is what a screen stacked over another screen in the same window looks like.
     *
     * An empty layer is deliberately not an occluder: a transparent wrapper spanning the screen
     * hides nothing, and treating it as an occluder would drop labels the user can still reach.
     * This never affects [child]'s own ancestors or descendants — only siblings drawn below it,
     * which is what draw order means here.
     */
    private fun addIfOccluding(
        child: AccessibilityNodeInfo,
        hasContent: Boolean,
        occluders: MutableList<Rect>,
    ) {
        if (!child.isVisibleToUser) return
        if (!child.isClickable && !hasContent) return
        val bounds = Rect()
        child.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) occluders.add(bounds)
    }

    /**
     * Whether [window] hides what is drawn below it. App and keyboard windows always do: those
     * swallow touches (a dialog over its activity, the keyboard over a list).
     *
     * A system window counts only when it does not span [screen] edge to edge. That narrow case is
     * the picture-in-picture controls, which sit exactly on top of the app's own PiP window and hide
     * its buttons. The wide ones are status and navigation bars and invisible gesture layers, which
     * must never hide the app behind them. Our own label overlay is an accessibility overlay and so
     * never qualifies, which matters because it is added with FLAG_NOT_TOUCHABLE over everything.
     */
    private fun occludes(window: AccessibilityWindowInfo, bounds: Rect, screen: Rect): Boolean =
        when (window.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION,
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> true
            AccessibilityWindowInfo.TYPE_SYSTEM ->
                !screen.isEmpty && bounds.width() < screen.width() * SYSTEM_OCCLUDER_MAX_WIDTH
            else -> false
        }

    /**
     * Whether [bounds] lies entirely inside one of [occluders]. Full containment on purpose: a
     * partly covered element is still partly reachable, and its chip is drawn at its top edge.
     */
    private fun isCovered(bounds: Rect, occluders: List<Rect>): Boolean =
        occluders.any { it.contains(bounds) }

    /** Area overlap of [a] and [b] as intersection over union, 0 when they do not intersect. */
    private fun overlapRatio(a: Rect, b: Rect): Float {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (width <= 0 || height <= 0) return 0f
        val intersection = width.toLong() * height.toLong()
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - intersection
        return if (union <= 0L) 0f else intersection.toFloat() / union
    }

    /**
     * Index of the already accepted label that covers essentially the same area as [bounds], or -1.
     * Uses area overlap rather than containment on purpose: a nested target that is much smaller
     * than its container (a call button in a chat row) is a real second target and must keep its
     * own label, while a wrapper and the icon inside it are the same element seen twice.
     */
    private fun indexOfNearDuplicate(
        labels: List<Pair<AccessibilityNodeInfo, Rect>>,
        bounds: Rect,
    ): Int = labels.indexOfFirst { overlapRatio(it.second, bounds) >= NEAR_DUPLICATE_IOU }

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
}
