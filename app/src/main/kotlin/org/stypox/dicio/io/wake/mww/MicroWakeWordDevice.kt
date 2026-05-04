package org.stypox.dicio.io.wake.mww

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.stypox.dicio.io.wake.WakeDevice
import org.stypox.dicio.io.wake.WakeState
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MicroWakeWordDevice(
    private val appContext: Context,
    private val engineFactory: (ByteBuffer, MwwMicroConfig) -> MicroWakeWord = ::defaultEngineFactory,
) : WakeDevice {
    private val _state: MutableStateFlow<WakeState>
    override val state: StateFlow<WakeState>

    private val userModelFile = userModelFile(appContext)
    private val userManifestFile = userManifestFile(appContext)

    private var engine: MicroWakeWord? = null
    private var modelMapping: MappedByteBuffer? = null

    init {
        // Debug builds ship a pre-bundled "okay nabu" model + manifest under
        // src/debug/assets/microWakeWord/. Auto-install on first run so dev builds work
        // out of the box without manual import. Release builds don't ship the assets, so
        // this is a no-op there.
        seedBundledModelIfPresent(appContext, userModelFile, userManifestFile)

        _state = if (!userModelFile.exists()) {
            MutableStateFlow(WakeState.NotDownloaded)
        } else {
            MutableStateFlow(WakeState.NotLoaded)
        }
        state = _state
    }

    /**
     * microWakeWord has no curated download URL — users supply their own .tflite (and
     * optional .json manifest) via the Settings import flow. Surface a clear error so
     * the UI prompts them to import.
     */
    override fun download() {
        _state.value = WakeState.ErrorDownloading(
            IllegalStateException("Import a microWakeWord .tflite via Settings")
        )
    }

    override fun processFrame(audio16bitPcm: ShortArray): Boolean {
        if (audio16bitPcm.size != FRAME_SIZE) {
            throw IllegalArgumentException(
                "MicroWakeWordDevice can only process audio frames of $FRAME_SIZE samples"
            )
        }

        if (engine == null) {
            if (_state.value.let { it != WakeState.NotLoaded && it !is WakeState.ErrorLoading }) {
                throw IOException("Model has not been imported yet")
            }

            try {
                _state.value = WakeState.Loading
                val config = loadManifest(userManifestFile)
                Log.d(TAG, "Loading mWW with config: $config")
                val mapping = mapModelFile(userModelFile)
                modelMapping = mapping
                engine = engineFactory(mapping, config)
                _state.value = WakeState.Loaded
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load microWakeWord model", t)
                _state.value = WakeState.ErrorLoading(t)
                throw t
            }
        }

        val detected = engine!!.processAudio(audio16bitPcm)
        if (detected) {
            engine!!.reset()
        }
        return detected
    }

    override fun frameSize(): Int = FRAME_SIZE

    override fun isOccupyingResources(): Boolean = engine != null

    override fun destroy() {
        engine?.close()
        engine = null
        modelMapping = null
    }

    override fun isHeyDicio(): Boolean = false

    /**
     * Subset of the ESPHome microWakeWord JSON manifest the engine actually consumes.
     * Top-level "micro" object — see https://github.com/esphome/micro-wake-word-models.
     */
    @Serializable
    data class MwwMicroConfig(
        @SerialName("probability_cutoff") val probabilityCutoff: Float = 0.97f,
        @SerialName("feature_step_size") val featureStepSizeMs: Int = 20,
        @SerialName("sliding_window_size") val slidingWindowSize: Int = 10,
    )

    @Serializable
    private data class MwwManifest(
        val micro: MwwMicroConfig = MwwMicroConfig(),
    )

    companion object {
        val TAG: String = MicroWakeWordDevice::class.simpleName ?: "MicroWakeWordDevice"

        // 80 ms at 16 kHz. Matches OwwModel.MEL_INPUT_COUNT so switching backends doesn't
        // disturb WakeService's audio loop.
        const val FRAME_SIZE = 1280

        private const val BUNDLED_MODEL_ASSET = "microWakeWord/okay_nabu.tflite"
        private const val BUNDLED_MANIFEST_ASSET = "microWakeWord/okay_nabu.json"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun userModelFile(context: Context) =
            File(context.filesDir, "microWakeWord/userwake.tflite")

        private fun userManifestFile(context: Context) =
            File(context.filesDir, "microWakeWord/userwake.json")

        fun userModelFileExists(context: Context): Boolean = userModelFile(context).exists()

        private fun loadManifest(manifestFile: File): MwwMicroConfig {
            if (!manifestFile.exists()) return MwwMicroConfig()
            return try {
                json.decodeFromString<MwwManifest>(manifestFile.readText()).micro
            } catch (t: Throwable) {
                Log.w(TAG, "Bad manifest at $manifestFile, using defaults", t)
                MwwMicroConfig()
            }
        }

        private fun seedBundledModelIfPresent(
            context: Context,
            modelTarget: File,
            manifestTarget: File,
        ) {
            if (modelTarget.exists()) return
            try {
                modelTarget.parentFile?.mkdirs()
                copyAssetTo(context, BUNDLED_MODEL_ASSET, modelTarget)
                copyAssetTo(context, BUNDLED_MANIFEST_ASSET, manifestTarget)
                Log.i(TAG, "Installed bundled microWakeWord model from assets")
            } catch (_: java.io.FileNotFoundException) {
                // Release builds don't ship the asset — leave the device in NotDownloaded state.
            } catch (t: Throwable) {
                Log.w(TAG, "Error seeding bundled microWakeWord model", t)
            }
        }

        private fun copyAssetTo(context: Context, asset: String, target: File) {
            context.assets.open(asset).use { input ->
                val partial = File.createTempFile(target.name, ".part", context.cacheDir)
                partial.outputStream().use { input.copyTo(it) }
                target.delete()
                if (!partial.renameTo(target)) {
                    partial.delete()
                    throw IOException("Cannot install asset $asset to $target")
                }
            }
        }

        private fun mapModelFile(file: File): MappedByteBuffer {
            return RandomAccessFile(file, "r").use { raf ->
                raf.channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                }
            }
        }

        private fun defaultEngineFactory(
            buffer: ByteBuffer,
            config: MwwMicroConfig,
        ): MicroWakeWord = MicroWakeWord(
            modelBuffer = buffer,
            featureStepSizeMs = config.featureStepSizeMs,
            probabilityCutoff = config.probabilityCutoff,
            slidingWindowSize = config.slidingWindowSize,
        )

        /**
         * Imports one or more user-supplied files (`.tflite` model, optional `.json`
         * manifest). Files are dispatched by display name suffix; non-matching files
         * are ignored. Existing user model + manifest are atomically replaced.
         *
         * If a `.tflite` is provided without a paired `.json`, any previous manifest is
         * deleted so engine config falls back to defaults.
         */
        suspend fun addUserModelFiles(context: Context, sources: List<Uri>) {
            withContext(Dispatchers.IO) {
                var modelUri: Uri? = null
                var manifestUri: Uri? = null
                for (uri in sources) {
                    when (classify(context, uri)) {
                        Kind.TFLITE -> modelUri = uri
                        Kind.JSON -> manifestUri = uri
                        Kind.UNKNOWN -> Log.w(TAG, "Ignoring unrecognized file: $uri")
                    }
                }
                if (modelUri == null) {
                    throw IOException("No .tflite file in selection")
                }
                copyUriTo(context, modelUri, userModelFile(context))
                val manifestFile = userManifestFile(context)
                if (manifestUri != null) {
                    copyUriTo(context, manifestUri, manifestFile)
                } else {
                    // No new manifest selected — drop the stale one so defaults apply.
                    manifestFile.delete()
                }
            }
        }

        suspend fun removeUserModelFile(context: Context) {
            withContext(Dispatchers.IO) {
                userModelFile(context).delete()
                userManifestFile(context).delete()
            }
        }

        private enum class Kind { TFLITE, JSON, UNKNOWN }

        private fun classify(context: Context, uri: Uri): Kind {
            val name = displayName(context, uri)?.lowercase() ?: return Kind.UNKNOWN
            return when {
                name.endsWith(".tflite") -> Kind.TFLITE
                name.endsWith(".json") -> Kind.JSON
                else -> Kind.UNKNOWN
            }
        }

        private fun displayName(context: Context, uri: Uri): String? {
            return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }

        private fun copyUriTo(context: Context, source: Uri, target: File) {
            val input = context.contentResolver.openInputStream(source)
                ?: throw IOException("Cannot open $source")
            input.use { src ->
                val partial = File.createTempFile(target.name, ".part", context.cacheDir)
                partial.outputStream().use { src.copyTo(it) }
                target.delete()
                target.parentFile?.mkdirs()
                if (!partial.renameTo(target)) {
                    partial.delete()
                    throw IOException("Cannot rename partial file $partial to $target")
                }
            }
        }
    }
}
