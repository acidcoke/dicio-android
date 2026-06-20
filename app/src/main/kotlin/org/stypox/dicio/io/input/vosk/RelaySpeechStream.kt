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
import kotlin.math.roundToInt

/**
 * A [SpeechStream] that captures the microphone **once** and fans the same PCM out to two Vosk
 * [Recognizer]s running in parallel on a shared [Model]:
 *
 *  - a **grammar** recognizer constrained to the closed command set (+ `"[unk]"`), which recognizes
 *    commands near-perfectly but emits `"[unk]"` for anything off-grammar, and
 *  - a **free** recognizer doing open dictation.
 *
 * On each endpoint the two result sets are merged into a single alternatives list (grammar first,
 * with `"[unk]"` tokens stripped) and handed to the [RecognitionListener]; the downstream skill
 * scorer then picks the best match, so closed commands resolve off the grammar recognizer and
 * open-vocabulary speech (e.g. `open <app>`) off the free one — both at the same time.
 *
 * This avoids the mic contention that comes from running two [org.vosk.android.SpeechService]s (two
 * `AudioRecord`s fighting over the mic): there is exactly one `AudioRecord` here.
 *
 * The [model] is owned by the caller and is NOT closed here; the two recognizers are created and
 * closed by the capture thread, after the loop has stopped touching them (to avoid a native crash).
 */
class RelaySpeechStream(
    private val model: Model,
    private val sampleRate: Int,
    private val grammarJson: String?,
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
            val bufferSizeShorts = (sampleRate * BUFFER_SECONDS).roundToInt()
            val recorder = try {
                createRecorder(bufferSizeShorts)
            } catch (e: Exception) {
                postError(e)
                return
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                postError(IllegalStateException("AudioRecord failed to initialize"))
                return
            }

            var freeRec: Recognizer? = null
            var grammarRec: Recognizer? = null
            try {
                freeRec = Recognizer(model, sampleRate.toFloat())
                    .apply { setMaxAlternatives(maxAlternatives) }
                grammarRec = grammarJson?.let {
                    Recognizer(model, sampleRate.toFloat(), it)
                        .apply { setMaxAlternatives(maxAlternatives) }
                }

                recorder.startRecording()
                val buffer = ShortArray(bufferSizeShorts)
                while (running) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n <= 0) continue

                    val freeEnd = freeRec.acceptWaveForm(buffer, n)
                    val grammarEnd = grammarRec?.acceptWaveForm(buffer, n) ?: false

                    if (freeEnd || grammarEnd) {
                        val merged = mergeResults(grammarRec?.result, freeRec.result)
                        postResult(merged)
                    } else {
                        // free recognizer's partial drives the live transcript (dictation view)
                        postPartial(freeRec.partialResult)
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

    private fun createRecorder(bufferSizeShorts: Int): AudioRecord {
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // make the hardware buffer comfortably larger than one read chunk
        val bufferBytes = maxOf(minBytes, bufferSizeShorts * 2 * 2)
        @Suppress("MissingPermission") // RECORD_AUDIO is required for any STT and assumed granted
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
    }

    /**
     * Merges the grammar and free result JSONs into one `{"alternatives": [...]}` payload, putting
     * the (cleaned) grammar alternatives first so that on a score tie the constrained command match
     * wins. `"[unk]"` tokens are removed from grammar alternatives; alternatives that become empty
     * are dropped.
     */
    private fun mergeResults(grammarJson: String?, freeJson: String?): String {
        val alternatives = JSONArray()

        grammarJson?.let { parseAlternatives(it) }?.forEach { (text, confidence) ->
            val cleaned = text.split(WHITESPACE)
                .filter { it.isNotBlank() && it != UNK_TOKEN }
                .joinToString(" ")
            if (cleaned.isNotBlank()) {
                alternatives.put(JSONObject().put("text", cleaned).put("confidence", confidence))
            }
        }
        freeJson?.let { parseAlternatives(it) }?.forEach { (text, confidence) ->
            if (text.isNotBlank()) {
                alternatives.put(JSONObject().put("text", text).put("confidence", confidence))
            }
        }

        return JSONObject().put("alternatives", alternatives).toString()
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
        }
        val text = obj.optString("text").trim()
        return if (text.isNotEmpty()) listOf(Pair(text, 1.0)) else emptyList()
    }

    companion object {
        private val TAG = RelaySpeechStream::class.simpleName
        private const val BUFFER_SECONDS = 0.2f
        private const val UNK_TOKEN = "[unk]"
        private val WHITESPACE = Regex("\\s+")
    }
}
