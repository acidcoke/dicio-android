package org.stypox.dicio.io.wake

import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.datastore.core.DataStore
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Confirmation
import org.stypox.dicio.settings.datastore.ListeningDuration
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.MainActivity
import org.stypox.dicio.MainActivity.Companion.ACTION_WAKE_WORD
import org.stypox.dicio.R
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.voiceaccess.VoiceAccessService
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@AndroidEntryPoint
class WakeService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private val listening = AtomicBoolean(false)

    @Inject
    lateinit var skillEvaluator: SkillEvaluator
    @Inject
    lateinit var sttInputDevice: SttInputDeviceWrapper
    @Inject
    lateinit var wakeDevice: WakeDeviceWrapper
    @Inject
    lateinit var dataStore: DataStore<UserSettings>
    @Inject
    lateinit var skillContext: SkillContextInternal

    private val handler = Handler(Looper.getMainLooper())
    private val releaseSttResourcesRunnable = Runnable {
        if (MainActivity.isCreated <= 0) {
            // if the main activity is neither visible nor in the background,
            // then unload the STT after a while because it would be using resources uselessly
            sttInputDevice.reinitializeToReleaseResources()
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(this, NotificationManager::class.java)!!

        scope.launch {
            // Recreate the notification so that it says the correct thing (i.e. there is a
            // different string for the "Hey Dicio" wake word and for a custom one).
            // Ignore the first one (i.e. the current value), which is handled in onStartCommand.
            wakeDevice.isHeyDicio.drop(1).collect { isHeyDicio ->
                createForegroundNotification(isHeyDicio)
            }
        }

        scope.launch {
            dataStore.data
                .map { it.listeningDuration }
                .collect { listeningDuration = normalizeListeningDuration(it) }
        }

        scope.launch {
            dataStore.data
                .map { it.invalidCommandsBeforePrompt }
                .collect {
                    invalidCommandsBeforePrompt =
                        if (it <= 0) DEFAULT_INVALID_COMMANDS_BEFORE_PROMPT else it
                }
        }

        // Screen on/off cannot be declared in the manifest, so register dynamically. Used to pause
        // the Voice Access listening session while the screen is off and resume it when it comes on.
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_WAKE_SERVICE) {
            listening.set(false)
            return START_NOT_STICKY
        }

        try {
            createForegroundNotification(wakeDevice.isHeyDicio.value)
        } catch (t: Throwable) {
            stopWithMessage("could not create WakeService foreground notification", t)
            return START_NOT_STICKY
        }

        if (listening.getAndSet(true)) {
            return START_STICKY // if we were already listening, do nothing more
        }

        if (ContextCompat.checkSelfPermission(this, RECORD_AUDIO) != PERMISSION_GRANTED) {
            stopWithMessage("Could not start WakeService: microphone permission not granted")
            return START_NOT_STICKY
        }

        when (wakeDevice.state.value) {
            WakeState.NotLoaded,
            WakeState.Loading,
            WakeState.Loaded -> {}
            else -> {
                stopWithMessage("Could not start WakeService: wake word device not ready")
                return START_NOT_STICKY
            }
        }

        scope.launch {
            try {
                listenForWakeWord()
                stopWithMessage() // exit normally, as the user just stopped the service
            } catch (t: Throwable) {
                stopWithMessage("Cannot continue listening for wake word", t)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        listening.set(false)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // not registered; ignore
        }
        job.cancel()
        wakeDevice.reinitializeToReleaseResources()
        super.onDestroy()
    }

    private fun stopWithMessage(message: String = "", throwable: Throwable? = null) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()

        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else if (message.isNotEmpty()) {
            Log.e(TAG, message)
        }
    }

    private fun createForegroundNotification(isHeyDicio: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FOREGROUND_NOTIFICATION_CHANNEL_ID,
                getString(R.string.wake_service_label),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = getString(R.string.wake_service_foreground_notification_summary)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, FOREGROUND_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hearing_white)
            .setContentTitle(
                getString(
                    if (isHeyDicio) R.string.wake_service_foreground_notification
                    else R.string.wake_custom_service_foreground_notification
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(NotificationCompat.Action(
                R.drawable.ic_stop_circle_white,
                getString(R.string.stop),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, WakeService::class.java)
                        .apply { action = ACTION_STOP_WAKE_SERVICE },
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            ))
            .build()

        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun listenForWakeWord() {
        @SuppressLint("MissingPermission")
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            6400,
        )

        var audio = ShortArray(0)
        var nextWakeWordAllowed = Instant.MIN

        try {
            ar.startRecording()
            while (listening.get()) {
                if (audio.size != wakeDevice.frameSize()) {
                    audio = ShortArray(wakeDevice.frameSize())
                }

                ar.read(audio, 0, audio.size)

                val wakeWordDetected = wakeDevice.processFrame(audio)
                if (wakeWordDetected && Instant.now() > nextWakeWordAllowed) {
                    nextWakeWordAllowed = Instant.now().plusMillis(WAKE_WORD_BACKOFF_MILLIS)
                    onWakeWordDetected()
                }

                lastHeard.set(Instant.now())
            }
        } finally {
            ar.stop()
            ar.release()
        }
    }

    // true while a hands-free Voice Access session is active (keeps listening across commands)
    private val voiceSessionActive = AtomicBoolean(false)

    // the configured session duration mode, kept in sync with the settings data store
    @Volatile
    private var listeningDuration = ListeningDuration.LISTENING_DURATION_TIMEOUT_30S

    // set when a session is paused because the screen turned off, so it resumes on screen-on
    @Volatile
    private var resumeOnScreenOn = false

    // consecutive results that were not understood; at the threshold the continue/stop prompt shows
    private var invalidCommandCount = 0
    // configurable threshold of unrecognized commands before showing the continue/stop prompt
    @Volatile
    private var invalidCommandsBeforePrompt = DEFAULT_INVALID_COMMANDS_BEFORE_PROMPT
    // while true the continue/stop prompt is shown and only "continue"/"resume" voice is accepted
    @Volatile
    private var awaitingContinueConfirmation = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pauseVoiceSessionForScreenOff()
                Intent.ACTION_SCREEN_ON -> resumeVoiceSessionForScreenOn()
            }
        }
    }

    private val sessionTimeoutRunnable = Runnable {
        // 30 s without a recognized command: end the Voice Access listening session
        endVoiceSession()
    }

    private val rearmListeningRunnable = Runnable {
        if (voiceSessionActive.get()) {
            // reset the transcript to the "Listening…" hint and start a new recognition
            VoiceAccessService.instance?.updateTranscript("", false)
            sttInputDevice.tryLoad(::onVoiceAccessInputEvent)
        }
    }

    /** Treats UNSET / unrecognized as the default (30 s timeout). */
    private fun normalizeListeningDuration(d: ListeningDuration): ListeningDuration = when (d) {
        ListeningDuration.LISTENING_DURATION_TIMEOUT_30S,
        ListeningDuration.LISTENING_DURATION_UNTIL_SCREEN_OFF -> d
        else -> ListeningDuration.LISTENING_DURATION_TIMEOUT_30S
    }

    /** Arms the inactivity timeout, but only in the 30 s mode; until-screen-off mode never times out. */
    private fun scheduleSessionTimeout() {
        handler.removeCallbacks(sessionTimeoutRunnable)
        if (listeningDuration == ListeningDuration.LISTENING_DURATION_TIMEOUT_30S) {
            handler.postDelayed(sessionTimeoutRunnable, VOICE_SESSION_TIMEOUT_MILLIS)
        }
    }

    private fun endVoiceSession() {
        voiceSessionActive.set(false)
        // a genuine end (timeout / "stop" / error) must not auto-resume when the screen comes on
        resumeOnScreenOn = false
        awaitingContinueConfirmation = false
        invalidCommandCount = 0
        handler.removeCallbacks(sessionTimeoutRunnable)
        handler.removeCallbacks(rearmListeningRunnable)
        skillEvaluator.onSkillResult = null
        skillEvaluator.suppressReopenMicrophone = false
        sttInputDevice.stopListening()
        // hideListening() tears down all overlays but remembers the user's label on/off choice
        VoiceAccessService.instance?.hideListening()
    }

    /** Pauses an active session when the screen turns off, remembering to resume on screen-on. */
    private fun pauseVoiceSessionForScreenOff() {
        // also pause if we are currently asking the continue/stop question
        if (!voiceSessionActive.getAndSet(false) && !awaitingContinueConfirmation) return
        resumeOnScreenOn = true
        awaitingContinueConfirmation = false
        handler.removeCallbacks(sessionTimeoutRunnable)
        handler.removeCallbacks(rearmListeningRunnable)
        skillEvaluator.suppressReopenMicrophone = false
        sttInputDevice.stopListening()
        VoiceAccessService.instance?.hideListening()
    }

    /**
     * Briefly turns the screen on via a wake lock that auto-releases. The screen-bright wake-lock
     * levels are deprecated but remain the practical way to wake the display from a background
     * service; [PowerManager.ON_AFTER_RELEASE] keeps it on for the normal timeout afterwards.
     */
    @Suppress("DEPRECATION")
    private fun turnScreenOn(powerManager: PowerManager) {
        try {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "$TAG:wakeOnWakeWord",
            )
            wakeLock.acquire(SCREEN_WAKE_MILLIS)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire wake lock to turn the screen on", t)
        }
    }

    /** Resumes a session that was paused by the screen turning off. */
    private fun resumeVoiceSessionForScreenOn() {
        if (!resumeOnScreenOn) return
        resumeOnScreenOn = false
        val service = VoiceAccessService.instance ?: return
        onWakeWordDetectedVoiceAccess(service)
    }

    private fun onWakeWordDetected() {
        Log.d(TAG, "Wake word detected")

        val service = VoiceAccessService.instance
        if (service != null) {
            // Voice Access overlay mode: control the phone hands-free over whatever app is in the
            // foreground, without bringing the dicio activity forward.
            onWakeWordDetectedVoiceAccess(service)
            return
        }

        // The accessibility service is not enabled yet: fall back to opening the dicio activity,
        // which can prompt the user to enable Voice Access.
        onWakeWordDetectedOpenActivity()
    }

    private fun onWakeWordDetectedVoiceAccess(service: VoiceAccessService) {
        // if the screen is off, wake it so the user can see the listening UI and labels and so the
        // accessibility overlays can be drawn; then proceed to start the session
        val powerManager = getSystemService(this, PowerManager::class.java)
        if (powerManager?.isInteractive == false) {
            turnScreenOn(powerManager)
        }

        voiceSessionActive.set(true)
        invalidCommandCount = 0
        awaitingContinueConfirmation = false
        // show the listening bar over the current app
        service.showListening()
        // the "stop" command ends the whole continuous session
        service.stopListeningCallback = { endVoiceSession() }
        // count recognized-but-unmatched utterances (fallback misses) toward the continue prompt
        skillEvaluator.onSkillResult = { matched -> handler.post { onSkillMatchResult(matched) } }
        // we drive continuous listening ourselves; stop skills from reopening the mic with their
        // own listener (which would hijack our onVoiceAccessInputEvent loop)
        skillEvaluator.suppressReopenMicrophone = true

        // forward STT events to both the overlay (live transcript) and the skill evaluator
        sttInputDevice.tryLoad(::onVoiceAccessInputEvent)

        // auto-end the session after the inactivity timeout (30 s mode only)
        handler.removeCallbacks(rearmListeningRunnable)
        scheduleSessionTimeout()

        // unload the STT after a longer while if nothing keeps it alive
        handler.removeCallbacks(releaseSttResourcesRunnable)
        handler.postDelayed(releaseSttResourcesRunnable, RELEASE_STT_RESOURCES_MILLIS)
    }

    private fun onVoiceAccessInputEvent(event: org.stypox.dicio.io.input.InputEvent) {
        Log.d(TAG, "VoiceAccess input event: ${event::class.simpleName}")
        val service = VoiceAccessService.instance

        // While the continue/stop prompt is up, only "continue"/"resume" voice is honored (plus the
        // on-screen buttons). Everything else is ignored and not forwarded to the skill evaluator.
        if (awaitingContinueConfirmation) {
            handleConfirmationInputEvent(event, service)
            return
        }

        when (event) {
            is org.stypox.dicio.io.input.InputEvent.Partial ->
                service?.updateTranscript(event.utterance, false)
            is org.stypox.dicio.io.input.InputEvent.Final -> {
                service?.updateTranscript(event.utterances.firstOrNull()?.first.orEmpty(), true)
                // keep listening; whether this counts as valid is decided by onSkillMatchResult
                // (a matched skill resets the counter, a fallback miss increments it)
                scheduleSessionTimeout()
                handler.postDelayed(rearmListeningRunnable, REARM_DELAY_MILLIS)
            }
            org.stypox.dicio.io.input.InputEvent.None -> {
                // nothing understood: count it and either keep listening or ask to continue
                if (!registerInvalidCommand(service)) {
                    handler.postDelayed(rearmListeningRunnable, REARM_DELAY_MILLIS)
                }
            }
            is org.stypox.dicio.io.input.InputEvent.Error -> {
                // recognition error: treat like an invalid command instead of aborting the session
                if (!registerInvalidCommand(service)) {
                    handler.postDelayed(rearmListeningRunnable, REARM_DELAY_MILLIS)
                }
            }
        }
        // run the command (back, open, labels, click number, stop, …)
        skillEvaluator.processInputEvent(event)
    }

    /** Result of evaluating a recognized utterance: matched a skill, or fell through to fallback. */
    private fun onSkillMatchResult(matched: Boolean) {
        if (awaitingContinueConfirmation || !voiceSessionActive.get()) return
        if (matched) {
            invalidCommandCount = 0
        } else {
            // recognized speech but no command matched it: counts toward the continue prompt
            registerInvalidCommand(VoiceAccessService.instance)
        }
    }

    /**
     * Counts an unrecognized result. Returns true if the continue/stop prompt was shown (so the
     * caller should not re-arm normal listening).
     */
    private fun registerInvalidCommand(service: VoiceAccessService?): Boolean {
        invalidCommandCount += 1
        if (invalidCommandCount < invalidCommandsBeforePrompt) {
            return false
        }
        enterContinueConfirmation(service)
        return true
    }

    private fun enterContinueConfirmation(service: VoiceAccessService?) {
        awaitingContinueConfirmation = true
        // don't time out while waiting for the user's answer
        handler.removeCallbacks(sessionTimeoutRunnable)
        service?.showContinuePrompt(
            onContinue = { handler.post { resumeFromContinueConfirmation() } },
            onStop = { handler.post { endVoiceSession() } },
        )
        // keep listening so the user can say "continue" or "stop"
        handler.removeCallbacks(rearmListeningRunnable)
        handler.postDelayed(rearmListeningRunnable, REARM_DELAY_MILLIS)
    }

    private fun resumeFromContinueConfirmation() {
        awaitingContinueConfirmation = false
        invalidCommandCount = 0
        VoiceAccessService.instance?.hideContinuePrompt()
        VoiceAccessService.instance?.updateTranscript("", false)
        scheduleSessionTimeout()
        handler.postDelayed(rearmListeningRunnable, REARM_DELAY_MILLIS)
    }

    private fun handleConfirmationInputEvent(
        event: org.stypox.dicio.io.input.InputEvent,
        service: VoiceAccessService?,
    ) {
        when (event) {
            is org.stypox.dicio.io.input.InputEvent.Partial ->
                service?.updateTranscript(event.utterance, false)
            is org.stypox.dicio.io.input.InputEvent.Final -> {
                val said = event.utterances.firstOrNull()?.first.orEmpty()
                service?.updateTranscript(said, true)
                when (classifyConfirmation(said)) {
                    is Confirmation.Continue -> resumeFromContinueConfirmation()
                    is Confirmation.Stop -> endVoiceSession()
                    // not understood: keep listening for an answer
                    null -> handler.postDelayed(rearmListeningRunnable, REARM_DELAY_MILLIS)
                }
            }
            org.stypox.dicio.io.input.InputEvent.None,
            is org.stypox.dicio.io.input.InputEvent.Error ->
                // keep listening so the user can answer by voice
                handler.postDelayed(rearmListeningRunnable, REARM_DELAY_MILLIS)
        }
    }

    /**
     * Matches the spoken answer against the per-language continue/stop sentences. Returns the
     * matched [Confirmation] (Continue/Stop) or null if it scored too low to be either.
     */
    private fun classifyConfirmation(said: String): Confirmation? {
        val data = Sentences.Confirmation[skillContext.sentencesLanguage] ?: return null
        val helper = MatchHelper(skillContext.parserFormatter, said)
        val (score, result) = data.score(helper, said)
        return if (score.scoreIn01Range() >= CONFIRMATION_THRESHOLD) result else null
    }

    private fun onWakeWordDetectedOpenActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.setAction(ACTION_WAKE_WORD)
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK)

        // Start listening and pass STT events to the skill evaluator.
        // Note that this works even if the MainActivity is opened later!
        sttInputDevice.tryLoad(skillEvaluator::processInputEvent)

        // Unload the STT after a while because it would be using RAM uselessly
        handler.removeCallbacks(releaseSttResourcesRunnable)
        handler.postDelayed(releaseSttResourcesRunnable, RELEASE_STT_RESOURCES_MILLIS)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || MainActivity.isInForeground > 0) {
            // start the activity directly on versions prior to Android 10,
            // or if the MainActivity is already running in the foreground
            startActivity(intent)

        } else {
            // Android 10+ does not allow starting activities from the background,
            // so show a full-screen notification instead, which does actually result in starting
            // the activity from the background if the phone is off and Do Not Disturb is not active
            // Maybe we could also use the "Display over other apps" permission?

            val channel = NotificationChannel(
                TRIGGERED_NOTIFICATION_CHANNEL_ID,
                getString(R.string.wake_service_triggered_notification),
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = getString(R.string.wake_service_triggered_notification_summary)
            notificationManager.createNotificationChannel(channel)

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(this, TRIGGERED_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hearing_white)
                .setContentTitle(getString(R.string.wake_service_triggered_notification))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    getString(R.string.wake_service_triggered_notification_summary)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(pendingIntent, true)
                .build()

            notificationManager.cancel(TRIGGERED_NOTIFICATION_ID)
            notificationManager.notify(TRIGGERED_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /**
         * Starting from Android 11, it is not possible to start a foreground service
         * that accesses the microphone from a BOOT_COMPLETED broadcast. So we show a
         * notification instead, which starts the foreground service when clicked.
         * https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed
         */
        @RequiresApi(Build.VERSION_CODES.R)
        fun createNotificationToStartLater(context: Context) {
            val notificationManager = getSystemService(context, NotificationManager::class.java)
                ?: return

            val channel = NotificationChannel(
                START_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.wake_service_start_notification),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = context.getString(R.string.wake_service_start_notification_summary)
            notificationManager.createNotificationChannel(channel)

            val pendingIntent = PendingIntent.getForegroundService(
                context,
                0,
                Intent(context, WakeService::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, START_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hearing_white)
                .setContentTitle(context.getString(R.string.wake_service_start_notification))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.wake_service_start_notification_summary)))
                .setOngoing(false)
                .setShowWhen(false)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(START_NOTIFICATION_ID, notification)
        }

        /**
         * Start the service. Call this only from a foreground part of the app (e.g. the main
         * activity), or from BOOT_COMPLETED only before Android 11. For BOOT_COMPLETED on Android
         * 11+ use [createNotificationToStartLater] instead.
         */
        fun start(context: Context) {
            Log.d(TAG, "WakeService.start() called from ${Throwable().stackTrace[1]}")
            val intent = Intent(context, WakeService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, WakeService::class.java)
                    .apply { action = ACTION_STOP_WAKE_SERVICE })
            } catch (_: IllegalStateException) {
                // Must not have been running. No problem with that.
            }
        }

        // Consider the service running if it processed any audio data within the past half second.
        fun isRunning(): Boolean = lastHeard.get()?.isAfter(Instant.now().minusMillis(500)) == true

        /**
         * On Android 10+ cancels any notification telling the user that the Dicio wake word was
         * triggered, which is not needed anymore after the main activity starts.
         */
        fun cancelTriggeredNotification(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getSystemService(context, NotificationManager::class.java)
                    ?.cancel(TRIGGERED_NOTIFICATION_ID)
            }
        }

        private val lastHeard = AtomicReference<Instant>()

        private val TAG = WakeService::class.simpleName
        private const val FOREGROUND_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.FOREGROUND"
        private const val START_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.START"
        private const val TRIGGERED_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.TRIGGERED"
        private const val FOREGROUND_NOTIFICATION_ID = 19803672
        private const val START_NOTIFICATION_ID = 48019274
        private const val TRIGGERED_NOTIFICATION_ID = 601398647
        private const val WAKE_WORD_BACKOFF_MILLIS = 4000L
        private const val VOICE_SESSION_TIMEOUT_MILLIS = 30000L // 30 s inactivity ends session
        private const val SCREEN_WAKE_MILLIS = 3000L // how long to force the screen on when waking
        // default unrecognized results before asking to continue (when the setting is unset)
        const val DEFAULT_INVALID_COMMANDS_BEFORE_PROMPT = 3
        // minimum recognizer score for a spoken continue/stop answer to count (mirrors SkillRanker)
        private const val CONFIRMATION_THRESHOLD = 0.7f
        private const val REARM_DELAY_MILLIS = 350L // brief gap before listening for next command
        private const val ACTION_STOP_WAKE_SERVICE =
            "org.stypox.dicio.io.wake.WakeService.ACTION_STOP"
        private const val RELEASE_STT_RESOURCES_MILLIS = 1000L * 60 * 5 // 5 minutes
    }
}
