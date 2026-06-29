package org.stypox.dicio.io.input

import kotlinx.coroutines.flow.StateFlow
import org.stypox.dicio.settings.datastore.UserSettings

interface SttInputDevice {
    val uiState: StateFlow<SttState>

    fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean

    fun stopListening()

    fun onClick(eventListener: (InputEvent) -> Unit)

    /**
     * Constrains speech recognition to the given list of allowed words/phrases (a context grammar),
     * or restores free recognition when [grammar] is `null`/empty. Used by Voice Access to bias the
     * recognizer to the on-screen command set (e.g. the phonetic words of a PIN pad). Devices that
     * cannot constrain their vocabulary ignore this. No-op by default.
     *
     * [dictationTriggers] are leading command words (open/search/…) after which the rest of the
     * utterance is dictated free-form: when the constrained recognizer hears one as the first word,
     * the audio after it is re-decoded without the grammar so the argument is not force-fit onto a
     * grammar word. The trigger words must also appear in [grammar] to be recognizable.
     */
    fun setRecognitionGrammar(grammar: List<String>?, dictationTriggers: List<String> = emptyList()) {}

    suspend fun destroy()

    companion object {
        const val DEFAULT_STT_SILENCE_DURATION = 2
        fun getSttSilenceDurationOrDefault(settings: UserSettings): Int {
            // unfortunately there is no way to tell protobuf to use "2" as the default value
            return settings.sttSilenceDuration.takeIf { it > 0 } ?: DEFAULT_STT_SILENCE_DURATION
        }
    }
}
