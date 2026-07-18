package org.stypox.dicio.io.wake.mww

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.io.wake.WakeDevice
import org.stypox.dicio.io.wake.WakeState
import org.stypox.dicio.microwakeword.MicroWakeWord
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class MicroWakeWordDevice(
    @param:ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val modelId: String,
) : WakeDevice {
    private val cacheDir: File = appContext.cacheDir
    private val mwwDir = MicroWakeWordConfig.mwwDir(appContext)
    private val tfliteFile = MicroWakeWordConfig.modelFile(appContext, modelId)
    private val jsonFile = MicroWakeWordConfig.configFile(appContext, modelId)

    private val builtin: MicroWakeWordConfig.BuiltinDescriptor? =
        MicroWakeWordConfig.BUILTINS.firstOrNull { it.id == modelId }

    private val downloadList: List<FileToDownload> = builtin?.let {
        listOf(
            FileToDownload(it.tfliteUrl, tfliteFile),
            FileToDownload(it.jsonUrl, jsonFile),
        )
    } ?: emptyList()

    private val _state: MutableStateFlow<WakeState>
    override val state: StateFlow<WakeState>

    private var mww: MicroWakeWord? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        MicroWakeWordConfig.installBundledIfMissing(appContext)
        _state = MutableStateFlow(
            if (filesMissing()) WakeState.NotDownloaded else WakeState.NotLoaded
        )
        state = _state
    }

    private fun filesMissing(): Boolean = !tfliteFile.exists() || !jsonFile.exists()

    override fun download() {
        if (downloadList.isEmpty()) {
            // user-imported model: nothing to download. If files exist, mark loadable.
            if (!filesMissing()) _state.value = WakeState.NotLoaded
            return
        }
        _state.value = WakeState.Downloading(Progress.UNKNOWN)
        scope.launch {
            try {
                mwwDir.mkdirs()
                downloadBinaryFilesWithPartial(
                    urlsFiles = downloadList,
                    httpClient = okHttpClient,
                    cacheDir = cacheDir,
                ) { progress -> _state.value = WakeState.Downloading(progress) }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "Can't download microWakeWord model", t)
                _state.value = WakeState.ErrorDownloading(t)
                return@launch
            }
            _state.value = WakeState.NotLoaded
        }
    }

    override fun frameSize(): Int = FRAME_SIZE

    override fun processFrame(audio16bitPcm: ShortArray): Boolean {
        if (audio16bitPcm.size != FRAME_SIZE) {
            throw IllegalArgumentException(
                "MicroWakeWordDevice expects $FRAME_SIZE-sample frames, got ${audio16bitPcm.size}"
            )
        }

        if (mww == null) {
            val cur = _state.value
            if (cur != WakeState.NotLoaded && cur !is WakeState.ErrorLoading) {
                throw IOException("microWakeWord model has not been downloaded yet")
            }
            try {
                _state.value = WakeState.Loading
                val cfg = MicroWakeWordConfig.loadFromDisk(appContext, modelId)
                val buf = mmapDirect(cfg.modelFile)
                mww = MicroWakeWord(
                    modelBuffer = buf,
                    featureStepSizeMs = cfg.featureStepSize,
                    probabilityCutoff = cfg.probabilityCutoff,
                    slidingWindowSize = cfg.slidingWindowSize,
                )
                _state.value = WakeState.Loaded
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load microWakeWord model", t)
                _state.value = WakeState.ErrorLoading(t)
                throw t
            }
        }

        val current = mww ?: return false
        val detected = current.processAudio(audio16bitPcm)
        if (detected) current.reset()
        return detected
    }

    override fun isOccupyingResources(): Boolean = mww != null

    override fun destroy() {
        mww?.close()
        mww = null
        scope.cancel()
    }

    override fun isHeyDicio(): Boolean = false

    private fun mmapDirect(file: File): ByteBuffer =
        RandomAccessFile(file, "r").use { raf ->
            raf.channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                    .order(ByteOrder.nativeOrder())
            }
        }

    companion object {
        private const val TAG = "MicroWakeWordDevice"

        // 100 ms @ 16 kHz; MicroWakeWord internally accumulates feature frames at 10 ms steps.
        const val FRAME_SIZE = 1600

        suspend fun addUserModel(
            context: Context,
            modelId: String,
            tfliteSource: Uri,
            jsonSource: Uri,
        ): MicroWakeWordConfig = withContext(Dispatchers.IO) {
            val dir = MicroWakeWordConfig.mwwDir(context).apply { mkdirs() }
            val tfliteOut = MicroWakeWordConfig.modelFile(context, modelId)
            val jsonOut = MicroWakeWordConfig.configFile(context, modelId)
            copyAtomically(context, tfliteSource, tfliteOut)
            try {
                copyAtomically(context, jsonSource, jsonOut)
                MicroWakeWordConfig.loadFromDisk(context, modelId)
            } catch (t: Throwable) {
                tfliteOut.delete()
                jsonOut.delete()
                throw t
            }
        }

        suspend fun removeUserModel(context: Context, modelId: String) =
            withContext(Dispatchers.IO) {
                MicroWakeWordConfig.modelFile(context, modelId).delete()
                MicroWakeWordConfig.configFile(context, modelId).delete()
            }

        private fun copyAtomically(context: Context, source: Uri, dest: File) {
            val partial = File.createTempFile(dest.name, ".part", context.cacheDir)
            val input = context.contentResolver.openInputStream(source)
                ?: throw IOException("Cannot open input stream for $source")
            input.use { inp -> partial.outputStream().use { inp.copyTo(it) } }
            dest.delete()
            dest.parentFile?.mkdirs()
            if (!partial.renameTo(dest)) {
                throw IOException("Cannot rename partial file $partial to $dest")
            }
        }
    }
}
