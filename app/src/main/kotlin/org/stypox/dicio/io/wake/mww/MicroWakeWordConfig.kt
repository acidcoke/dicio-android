package org.stypox.dicio.io.wake.mww

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

data class MicroWakeWordConfig(
    val id: String,
    val wakeWord: String,
    val author: String,
    val website: String,
    val modelFile: File,
    val trainedLanguages: List<String>,
    val probabilityCutoff: Float,
    val featureStepSize: Int,
    val slidingWindowSize: Int,
) {
    data class BuiltinDescriptor(
        val id: String,
        val displayName: String,
        val tfliteUrl: String,
        val jsonUrl: String,
    )

    companion object {
        const val DEFAULT_ID = "okay_nabu"
        const val ASSET_DIR_NAME = "microWakeWord"

        val BUILTINS: List<BuiltinDescriptor> = listOf(
            BuiltinDescriptor(
                id = "okay_nabu",
                displayName = "Okay Nabu",
                tfliteUrl = "https://github.com/esphome/micro-wake-word-models/raw/main/models/v2/okay_nabu.tflite",
                jsonUrl = "https://raw.githubusercontent.com/esphome/micro-wake-word-models/main/models/v2/okay_nabu.json",
            ),
            BuiltinDescriptor(
                id = "hey_jarvis",
                displayName = "Hey Jarvis",
                tfliteUrl = "https://github.com/esphome/micro-wake-word-models/raw/main/models/v2/hey_jarvis.tflite",
                jsonUrl = "https://raw.githubusercontent.com/esphome/micro-wake-word-models/main/models/v2/hey_jarvis.json",
            ),
            BuiltinDescriptor(
                id = "hey_mycroft",
                displayName = "Hey Mycroft",
                tfliteUrl = "https://github.com/esphome/micro-wake-word-models/raw/main/models/v2/hey_mycroft.tflite",
                jsonUrl = "https://raw.githubusercontent.com/esphome/micro-wake-word-models/main/models/v2/hey_mycroft.json",
            ),
            BuiltinDescriptor(
                id = "hey_luna",
                displayName = "Hey Luna",
                tfliteUrl = "https://github.com/dreimer1986/micro-wake-word-models/raw/main/models/v2/hey_luna_v3.tflite",
                jsonUrl = "https://raw.githubusercontent.com/dreimer1986/micro-wake-word-models/main/models/v2/hey_luna_v3.json",
            ),
            BuiltinDescriptor(
                id = "hey_glados",
                displayName = "Hey GLaDOS",
                tfliteUrl = "https://github.com/Darkmadda/micro-wake-word-models/raw/main/models/v2/GlaDOS/hey_glados.tflite",
                jsonUrl = "https://raw.githubusercontent.com/Darkmadda/micro-wake-word-models/main/models/v2/GlaDOS/hey_glados.json",
            ),
        )

        fun mwwDir(context: Context): File = File(context.filesDir, ASSET_DIR_NAME)
        fun modelFile(context: Context, id: String): File = File(mwwDir(context), "$id.tflite")
        fun configFile(context: Context, id: String): File = File(mwwDir(context), "$id.json")

        fun isBuiltin(id: String): Boolean = BUILTINS.any { it.id == id }

        fun loadFromDisk(context: Context, id: String): MicroWakeWordConfig {
            val cfg = configFile(context, id)
            val model = modelFile(context, id)
            val json = JSONObject(cfg.readText())
            val micro = json.getJSONObject("micro")
            val langs = json.optJSONArray("trained_languages")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()
            return MicroWakeWordConfig(
                id = id,
                wakeWord = json.getString("wake_word"),
                author = json.optString("author", ""),
                website = json.optString("website", ""),
                modelFile = model,
                trainedLanguages = langs,
                probabilityCutoff = micro.getDouble("probability_cutoff").toFloat(),
                featureStepSize = micro.getInt("feature_step_size"),
                slidingWindowSize = micro.getInt("sliding_window_size"),
            )
        }

        fun installBundledIfMissing(context: Context) {
            val dir = mwwDir(context).apply { mkdirs() }
            val mgr = context.assets
            val names = try {
                mgr.list(ASSET_DIR_NAME)?.toList() ?: emptyList()
            } catch (t: Throwable) {
                Log.w("MicroWakeWordConfig", "Cannot list bundled mww assets", t)
                return
            }
            names.forEach { name ->
                val out = File(dir, name)
                if (out.exists() && out.length() > 0L) return@forEach
                try {
                    mgr.open("$ASSET_DIR_NAME/$name").use { input ->
                        val tmp = File(dir, "$name.part")
                        tmp.outputStream().use { input.copyTo(it) }
                        if (!tmp.renameTo(out)) {
                            tmp.delete()
                            throw java.io.IOException("rename failed for $tmp")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("MicroWakeWordConfig", "Failed to install asset $name", t)
                }
            }
        }

        fun listAvailable(context: Context): List<MicroWakeWordConfig> {
            installBundledIfMissing(context)
            val dir = mwwDir(context)
            if (!dir.exists()) return emptyList()
            return dir.listFiles { f -> f.extension == "json" }
                ?.mapNotNull { f ->
                    val id = f.nameWithoutExtension
                    if (modelFile(context, id).exists()) {
                        try {
                            loadFromDisk(context, id)
                        } catch (t: Throwable) {
                            Log.w("MicroWakeWordConfig", "Skipping malformed model '$id'", t)
                            null
                        }
                    } else null
                }
                ?.sortedBy { it.wakeWord }
                ?: emptyList()
        }

        fun slugify(s: String): String =
            s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "model" }
    }
}
