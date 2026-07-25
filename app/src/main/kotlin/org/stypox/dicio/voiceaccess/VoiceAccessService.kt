package org.stypox.dicio.voiceaccess

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.datastore.core.DataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.dicio.R
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.di.LocaleManagerModule
import org.dicio.skill.skill.SkillGrammar
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.eval.SkillHandler
import org.stypox.dicio.settings.datastore.UserSettings
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The AccessibilityService that powers the open-source Voice Access mode. It is the only component
 * that can perform a global "back", enumerate clickable nodes, draw overlays over other apps, and
 * click a node by its number. Skills reach it through the process-wide [instance] singleton.
 */
class VoiceAccessService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null

    private var labelOverlay: LabelOverlayView? = null
    private var gridOverlay: GridOverlayView? = null
    private var listeningBar: ListeningBarView? = null
    private var confirmationOverlay: ConfirmationOverlayView? = null

    // whether the user wants labels shown; remembered across listening sessions
    @Volatile
    private var labelsVisible = false
    // whether the user wants the coordinate grid shown; remembered across listening sessions and
    // mutually exclusive with the numbered labels
    @Volatile
    private var gridVisible = false
    // the cell currently split into a 3×3 refinement sub-grid, or null; session-scoped
    @Volatile
    private var subGridCell: Pair<Int, Int>? = null
    // user-configured grid line/text opacity (0..1), kept in sync with the settings data store
    @Volatile
    private var gridOpacity = GridOverlayView.DEFAULT_OPACITY_PERCENT / 100f
    // whether a listening session is currently active (the overlay is only drawn while it is)
    @Volatile
    private var sessionActive = false
    private var labeledNodes: List<LabeledNode> = emptyList()

    // ---- PIN mode: phonetic-word labels over a numeric PIN pad ----
    // whether a numeric PIN pad is currently on screen (labels become phonetic words, not numbers)
    @Volatile
    private var pinModeActive = false
    // the stable digit→slot shuffle, computed once each time a pad appears (slot = phonetic word index)
    private var pinDigitToSlot: Map<Int, Int>? = null
    // resolved per scan: slot index / delete / enter → the node to click
    private val pinSlotToNode = HashMap<Int, LabeledNode>()
    private var pinDeleteNode: LabeledNode? = null
    private var pinEnterNode: LabeledNode? = null

    // ---- generic keyboard mode: enter/delete/shift/space labels over any on-screen keyboard ----
    // resolved per scan: only populated while labelsVisible, and never alongside PIN mode (which
    // already exposes its own delete/enter through pinDeleteNode/pinEnterNode above)
    private var keyboardEnterNode: LabeledNode? = null
    private var keyboardDeleteNode: LabeledNode? = null
    private var keyboardShiftNode: LabeledNode? = null
    private var keyboardSpaceNode: LabeledNode? = null

    // Resources forced to the app/Vosk locale (LocaleManager), NOT the service's system locale:
    // the PIN labels and the recognition grammar must match the language of the loaded Vosk model,
    // otherwise the words drawn on screen are out-of-grammar and nothing is recognized.
    // null until the service is connected and the locale is known; falls back to the service's own
    // resources. Must NOT be initialized from `resources` in the constructor, where the base context
    // is not yet attached (getResources() would NPE).
    @Volatile
    private var localizedResources: Resources? = null
    private val localeResources: Resources get() = localizedResources ?: resources

    // a Context pinned to the app/Vosk locale, used to inflate overlay views (the listening bar,
    // confirmation dialog) so their strings match the app language and not the system locale
    @Volatile
    private var localizedContext: Context? = null
    private val localeContext: Context get() = localizedContext ?: this

    // phonetic words (slot order) and delete/enter captions, in the app/Vosk locale
    private val pinWords: Array<String> get() = localeResources.getStringArray(R.array.va_pin_words)
    private val pinDeleteLabel: String get() = localeResources.getString(R.string.va_pin_delete)
    private val pinEnterLabel: String get() = localeResources.getString(R.string.va_pin_enter)
    private val keyboardShiftLabel: String get() = localeResources.getString(R.string.va_keyboard_shift)
    private val keyboardSpaceLabel: String get() = localeResources.getString(R.string.va_keyboard_space)

    // the merged grammar of the currently enabled skills, kept in sync with SkillHandler: every
    // word they can understand, plus the words after which dictation takes over
    @Volatile
    private var skillGrammar: SkillGrammar = SkillGrammar.EMPTY

    private val localeManager: LocaleManager by lazy {
        EntryPointAccessors
            .fromApplication(applicationContext, LocaleManagerModule::class.java)
            .getLocaleManager()
    }
    // the STT device, reached the same way as the data store since this is a non-Hilt service
    private val sttInputDevice: SttInputDeviceWrapper by lazy {
        EntryPointAccessors
            .fromApplication(applicationContext, VoiceAccessEntryPoint::class.java)
            .sttInputDeviceWrapper()
    }
    private val skillHandler: SkillHandler by lazy {
        EntryPointAccessors
            .fromApplication(applicationContext, VoiceAccessEntryPoint::class.java)
            .skillHandler()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Current user-configured label appearance, kept in sync with the settings data store. */
    @Volatile
    private var labelStyle: LabelStyle = LabelStyle.DEFAULT

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
        collectLabelStyle()
        collectLocale()
        collectSkillGrammar()
        Log.d(TAG, "VoiceAccessService connected")
    }

    /** Keeps [localizedResources] pinned to the app/Vosk language, so PIN labels and the grammar
     * always match the loaded Vosk model rather than the device's system locale. */
    private fun collectLocale() {
        scope.launch {
            localeManager.locale.collect { locale ->
                val ctx = contextForLocale(locale)
                localizedContext = ctx
                localizedResources = ctx.resources
            }
        }
    }

    /**
     * Keeps [skillGrammar] in sync with the enabled skills, so that enabling or disabling a skill in
     * the settings takes effect immediately, even in the middle of a listening session.
     */
    private fun collectSkillGrammar() {
        scope.launch {
            skillHandler.skillGrammar.collect { grammar ->
                skillGrammar = grammar
                if (sessionActive) {
                    applyRecognitionGrammar()
                }
            }
        }
    }

    private fun contextForLocale(locale: Locale): Context {
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        return createConfigurationContext(config)
    }

    private fun collectLabelStyle() {
        val dataStore = EntryPointAccessors
            .fromApplication(applicationContext, VoiceAccessEntryPoint::class.java)
            .dataStore()
        scope.launch {
            dataStore.data
                .map { LabelStyle.from(it.labelOpacity, it.labelContrast, it.labelTheme) }
                .distinctUntilChanged()
                .collect { style ->
                    labelStyle = style
                    labelOverlay?.applyStyle(style)
                }
        }
        scope.launch {
            dataStore.data
                .map { it.gridOpacity.takeIf { pct -> pct != 0 } ?: GridOverlayView.DEFAULT_OPACITY_PERCENT }
                .distinctUntilChanged()
                .collect { percent ->
                    gridOpacity = percent / 100f
                    gridOverlay?.applyOpacity(gridOpacity)
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // refresh whenever a session is active: even with numbered labels off we must keep scanning
        // so a PIN pad that appears mid-session is detected and switched to phonetic labels
        if (!sessionActive || event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleLabelRefresh()
            // fires as a mouse pointer enters a view, independent of touch exploration; doesn't
            // consume the underlying input, unlike requesting raw motion events would
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> event.source?.let { lastHoveredNode = it }
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
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        removeListeningBar()
        removeConfirmationOverlay()
        removeLabelOverlay()
        removeGridOverlay()
        stopListeningCallback = null
    }

    // ---------------------------------------------------------------- global actions

    fun goBack() = runOnMain { performGlobalAction(GLOBAL_ACTION_BACK) }

    fun goHome() = runOnMain { performGlobalAction(GLOBAL_ACTION_HOME) }

    fun openNotifications() = runOnMain { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }

    fun openQuickSettings() = runOnMain { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }

    // ---------------------------------------------------------------- scrolling & swiping

    /**
     * Scrolls so that content moves in [direction] (e.g. [SwipeDirection.DOWN] reveals content
     * further down), by swiping the finger the opposite way. [fraction] is the swipe distance as a
     * portion of the screen (0..1); larger means a bigger scroll.
     */
    fun scroll(direction: SwipeDirection, fraction: Float = DEFAULT_SWIPE_FRACTION) =
        runOnMain { dispatchSwipe(direction.fingerForContentScroll(), fraction) }

    /** Performs a raw swipe gesture in the given finger-movement [direction]. */
    fun swipe(direction: SwipeDirection, fraction: Float = DEFAULT_SWIPE_FRACTION) =
        runOnMain { dispatchSwipe(direction, fraction) }

    /** To move content in a direction, the finger swipes the opposite way. */
    private fun SwipeDirection.fingerForContentScroll(): SwipeDirection = when (this) {
        SwipeDirection.UP -> SwipeDirection.DOWN
        SwipeDirection.DOWN -> SwipeDirection.UP
        SwipeDirection.LEFT -> SwipeDirection.RIGHT
        SwipeDirection.RIGHT -> SwipeDirection.LEFT
    }

    @SuppressLint("NewApi") // dispatchGesture/GestureDescription guarded by the SDK_INT check
    private fun dispatchSwipe(direction: SwipeDirection, fraction: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        // distance the finger travels, half on each side of the screen center
        val dx = dm.widthPixels * fraction / 2f
        val dy = dm.heightPixels * fraction / 2f

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

    // ---------------------------------------------------------------- zooming

    /** Zooms in ([zoomIn] true) or out at the screen center with a two-finger pinch gesture. */
    fun zoom(zoomIn: Boolean) = runOnMain {
        val dm = resources.displayMetrics
        dispatchThroughGrid { dispatchPinch(dm.widthPixels / 2f, dm.heightPixels / 2f, zoomIn) }
    }

    /**
     * Zooms in/out focused on the given grid cell (0-based [col], 1-based [row]). Returns false if
     * the cell is outside the current grid (mirroring [handleGridCell]'s range check), true once the
     * pinch is dispatched.
     */
    fun zoomAtCell(col: Int, row: Int, zoomIn: Boolean): Boolean {
        val geometry = currentGridGeometry()
        if (col >= geometry.cols || row < 1 || row > geometry.rows) {
            return false
        }
        val center = geometry.cellCenter(col, row - 1)
        runOnMain { dispatchThroughGrid { dispatchPinch(center.x, center.y, zoomIn) } }
        return true
    }

    /**
     * Dispatches a two-finger pinch/spread centered on ([focusX], [focusY]): two strokes running
     * concurrently along a vertical axis through the focus. Spreading apart zooms in, drawing
     * together zooms out. Finger endpoints are clamped to the screen so a focus near an edge still
     * produces a valid gesture.
     */
    @SuppressLint("NewApi") // dispatchGesture/GestureDescription guarded by the SDK_INT check
    private fun dispatchPinch(focusX: Float, focusY: Float, zoomIn: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val dm = resources.displayMetrics
        val minDim = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val startGap = (if (zoomIn) PINCH_GAP_CLOSE else PINCH_GAP_FAR) * minDim
        val endGap = (if (zoomIn) PINCH_GAP_FAR else PINCH_GAP_CLOSE) * minDim
        val maxY = dm.heightPixels.toFloat()

        fun clampY(y: Float) = y.coerceIn(0f, maxY)

        // finger A above the focus, finger B below it
        val topStart = android.graphics.Path().apply { moveTo(focusX, clampY(focusY - startGap)) }
            .also { it.lineTo(focusX, clampY(focusY - endGap)) }
        val bottomStart = android.graphics.Path().apply { moveTo(focusX, clampY(focusY + startGap)) }
            .also { it.lineTo(focusX, clampY(focusY + endGap)) }

        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    topStart, 0, PINCH_DURATION_MS
                )
            )
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    bottomStart, 0, PINCH_DURATION_MS
                )
            )
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ---------------------------------------------------------------- numbered labels

    fun areLabelsVisible(): Boolean = labelsVisible

    fun showLabels() = runOnMain {
        if (gridVisible) hideGrid()
        labelsVisible = true
        // polling runs for the whole session; just trigger an immediate refresh to draw them now
        if (sessionActive) refreshLabels()
    }

    fun hideLabels() = runOnMain {
        labelsVisible = false
        labeledNodes = emptyList()
        // a PIN pad's phonetic labels stay even when numbered labels are hidden, so re-render
        // instead of unconditionally tearing the overlay down
        if (sessionActive) refreshLabels() else removeLabelOverlay()
    }

    fun toggleLabels() = runOnMain {
        if (labelsVisible) hideLabels() else showLabels()
    }

    /** @return the number of labels currently shown, useful for spoken feedback */
    fun labelCount(): Int = labeledNodes.size

    private var lastRefreshUptimeMs = 0L
    private val scanInProgress = AtomicBoolean(false)

    private val refreshRunnable = Runnable { if (sessionActive) refreshLabels() }

    /**
     * Periodic safety-net rescan. Some surfaces (notably the dialpad / phone-number IME) change
     * their layout without sending content-changed events we receive, so the event-driven refresh
     * never fires. Polling while labels are shown keeps them in sync regardless.
     */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            refreshLabels()
            handler.postDelayed(this, LABEL_POLL_INTERVAL_MS)
        }
    }

    private fun startLabelPolling() {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, LABEL_POLL_INTERVAL_MS)
    }

    private fun stopLabelPolling() {
        handler.removeCallbacks(pollRunnable)
    }

    /**
     * Debounce + throttle: coalesce bursts of accessibility events, but guarantee a refresh at least
     * every [LABEL_REFRESH_MAX_WAIT_MS] so a continuous stream (e.g. a keyboard layer switch, which
     * fires content-changed events nonstop) can't starve the update indefinitely.
     */
    private fun scheduleLabelRefresh() {
        handler.removeCallbacks(refreshRunnable)
        val sinceLast = android.os.SystemClock.uptimeMillis() - lastRefreshUptimeMs
        val delay = if (sinceLast >= LABEL_REFRESH_MAX_WAIT_MS) {
            0L
        } else {
            LABEL_REFRESH_DEBOUNCE_MS.coerceAtMost(LABEL_REFRESH_MAX_WAIT_MS - sinceLast)
        }
        handler.postDelayed(refreshRunnable, delay)
    }

    private fun refreshLabels() {
        lastRefreshUptimeMs = android.os.SystemClock.uptimeMillis()
        // skip if a scan is already running, so a fast poll can't pile up overlapping scans
        if (!scanInProgress.compareAndSet(false, true)) return
        // The accessibility node tree is cached and only invalidated by events. Some surfaces (e.g.
        // the dialpad) change layout without sending us events, so without clearing the cache the
        // poll would keep re-reading a stale tree. clearCache() forces the next reads to be fresh.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            clearCache()
        }
        val windowList = windows
        scope.launch(Dispatchers.Default) {
            val result = try {
                ClickableNodeScanner.scan(windowList)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to scan clickable nodes", t)
                ScanResult(emptyList(), null, null)
            }
            withContext(Dispatchers.Main) { renderScan(result) }
            scanInProgress.set(false)
        }
    }

    /**
     * Decides what to draw from a scan: phonetic PIN-pad labels take priority over numbered labels
     * (which are themselves only drawn when the user has labels enabled).
     */
    private fun renderScan(result: ScanResult) {
        // the session may have ended while the (off-main-thread) scan was running
        if (!sessionActive) return

        val pad = result.pinPad
        if (pad != null) {
            clearKeyboardKeys()
            renderPinLabels(pad, result.labels)
            return
        }
        // no PIN pad on screen: leave PIN mode so it reshuffles next time, then numbered labels
        if (pinModeActive) exitPinMode()

        val keys = result.keyboardKeys
        if (labelsVisible && keys != null &&
            (keys.enterKey != null || keys.deleteKey != null || keys.shiftKey != null || keys.spaceKey != null)
        ) {
            renderKeyboardKeys(keys, result.labels)
            return
        }
        clearKeyboardKeys()

        if (labelsVisible) {
            labeledNodes = result.labels
            showOverlayLabels(result.labels)
        } else {
            labeledNodes = emptyList()
            removeLabelOverlay()
        }
    }

    /**
     * Builds and draws the enter/delete/shift/space labels for a generic on-screen keyboard (not a
     * numeric PIN pad). Only called while [labelsVisible], unlike PIN-pad labels which are always
     * shown regardless of the user's label toggle.
     */
    private fun renderKeyboardKeys(keys: KeyboardKeys, allLabels: List<LabeledNode>) {
        val nodes = ArrayList<LabeledNode>()
        val keyBounds = HashSet<Rect>()

        keyboardEnterNode = keys.enterKey?.let {
            LabeledNode(KB_NUM_ENTER, it.node, it.bounds, pinEnterLabel, centered = true)
        }
        keyboardEnterNode?.let { nodes.add(it); keyBounds.add(it.bounds) }

        keyboardDeleteNode = keys.deleteKey?.let {
            LabeledNode(KB_NUM_DELETE, it.node, it.bounds, pinDeleteLabel, centered = true)
        }
        keyboardDeleteNode?.let { nodes.add(it); keyBounds.add(it.bounds) }

        keyboardShiftNode = keys.shiftKey?.let {
            LabeledNode(KB_NUM_SHIFT, it.node, it.bounds, keyboardShiftLabel, centered = true)
        }
        keyboardShiftNode?.let { nodes.add(it); keyBounds.add(it.bounds) }

        keyboardSpaceNode = keys.spaceKey?.let {
            LabeledNode(KB_NUM_SPACE, it.node, it.bounds, keyboardSpaceLabel, centered = true)
        }
        keyboardSpaceNode?.let { nodes.add(it); keyBounds.add(it.bounds) }

        val numbered = allLabels.asSequence()
            .filter { it.bounds !in keyBounds }
            .mapIndexed { i, l -> LabeledNode(i + 1, l.node, l.bounds) }
            .toList()
        labeledNodes = numbered
        nodes.addAll(numbered)

        showOverlayLabels(nodes)
    }

    private fun clearKeyboardKeys() {
        keyboardEnterNode = null
        keyboardDeleteNode = null
        keyboardShiftNode = null
        keyboardSpaceNode = null
    }

    /**
     * Builds and draws the phonetic-word labels for a PIN pad. The digit→word shuffle is computed
     * once when the pad first appears and reused (by digit value) across refreshes, so the labels
     * stay stable while the pad is up.
     */
    private fun renderPinLabels(pad: PinPad, allLabels: List<LabeledNode>) {
        if (!pinModeActive || pinDigitToSlot == null) {
            // map every digit 0-9 (not just the ones present in this first scan) to a shuffled slot,
            // so keys that render a moment later — e.g. the lockscreen drawing its pad progressively —
            // still get a phonetic label instead of being left blank
            val slotPool = pinWords.indices.toMutableList().also { it.shuffle() }
            pinDigitToSlot = (0..9).associateWith { slotPool[it % slotPool.size] }
            pinModeActive = true
            // the session-wide grammar (set in showListening) already covers the phonetic words, so
            // no PIN-specific grammar switch is needed here
        }
        val digitToSlot = pinDigitToSlot ?: return

        pinSlotToNode.clear()
        val nodes = ArrayList<LabeledNode>()
        val pinBounds = HashSet<Rect>()
        for ((digit, key) in pad.digitNodes) {
            val slot = digitToSlot[digit] ?: continue
            if (slot !in pinWords.indices) continue
            val labeled = LabeledNode(slot, key.node, key.bounds, pinWords[slot], centered = true)
            pinSlotToNode[slot] = labeled
            nodes.add(labeled)
            pinBounds.add(key.bounds)
        }
        pinDeleteNode = pad.deleteKey?.let { LabeledNode(PIN_NUM_DELETE, it.node, it.bounds, pinDeleteLabel, centered = true) }
        pinDeleteNode?.let { nodes.add(it); pinBounds.add(it.bounds) }
        pinEnterNode = pad.enterKey?.let { LabeledNode(PIN_NUM_ENTER, it.node, it.bounds, pinEnterLabel, centered = true) }
        pinEnterNode?.let { nodes.add(it); pinBounds.add(it.bounds) }

        // keep ordinary numbered labels for non-keypad actions (e.g. the lockscreen "Notruf"/
        // emergency button) so they stay selectable while phonetic labels cover the digit keys
        val numbered = allLabels.asSequence()
            .filter { it.bounds !in pinBounds }
            .mapIndexed { i, l -> LabeledNode(i + 1, l.node, l.bounds) }
            .toList()
        labeledNodes = numbered
        nodes.addAll(numbered)

        showOverlayLabels(nodes)
    }

    private fun exitPinMode() {
        pinModeActive = false
        pinDigitToSlot = null
        pinSlotToNode.clear()
        pinDeleteNode = null
        pinEnterNode = null
    }

    private fun showOverlayLabels(nodes: List<LabeledNode>) {
        if (nodes.isEmpty()) {
            removeLabelOverlay()
            return
        }
        val wm = windowManager ?: return
        if (labelOverlay == null) {
            val view = LabelOverlayView(this)
            view.applyStyle(labelStyle)
            try {
                wm.addView(view, labelOverlayParams())
                labelOverlay = view
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to add label overlay", t)
                return
            }
        }
        labelOverlay?.setLabels(nodes)
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

    /**
     * Long-clicks (holds) the element labelled [number]. Falls back to a long-clickable ancestor,
     * then to a long-press gesture at the element's center if the node itself does not accept
     * ACTION_LONG_CLICK.
     *
     * @return true if a label with that number existed and a long click was dispatched
     */
    fun longClickLabel(number: Int): Boolean {
        val target = labeledNodes.firstOrNull { it.number == number } ?: return false
        runOnMain { performLongClick(target) }
        return true
    }

    // ---------------------------------------------------------------- mouse pointer

    /** The view last entered by a hovering pointer (mouse, or a touch-exploring finger), tracked via
     * TYPE_VIEW_HOVER_ENTER events. This observes input passively — unlike requesting raw motion
     * events (FLAG_SEND_MOTION_EVENTS), which redirects the whole input stream to this service and
     * stops it from reaching the app underneath, breaking normal mouse use. */
    @Volatile
    private var lastHoveredNode: AccessibilityNodeInfo? = null

    /**
     * Clicks the view last entered by the mouse pointer. Falls back to a clickable ancestor, then to
     * a tap gesture at the view's center, same as [clickLabel].
     *
     * @return true if a hovered position was known and a click was dispatched
     */
    fun clickAtMousePointer(): Boolean {
        val node = lastHoveredNode ?: return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        runOnMain { performClick(node, bounds) }
        return true
    }

    // ---------------------------------------------------------------- coordinate grid

    /** What happened to a spoken grid cell reference, for spoken feedback. */
    enum class GridCellResult { TAPPED, SUB_SHOWN, OUT_OF_RANGE }

    /** Whether the grid overlay should currently react to spoken cell references. */
    fun isGridActive(): Boolean = gridVisible && sessionActive

    fun showGrid() = runOnMain {
        // grid and numbered labels are mutually exclusive
        labelsVisible = false
        labeledNodes = emptyList()
        gridVisible = true
        subGridCell = null
        if (sessionActive) {
            refreshLabels()
            addGridOverlay()
        }
    }

    fun hideGrid() = runOnMain {
        gridVisible = false
        subGridCell = null
        removeGridOverlay()
    }

    /**
     * Handles a spoken cell reference like "a2" (0-based [col], 1-based [row]):
     *  - while a 3×3 sub-grid is open, references within a–c/1–3 tap the sub-cell and dismiss the
     *    sub-grid; references outside it fall through and re-anchor on the main grid;
     *  - with [explicitPress] ("press a2") the main cell's center is tapped immediately;
     *  - a bare reference opens the 3×3 refinement sub-grid inside the cell.
     */
    fun handleGridCell(col: Int, row: Int, explicitPress: Boolean): GridCellResult {
        val geometry = currentGridGeometry()
        val sub = subGridCell
        if (sub != null && col < GridGeometry.SUB_DIVISIONS && row in 1..GridGeometry.SUB_DIVISIONS) {
            subGridCell = null
            val center = geometry.subCellCenter(sub, col, row - 1)
            runOnMain {
                dispatchThroughGrid { dispatchTapAt(center.x, center.y, longPress = false) }
            }
            return GridCellResult.TAPPED
        }

        if (col >= geometry.cols || row < 1 || row > geometry.rows) {
            return GridCellResult.OUT_OF_RANGE
        }

        return if (explicitPress) {
            subGridCell = null
            val center = geometry.cellCenter(col, row - 1)
            runOnMain {
                dispatchThroughGrid { dispatchTapAt(center.x, center.y, longPress = false) }
            }
            GridCellResult.TAPPED
        } else {
            subGridCell = col to (row - 1)
            runOnMain { updateGridOverlay() }
            GridCellResult.SUB_SHOWN
        }
    }

    /**
     * Runs [gesture] as if the grid overlay weren't there: the overlay is removed first so it no
     * longer obscures the target window (Chrome and other touch-filtering surfaces drop injected
     * gestures that land under an overlay), the gesture is dispatched once the window is really
     * gone, then the overlay is restored if the grid is still meant to be visible. Must be called
     * on the main thread.
     */
    private fun dispatchThroughGrid(gesture: () -> Unit) {
        val restore = gridVisible && gridOverlay != null
        removeGridOverlay()
        handler.postDelayed({
            gesture()
            if (restore) {
                handler.postDelayed({
                    if (gridVisible && sessionActive && gridOverlay == null) addGridOverlay()
                }, GRID_RESTORE_AFTER_TAP_MS)
            }
        }, GRID_HIDE_BEFORE_TAP_MS)
    }

    /** Builds the geometry fresh from the real display size, so rotation self-heals. */
    private fun currentGridGeometry(): GridGeometry {
        val topInset = statusBarInset()
        val wm = windowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.currentWindowMetrics.bounds
            return GridGeometry(bounds.width(), bounds.height(), topInset)
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
            ?: return GridGeometry(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
                topInset,
            )
        return GridGeometry(metrics.widthPixels, metrics.heightPixels, topInset)
    }

    /** Height of the status bar in screen coordinates, so the grid can start below it. */
    private fun statusBarInset(): Float {
        val wm = windowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            return wm.currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.statusBars()).top.toFloat()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            gridOverlay?.rootWindowInsets?.systemWindowInsetTop?.let { return it.toFloat() }
        }
        return GridOverlayView.FALLBACK_STATUS_BAR_DP * resources.displayMetrics.density
    }

    private fun addGridOverlay() {
        val wm = windowManager ?: return
        if (gridOverlay == null) {
            val view = GridOverlayView(this)
            view.applyOpacity(gridOpacity)
            try {
                wm.addView(view, gridOverlayParams())
                gridOverlay = view
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to add grid overlay", t)
                return
            }
        }
        updateGridOverlay()
    }

    @SuppressLint("NewApi") // layoutInDisplayCutoutMode guarded by the SDK_INT checks
    private fun gridOverlayParams(): WindowManager.LayoutParams =
        labelOverlayParams().apply {
            // let the window extend under the status bar / display cutout: otherwise it starts
            // below them and the grid's first row and column letters get clipped away
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private fun updateGridOverlay() {
        gridOverlay?.setState(currentGridGeometry(), subGridCell)
    }

    private fun removeGridOverlay() {
        gridOverlay?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to remove grid overlay", t)
            }
        }
        gridOverlay = null
    }

    // ---------------------------------------------------------------- PIN mode

    /** Whether a numeric PIN pad is currently being shown with phonetic-word labels. */
    fun isPinModeActive(): Boolean = pinModeActive

    /** 0-based slot index for a spoken phonetic word (case-insensitive), or null if not a pin word. */
    fun pinSlotForWord(word: String): Int? =
        pinWords.indexOfFirst { it.equals(word.trim(), ignoreCase = true) }.takeIf { it >= 0 }

    /** Clicks the PIN-pad key currently labelled with phonetic word [slot] (0-based). */
    fun clickPinSlot(slot: Int): Boolean {
        val target = pinSlotToNode[slot] ?: return false
        runOnMain { performClick(target) }
        return true
    }

    fun clickPinDelete(): Boolean {
        val target = pinDeleteNode ?: return false
        runOnMain { performClick(target) }
        return true
    }

    fun clickPinEnter(): Boolean {
        val target = pinEnterNode ?: return false
        runOnMain { performClick(target) }
        return true
    }

    // ---------------------------------------------------------------- generic keyboard mode

    /** Whether a generic on-screen keyboard's enter/delete/shift/space keys are currently labeled
     * (a soft keyboard is up, labels are enabled, and at least one such key was found). */
    fun isKeyboardActive(): Boolean =
        keyboardEnterNode != null || keyboardDeleteNode != null ||
            keyboardShiftNode != null || keyboardSpaceNode != null

    // Keyboard keys are pressed with tap gestures at their bounds instead of ACTION_CLICK: Gboard's
    // virtual key nodes accept the accessibility action but don't reliably act on it (e.g. tapping
    // shift while caps lock is engaged does nothing), while real touches always work.

    fun clickKeyboardEnter(): Boolean = tapKeyboardKey(keyboardEnterNode, longPress = false)

    fun clickKeyboardDelete(): Boolean = tapKeyboardKey(keyboardDeleteNode, longPress = false)

    fun clickKeyboardShift(): Boolean = tapKeyboardKey(keyboardShiftNode, longPress = false)

    fun clickKeyboardSpace(): Boolean = tapKeyboardKey(keyboardSpaceNode, longPress = false)

    fun holdKeyboardEnter(): Boolean = tapKeyboardKey(keyboardEnterNode, longPress = true)

    fun holdKeyboardDelete(): Boolean = tapKeyboardKey(keyboardDeleteNode, longPress = true)

    /** Double-taps shift, which is how Gboard (and most keyboards) engages caps lock — a
     * long-press on shift does nothing there. */
    fun holdKeyboardShift(): Boolean {
        val target = keyboardShiftNode ?: return false
        runOnMain {
            dispatchDoubleTapAt(target.bounds.exactCenterX(), target.bounds.exactCenterY())
        }
        return true
    }

    fun holdKeyboardSpace(): Boolean = tapKeyboardKey(keyboardSpaceNode, longPress = true)

    private fun tapKeyboardKey(target: LabeledNode?, longPress: Boolean): Boolean {
        val bounds = (target ?: return false).bounds
        runOnMain { dispatchTapAt(bounds.exactCenterX(), bounds.exactCenterY(), longPress) }
        return true
    }

    private fun performClick(label: LabeledNode) = performClick(label.node, label.bounds)

    private fun performLongClick(label: LabeledNode) = performLongClick(label.node, label.bounds)

    /**
     * Clicks [node]. Falls back to a clickable ancestor, then to a tap gesture at [bounds]'s center
     * if no node in the chain accepts ACTION_CLICK.
     */
    private fun performClick(node: AccessibilityNodeInfo, bounds: Rect) {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return
            }
            n = n.parent
        }
        // last resort: tap at the center of the element's bounds
        dispatchTapAt(bounds.exactCenterX(), bounds.exactCenterY(), longPress = false)
    }

    private fun performLongClick(node: AccessibilityNodeInfo, bounds: Rect) {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isLongClickable && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                return
            }
            n = n.parent
        }
        // last resort: a long-press gesture at the center of the element's bounds
        dispatchTapAt(bounds.exactCenterX(), bounds.exactCenterY(), longPress = true)
    }

    @SuppressLint("NewApi") // guarded by the SDK_INT check below
    private fun dispatchTapAt(x: Float, y: Float, longPress: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = android.graphics.Path().apply { moveTo(x, y) }
        // hold the touch down past the system's long-press threshold so it registers as a long click
        val duration = if (longPress) {
            (android.view.ViewConfiguration.getLongPressTimeout() + 100).toLong()
        } else {
            50L
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** Two quick taps at (x, y), fast enough to register as a double tap (e.g. caps lock on shift). */
    @SuppressLint("NewApi") // guarded by the SDK_INT check below
    private fun dispatchDoubleTapAt(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val firstTap = android.graphics.Path().apply { moveTo(x, y) }
        val secondTap = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(firstTap, 0, 50))
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(secondTap, 150, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ---------------------------------------------------------------- listening bar

    /** The closed command set the grammar recognizer is constrained to: the labels drawn on screen
     * (which must be speakable no matter which skills are enabled) plus the words of every enabled
     * skill. */
    private fun fullCommandGrammar(): List<String> =
        pinWords.toList() +
            listOf(pinDeleteLabel, pinEnterLabel, keyboardShiftLabel, keyboardSpaceLabel) +
            skillGrammar.words

    /**
     * Constrains recognition to the words of the enabled skills. With no skill enabled (or none
     * having sentences for this language) there is nothing meaningful to constrain to, and forcing
     * every utterance onto the handful of on-screen labels would be far worse than not constraining
     * at all, so recognition is left free in that case.
     */
    private fun applyRecognitionGrammar() {
        if (skillGrammar.isEmpty) {
            sttInputDevice.setRecognitionGrammar(null)
        } else {
            sttInputDevice.setRecognitionGrammar(
                fullCommandGrammar(),
                skillGrammar.dictationTriggers,
                skillGrammar.fullDecodeTriggers,
            )
        }
    }

    fun showListening() = runOnMain {
        sessionActive = true
        // constrain recognition to the command set; free-form dictation kicks in only after a
        // trigger word (open/search/…) — see RelaySpeechStream
        applyRecognitionGrammar()
        val wm = windowManager ?: return@runOnMain
        if (listeningBar == null) {
            val view = ListeningBarView(localeContext)
            try {
                wm.addView(view, listeningBarParams())
                listeningBar = view
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to add listening bar", t)
            }
        }
        listeningBar?.setTranscript("", isFinal = false)
        // poll for the whole session: needed to detect a PIN pad even when numbered labels are off,
        // and to restore numbered labels if the user had them on in a previous session
        refreshLabels()
        startLabelPolling()
        // restore the grid if the user had it on in a previous session
        if (gridVisible) addGridOverlay()
    }

    fun updateTranscript(text: String, isFinal: Boolean) = runOnMain {
        listeningBar?.setTranscript(text, isFinal)
    }

    /**
     * Ends the listening session UI: removes the listening bar and the label overlay, but keeps the
     * user's label on/off choice so the next session restores it.
     */
    fun hideListening() = runOnMain {
        sessionActive = false
        // session over: drop the command grammar so the general assistant gets free dictation again
        sttInputDevice.setRecognitionGrammar(null)
        stopLabelPolling()
        removeListeningBar()
        removeConfirmationOverlay()
        // pause the overlays only; labelsVisible/gridVisible (the user's intent) are kept, but the
        // sub-grid refinement is session-scoped and forgotten
        removeLabelOverlay()
        removeGridOverlay()
        subGridCell = null
        // forget any PIN shuffle so the pad reshuffles next session it appears
        exitPinMode()
        clearKeyboardKeys()
    }

    // ---------------------------------------------------------------- continue/stop confirmation

    /**
     * Shows a touchable overlay asking whether to keep using Voice Access, with Continue/Stop
     * buttons. Used after several commands could not be understood, instead of silently aborting.
     */
    fun showContinuePrompt(onContinue: () -> Unit, onStop: () -> Unit) = runOnMain {
        val wm = windowManager ?: return@runOnMain
        if (confirmationOverlay != null) return@runOnMain
        val view = ConfirmationOverlayView(localeContext, onContinue, onStop)
        try {
            wm.addView(view, confirmationParams())
            confirmationOverlay = view
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to add confirmation overlay", t)
        }
        // keep the listening bar on top of the prompt's dim scrim so it stays visible
        bringListeningBarToFront()
    }

    /** Re-adds the listening bar last so it draws in front of other Voice Access overlays. */
    private fun bringListeningBarToFront() {
        val wm = windowManager ?: return
        val bar = listeningBar ?: return
        try {
            wm.removeViewImmediate(bar)
            wm.addView(bar, listeningBarParams())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to bring listening bar to front", t)
        }
    }

    fun hideContinuePrompt() = runOnMain { removeConfirmationOverlay() }

    fun isContinuePromptShowing(): Boolean = confirmationOverlay != null

    /** Ends the whole session: stops STT and tears down the overlays (label state is remembered). */
    fun stopVoiceSession() {
        stopListeningCallback?.invoke()
        hideListening()
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

    private fun removeConfirmationOverlay() {
        confirmationOverlay?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to remove confirmation overlay", t)
            }
        }
        confirmationOverlay = null
    }

    private fun confirmationParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            // touchable (no FLAG_NOT_TOUCHABLE) so the buttons work; not focusable so it doesn't
            // steal key/IME focus from the underlying app
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
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
        private const val LABEL_REFRESH_DEBOUNCE_MS = 80L
        // hard cap so a nonstop event stream (e.g. keyboard layer switch) still refreshes
        private const val LABEL_REFRESH_MAX_WAIT_MS = 200L
        // safety-net poll for surfaces that change layout without sending us events (e.g. dialpad);
        // the scan runs off the main thread, so this can be frequent without causing UI jank
        private const val LABEL_POLL_INTERVAL_MS = 120L
        // a deliberate (non-fling) swipe so scrolling is controlled, not flung
        private const val SWIPE_DURATION_MS = 400L
        // default swipe distance as a portion of the screen, if no amount is given
        const val DEFAULT_SWIPE_FRACTION = 0.5f
        // two-finger pinch/spread: how long the fingers travel, and the near/far half-gaps between
        // the two fingers (as a portion of the smaller screen dimension) at the start and end of the
        // gesture. Spreading from CLOSE to FAR zooms in; pinching from FAR to CLOSE zooms out.
        private const val PINCH_DURATION_MS = 300L
        private const val PINCH_GAP_CLOSE = 0.05f
        private const val PINCH_GAP_FAR = 0.30f
        // Chrome (and other surfaces that filter obscured touches) drop injected gestures that land
        // under our accessibility overlay. Remove the grid overlay first, wait this long for the
        // window to actually leave the screen, dispatch the gesture, then restore the overlay.
        private const val GRID_HIDE_BEFORE_TAP_MS = 60L
        private const val GRID_RESTORE_AFTER_TAP_MS = 400L
        // sentinel LabeledNode.number values for the PIN-pad delete/enter keys (not real slots)
        private const val PIN_NUM_DELETE = -1
        private const val PIN_NUM_ENTER = -2
        // sentinel LabeledNode.number values for the generic keyboard's enter/delete/shift/space keys
        private const val KB_NUM_ENTER = -3
        private const val KB_NUM_DELETE = -4
        private const val KB_NUM_SHIFT = -5
        private const val KB_NUM_SPACE = -6

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

    /**
     * Lets this non-Hilt AccessibilityService reach the settings data store, the STT device and the
     * skills (whose merged grammar constrains recognition).
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface VoiceAccessEntryPoint {
        fun dataStore(): DataStore<UserSettings>
        fun sttInputDeviceWrapper(): SttInputDeviceWrapper
        fun skillHandler(): SkillHandler
    }
}
