package org.stypox.dicio.voiceaccess

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The AccessibilityService that powers the open-source Voice Access mode. It is the only component
 * that can perform a global "back", enumerate clickable nodes, draw overlays over other apps, and
 * click a node by its number. Skills reach it through the process-wide [instance] singleton.
 */
class VoiceAccessService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null

    private var labelOverlay: LabelOverlayView? = null
    private var listeningBar: ListeningBarView? = null

    @Volatile
    private var labelsVisible = false
    private var labeledNodes: List<LabeledNode> = emptyList()

    /**
     * Set by [org.stypox.dicio.io.wake.WakeService] when it starts an STT session, so that the
     * "stop" command can stop listening without the skill needing a reference to the STT device.
     */
    @Volatile
    var stopListeningCallback: (() -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        instance = this
        Log.d(TAG, "VoiceAccessService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!labelsVisible || event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleLabelRefresh()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        removeListeningBar()
        removeLabelOverlay()
        stopListeningCallback = null
    }

    // ---------------------------------------------------------------- global actions

    fun goBack() = runOnMain { performGlobalAction(GLOBAL_ACTION_BACK) }

    fun goHome() = runOnMain { performGlobalAction(GLOBAL_ACTION_HOME) }

    // ---------------------------------------------------------------- scrolling & swiping

    /**
     * Scrolls the most relevant scrollable element so that content moves in [direction] (e.g.
     * [SwipeDirection.DOWN] reveals content further down). Falls back to a swipe gesture if no
     * scrollable node is found.
     */
    fun scroll(direction: SwipeDirection) = runOnMain {
        val node = findScrollable()
        if (node == null || !performScroll(node, direction)) {
            // no scrollable node: emulate by swiping the finger the opposite way
            dispatchSwipe(direction.fingerForContentScroll())
        }
    }

    /** Performs a raw swipe gesture in the given finger-movement [direction]. */
    fun swipe(direction: SwipeDirection) = runOnMain { dispatchSwipe(direction) }

    private fun findScrollable(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        windows.forEach { it.root?.let(stack::addLast) }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.isVisibleToUser && node.isScrollable) {
                val b = android.graphics.Rect()
                node.getBoundsInScreen(b)
                val area = b.width() * b.height()
                if (area > bestArea) {
                    bestArea = area
                    best = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::addLast)
        }
        return best
    }

    @SuppressLint("NewApi") // directional scroll actions guarded by the SDK_INT check
    private fun performScroll(node: AccessibilityNodeInfo, direction: SwipeDirection): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val action = when (direction) {
                SwipeDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                SwipeDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                SwipeDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                SwipeDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            }
            if (node.actionList.contains(action) && node.performAction(action.id)) return true
        }
        // legacy fallback: forward = down/right, backward = up/left
        val legacy = when (direction) {
            SwipeDirection.DOWN, SwipeDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            SwipeDirection.UP, SwipeDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return node.performAction(legacy)
    }

    /** To move content in a direction, the finger swipes the opposite way. */
    private fun SwipeDirection.fingerForContentScroll(): SwipeDirection = when (this) {
        SwipeDirection.UP -> SwipeDirection.DOWN
        SwipeDirection.DOWN -> SwipeDirection.UP
        SwipeDirection.LEFT -> SwipeDirection.RIGHT
        SwipeDirection.RIGHT -> SwipeDirection.LEFT
    }

    @SuppressLint("NewApi") // dispatchGesture/GestureDescription guarded by the SDK_INT check
    private fun dispatchSwipe(direction: SwipeDirection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val dx = dm.widthPixels * 0.3f
        val dy = dm.heightPixels * 0.3f

        val path = android.graphics.Path()
        when (direction) {
            SwipeDirection.UP -> { path.moveTo(cx, cy + dy); path.lineTo(cx, cy - dy) }
            SwipeDirection.DOWN -> { path.moveTo(cx, cy - dy); path.lineTo(cx, cy + dy) }
            SwipeDirection.LEFT -> { path.moveTo(cx + dx, cy); path.lineTo(cx - dx, cy) }
            SwipeDirection.RIGHT -> { path.moveTo(cx - dx, cy); path.lineTo(cx + dx, cy) }
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS)
            )
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ---------------------------------------------------------------- numbered labels

    fun areLabelsVisible(): Boolean = labelsVisible

    fun showLabels() = runOnMain {
        labelsVisible = true
        refreshLabels()
    }

    fun hideLabels() = runOnMain {
        labelsVisible = false
        labeledNodes = emptyList()
        removeLabelOverlay()
    }

    fun toggleLabels() = runOnMain {
        if (labelsVisible) hideLabels() else showLabels()
    }

    /** @return the number of labels currently shown, useful for spoken feedback */
    fun labelCount(): Int = labeledNodes.size

    private val refreshRunnable = Runnable { if (labelsVisible) refreshLabels() }

    private fun scheduleLabelRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, LABEL_REFRESH_DEBOUNCE_MS)
    }

    private fun refreshLabels() {
        val windowList = windows
        labeledNodes = try {
            ClickableNodeScanner.scan(windowList)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to scan clickable nodes", t)
            emptyList()
        }

        val wm = windowManager ?: return
        if (labelOverlay == null) {
            val view = LabelOverlayView(this)
            try {
                wm.addView(view, labelOverlayParams())
                labelOverlay = view
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to add label overlay", t)
                return
            }
        }
        labelOverlay?.setLabels(labeledNodes)
    }

    /**
     * Clicks the element labelled [number]. Falls back to a clickable ancestor, then to a tap
     * gesture at the element's center if the node itself does not accept ACTION_CLICK.
     *
     * @return true if a label with that number existed and a click was dispatched
     */
    fun clickLabel(number: Int): Boolean {
        val target = labeledNodes.firstOrNull { it.number == number } ?: return false
        runOnMain { performClick(target) }
        return true
    }

    private fun performClick(label: LabeledNode) {
        var node: AccessibilityNodeInfo? = label.node
        while (node != null) {
            if (node.isClickable &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return
            }
            node = node.parent
        }
        // last resort: tap at the center of the element's bounds
        dispatchTap(label)
    }

    @SuppressLint("NewApi") // guarded by the SDK_INT check below
    private fun dispatchTap(label: LabeledNode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = android.graphics.Path().apply {
            moveTo(label.bounds.exactCenterX(), label.bounds.exactCenterY())
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ---------------------------------------------------------------- listening bar

    fun showListening() = runOnMain {
        val wm = windowManager ?: return@runOnMain
        if (listeningBar == null) {
            val view = ListeningBarView(this)
            try {
                wm.addView(view, listeningBarParams())
                listeningBar = view
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to add listening bar", t)
            }
        }
        listeningBar?.setTranscript("", isFinal = false)
    }

    fun updateTranscript(text: String, isFinal: Boolean) = runOnMain {
        listeningBar?.setTranscript(text, isFinal)
    }

    fun hideListening() = runOnMain { removeListeningBar() }

    /** Ends the whole session: stops STT, removes the listening bar and any labels. */
    fun stopVoiceSession() {
        stopListeningCallback?.invoke()
        hideListening()
        hideLabels()
    }

    // ---------------------------------------------------------------- overlay plumbing

    private fun removeLabelOverlay() {
        labelOverlay?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to remove label overlay", t)
            }
        }
        labelOverlay = null
    }

    private fun removeListeningBar() {
        listeningBar?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to remove listening bar", t)
            }
        }
        listeningBar = null
    }

    private fun labelOverlayParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
    }

    private fun listeningBarParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * resources.displayMetrics.density).toInt()
        }
    }

    @SuppressLint("NewApi") // TYPE_ACCESSIBILITY_OVERLAY is guarded by the SDK_INT check
    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            handler.post { block() }
        }
    }

    companion object {
        private val TAG = VoiceAccessService::class.simpleName
        private const val LABEL_REFRESH_DEBOUNCE_MS = 250L
        private const val SWIPE_DURATION_MS = 250L

        @Volatile
        var instance: VoiceAccessService? = null
            private set

        /** Whether the accessibility service is currently connected and ready to take commands. */
        fun isRunning(): Boolean = instance != null

        /** Intent to send the user to system settings to enable the accessibility service. */
        fun accessibilitySettingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
