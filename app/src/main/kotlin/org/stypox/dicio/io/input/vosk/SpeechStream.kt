package org.stypox.dicio.io.input.vosk

import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Abstraction over "the thing that captures audio and emits recognition results". Currently only
 * wraps Vosk's own [SpeechService]; kept as a seam so alternative capture strategies could be added
 * without touching the [VoskInputDevice] state machine.
 */
interface SpeechStream {
    fun startListening(listener: RecognitionListener): Boolean
    fun stop(): Boolean
    fun shutdown()
}

/** Thin wrapper around Vosk's [SpeechService] so it satisfies [SpeechStream]. */
class SingleSpeechStream(private val speechService: SpeechService) : SpeechStream {
    override fun startListening(listener: RecognitionListener) = speechService.startListening(listener)
    override fun stop() = speechService.stop()
    override fun shutdown() = speechService.shutdown()
}
