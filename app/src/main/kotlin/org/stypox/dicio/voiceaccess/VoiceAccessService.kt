package org.stypox.dicio.voiceaccess

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
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
import org.stypox.dicio.di.SttInputDeviceWrapper
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
    private var listeningBar: ListeningBarView? = null
    private var confirmationOverlay: ConfirmationOverlayView? = null

    // whether the user wants labels shown; remembered across listening sessions
    @Volatile
    private var labelsVisible = false
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

    // Resources forced to the app/Vosk locale (LocaleManager), NOT the service's system locale:
    // the PIN labels and the recognition grammar must match the language of the loaded Vosk model,
    // otherwise the words drawn on screen are out-of-grammar and nothing is recognized.
    @Volatile
    private var localizedResources: Resources = resources

    // phonetic words (slot order) and delete/enter captions, in the app/Vosk locale
    private val pinWords: Array<String> get() = localizedResources.getStringArray(R.array.va_pin_words)
    private val pinDeleteLabel: String get() = localizedResources.getString(R.string.va_pin_delete)
    private val pinEnterLabel: String get() = localizedResources.getString(R.string.va_pin_enter)

    // global command words still allowed while a PIN pad is up (go back/home, scroll, stop, …)
    private val commandGrammarWords: List<String>
        get() = localizedResources.getStringArray(R.array.va_command_grammar).toList()

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
        Log.d(TAG, "VoiceAccessService connected")
    }

    /** Keeps [localizedResources] pinned to the app/Vosk language, so PIN labels and the grammar
     * always match the loaded Vosk model rather than the device's system locale. */
    private fun collectLocale() {
        scope.launch {
            localeManager.locale.collect { locale ->
                localizedResources = resourcesForLocale(locale)
            }
        }
    }

    private fun resourcesForLocale(locale: Locale): Resources {
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        return createConfigurationContext(config).resources
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // refresh whenever a session is active: even with numbered labels off we must keep scanning
        // so a PIN pad that appears mid-session is detected and switched to phonetic labels
        if (!sessionActive || event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleLabelRefresh()
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

    // ---------------------------------------------------------------- numbered labels

    fun areLabelsVisible(): Boolean = labelsVisible

    fun showLabels() = runOnMain {
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
                ScanResult(emptyList(), null)
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
            renderPinLabels(pad, result.labels)
            return
        }
        // no PIN pad on screen: leave PIN mode so it reshuffles next time, then numbered labels
        if (pinModeActive) exitPinMode()

        if (labelsVisible) {
            labeledNodes = result.labels
            showOverlayLabels(result.labels)
        } else {
            labeledNodes = emptyList()
            removeLabelOverlay()
        }
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
            // constrain Vosk to the phonetic words plus the still-needed global commands, so PIN
            // entry is near-perfect while go back/home, scroll and stop keep working
            val grammar = pinWords.toList() +
                listOf(pinDeleteLabel, pinEnterLabel) +
                commandGrammarWords
            sttInputDevice.setRecognitionGrammar(grammar)
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
        val wasActive = pinModeActive
        pinModeActive = false
        pinDigitToSlot = null
        pinSlotToNode.clear()
        pinDeleteNode = null
        pinEnterNode = null
        // back to free dictation now that the PIN pad is gone (no-op if no grammar was set)
        if (wasActive) sttInputDevice.setRecognitionGrammar(null)
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

    // ---------------------------------------------------------------- PIN mode

    /** Whether a numeric PIN pad is currently being shown with phonetic-word labels. */
    fun isPinModeActive(): Boolean = pinModeActive

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
        sessionActive = true
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
        // poll for the whole session: needed to detect a PIN pad even when numbered labels are off,
        // and to restore numbered labels if the user had them on in a previous session
        refreshLabels()
        startLabelPolling()
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
        stopLabelPolling()
        removeListeningBar()
        removeConfirmationOverlay()
        // pause the overlay only; labelsVisible (the user's intent) is intentionally kept
        removeLabelOverlay()
        // forget any PIN shuffle so the pad reshuffles next session it appears
        exitPinMode()
    }

    // ---------------------------------------------------------------- continue/stop confirmation

    /**
     * Shows a touchable overlay asking whether to keep using Voice Access, with Continue/Stop
     * buttons. Used after several commands could not be understood, instead of silently aborting.
     */
    fun showContinuePrompt(onContinue: () -> Unit, onStop: () -> Unit) = runOnMain {
        val wm = windowManager ?: return@runOnMain
        if (confirmationOverlay != null) return@runOnMain
        val view = ConfirmationOverlayView(this, onContinue, onStop)
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
        // sentinel LabeledNode.number values for the PIN-pad delete/enter keys (not real slots)
        private const val PIN_NUM_DELETE = -1
        private const val PIN_NUM_ENTER = -2

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

    /** Lets this non-Hilt AccessibilityService reach the settings data store and the STT device. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface VoiceAccessEntryPoint {
        fun dataStore(): DataStore<UserSettings>
        fun sttInputDeviceWrapper(): SttInputDeviceWrapper
    }
}
