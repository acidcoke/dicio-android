package org.stypox.dicio.io.input.vosk

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A [SpeechStream] that keeps recognition **constrained** by default and only falls back to free
 * dictation for the argument of a command:
 *
 *  - A single **grammar** recognizer (with word timestamps) decodes every utterance against the
 *    closed command set. Pure closed commands ("scroll up", "go home", "tap five") are recognized
 *    here and nothing else runs — maximum constraint, no force-fit.
 *  - When the **first** recognized word is a [dictationTriggers] word (open/search/navigate/…), the
 *    utterance has a free-form argument. At the endpoint the audio captured **after that word's end
 *    timestamp** is re-decoded by a **free** recognizer, and the result is emitted as
 *    `"<trigger> <free tail>"`. The grammar's own (force-fit) tail is discarded, so e.g. "open
 *    signal" is no longer mangled into "open second".
 *  - For the subset of triggers in [fullDecodeTriggers] (e.g. "open"), even the trigger word itself
 *    is discarded: the **entire buffered utterance**, from the start, is re-decoded by the free
 *    recognizer and emitted as-is, with no grammar-recognized prefix.
 *
 * There is exactly one [AudioRecord]; the free recognizer is only ever fed buffered audio, so it
 * adds no extra live decoding and runs solely on trigger utterances.
 *
 * The [model] is owned by the caller and is NOT closed here; the recognizers are created and closed
 * by the capture thread, after the loop has stopped touching them (to avoid a native crash).
 */
class RelaySpeechStream(
    private val model: Model,
    private val sampleRate: Int,
    private val grammarJson: String,
    private val dictationTriggers: Set<String>,
    private val fullDecodeTriggers: Set<String>,
    private val maxAlternatives: Int,
) : SpeechStream {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var thread: CaptureThread? = null

    override fun startListening(listener: RecognitionListener): Boolean {
        if (thread != null) return false
        val t = CaptureThread(listener)
        thread = t
        t.start()
        return true
    }

    override fun stop(): Boolean {
        val t = thread ?: return false
        thread = null
        t.stopAndJoin()
        return true
    }

    override fun shutdown() {
        stop()
    }

    private inner class CaptureThread(
        private val listener: RecognitionListener,
    ) : Thread("RelaySpeechStream") {

        @Volatile private var running = true

        // PCM of the current utterance, kept so a trigger's tail can be re-decoded at the endpoint.
        // Grows as audio arrives and is cleared at each endpoint, so its sample indices stay aligned
        // with the grammar recognizer's per-utterance word timestamps.
        private var buffer = ShortArray(sampleRate) // ~1 s to start
        private var bufferLen = 0
        private val maxBufferSamples = sampleRate * MAX_UTTERANCE_SECONDS

        fun stopAndJoin() {
            running = false
            // never join from our own thread (would deadlock); stopListening runs on main
            if (currentThread() !== this) {
                try {
                    join()
                } catch (e: InterruptedException) {
                    currentThread().interrupt()
                }
            }
        }

        override fun run() {
            val frameShorts = (sampleRate * FRAME_SECONDS).roundToInt()
            val recorder = try {
                createRecorder(frameShorts)
            } catch (e: Exception) {
                postError(e)
                return
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                postError(IllegalStateException("AudioRecord failed to initialize"))
                return
            }

            var grammarRec: Recognizer? = null
            var freeRec: Recognizer? = null
            try {
                grammarRec = Recognizer(model, sampleRate.toFloat(), grammarJson).apply {
                    setWords(true) // need per-word start/end timestamps to find the splice point
                    setMaxAlternatives(0) // single best WITH word timings (alternatives mode drops them)
                }
                freeRec = Recognizer(model, sampleRate.toFloat()).apply {
                    setMaxAlternatives(maxAlternatives)
                }

                recorder.startRecording()
                val frame = ShortArray(frameShorts)
                while (running) {
                    val n = recorder.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    appendToBuffer(frame, n)

                    if (grammarRec.acceptWaveForm(frame, n)) {
                        postResult(buildResult(grammarRec.result, freeRec))
                        bufferLen = 0 // start a fresh utterance, aligned with the recognizer's reset
                    } else {
                        postPartial(grammarRec.partialResult)
                    }
                }
            } catch (e: Exception) {
                postError(e)
            } finally {
                // strict teardown order: the loop has exited, so it is safe to stop the recorder
                // and only then close the recognizers (the shared model stays alive)
                try {
                    recorder.stop()
                } catch (_: IllegalStateException) {
                }
                recorder.release()
                grammarRec?.close()
                freeRec?.close()
            }
        }

        private fun appendToBuffer(frame: ShortArray, n: Int) {
            if (bufferLen >= maxBufferSamples) return // cap memory on a runaway utterance
            if (bufferLen + n > buffer.size) {
                buffer = buffer.copyOf(min(maxBufferSamples, maxOf(buffer.size * 2, bufferLen + n)))
            }
            val toCopy = min(n, buffer.size - bufferLen)
            System.arraycopy(frame, 0, buffer, bufferLen, toCopy)
            bufferLen += toCopy
        }

        /**
         * Turns the grammar recognizer's final result into the JSON handed to [VoskListener]. If the
         * first word is a [fullDecodeTriggers] word, the entire buffered utterance is re-decoded by
         * [freeRec] and emitted as-is. Else if it's a [dictationTriggers] word, only the buffered
         * audio after that word is re-decoded and emitted as `"<trigger> <tail>"`. Otherwise the
         * constrained grammar text is emitted as-is.
         */
        private fun buildResult(grammarResultJson: String, freeRec: Recognizer): String {
            val obj = try {
                JSONObject(grammarResultJson)
            } catch (e: Exception) {
                return EMPTY_RESULT
            }

            val words = obj.optJSONArray("result")
            val first = words?.optJSONObject(0)
            val firstWord = first?.optString("word")?.lowercase()?.takeIf { it.isNotBlank() }

            if (firstWord != null && firstWord in fullDecodeTriggers) {
                return fullDecodeResult(decodeTail(freeRec, 0))
            }

            if (firstWord != null && firstWord in dictationTriggers) {
                val endSec = first.optDouble("end", 0.0)
                val tailAlts = decodeTail(freeRec, (endSec * sampleRate).roundToInt())
                return triggerResult(firstWord, tailAlts)
            }

            // not a trigger: emit the constrained text, with any "[unk]" tokens stripped
            val cleaned = obj.optString("text")
                .split(WHITESPACE)
                .filter { it.isNotBlank() && it != UNK_TOKEN }
                .joinToString(" ")
            if (cleaned.isBlank()) return EMPTY_RESULT
            return JSONObject().put(
                "alternatives",
                JSONArray().put(JSONObject().put("text", cleaned).put("confidence", 1.0)),
            ).toString()
        }

        /** Re-decodes [buffer] from [startSample] to the end with the free recognizer. */
        private fun decodeTail(freeRec: Recognizer, startSample: Int): List<Pair<String, Double>> {
            val from = startSample.coerceIn(0, bufferLen)
            val count = bufferLen - from
            freeRec.reset()
            if (count > 0) {
                val tail = buffer.copyOfRange(from, bufferLen)
                freeRec.acceptWaveForm(tail, count)
            }
            return parseAlternatives(freeRec.finalResult)
        }

        private fun postResult(json: String) {
            if (!running) return
            mainHandler.post { listener.onResult(json) }
        }

        private fun postPartial(json: String) {
            if (!running) return
            mainHandler.post { listener.onPartialResult(json) }
        }

        private fun postError(e: Exception) {
            Log.e(TAG, "RelaySpeechStream error", e)
            mainHandler.post { listener.onError(e) }
        }
    }

    /**
     * Builds the merged result for a trigger utterance: each free-tail alternative prefixed with the
     * (grammar-recognized, reliable) [trigger] word. Duplicates are dropped, order preserved. If the
     * tail is empty (e.g. just "open" with no app), the bare trigger word is emitted.
     */
    private fun triggerResult(trigger: String, tailAlts: List<Pair<String, Double>>): String {
        val alternatives = JSONArray()
        val seen = HashSet<String>()
        fun add(text: String, confidence: Double) {
            if (text.isBlank() || !seen.add(text.lowercase())) return
            alternatives.put(JSONObject().put("text", text).put("confidence", confidence))
        }
        tailAlts.forEach { (tail, confidence) -> add("$trigger $tail".trim(), confidence) }
        if (alternatives.length() == 0) add(trigger, 1.0)
        return JSONObject().put("alternatives", alternatives).toString()
    }

    /** Builds the merged result for a [fullDecodeTriggers] utterance: the free-decoded alternatives
     *  as-is, with no grammar-recognized prefix. */
    private fun fullDecodeResult(alts: List<Pair<String, Double>>): String {
        val alternatives = JSONArray()
        alts.forEach { (text, confidence) ->
            if (text.isNotBlank()) {
                alternatives.put(JSONObject().put("text", text).put("confidence", confidence))
            }
        }
        return JSONObject().put("alternatives", alternatives).toString()
    }

    private fun createRecorder(frameShorts: Int): AudioRecord {
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // make the hardware buffer comfortably larger than one read chunk
        val bufferBytes = maxOf(minBytes, frameShorts * 2 * 2)
        @Suppress("MissingPermission") // RECORD_AUDIO is required for any STT and assumed granted
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
    }

    private fun parseAlternatives(json: String): List<Pair<String, Double>> {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return emptyList()
        }
        obj.optJSONArray("alternatives")?.let { arr ->
            return (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .map { Pair(it.optString("text").trim(), it.optDouble("confidence", 1.0)) }
                .filter { it.first.isNotBlank() }
        }
        val text = obj.optString("text").trim()
        return if (text.isNotEmpty()) listOf(Pair(text, 1.0)) else emptyList()
    }

    companion object {
        private val TAG = RelaySpeechStream::class.simpleName
        private const val FRAME_SECONDS = 0.2f
        private const val MAX_UTTERANCE_SECONDS = 30
        private const val UNK_TOKEN = "[unk]"
        private val WHITESPACE = Regex("\\s+")
        private val EMPTY_RESULT = JSONObject().put("alternatives", JSONArray()).toString()
    }
}
