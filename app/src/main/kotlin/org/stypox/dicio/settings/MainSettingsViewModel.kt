package org.stypox.dicio.settings

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.stypox.dicio.R
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.io.wake.mww.MicroWakeWordConfig
import org.stypox.dicio.io.wake.mww.MicroWakeWordDevice
import org.stypox.dicio.io.wake.oww.OpenWakeWordDevice
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.Language
import org.stypox.dicio.settings.datastore.LabelTheme
import org.stypox.dicio.settings.datastore.NumberSelectionMode
import org.stypox.dicio.settings.datastore.ScrollAmount
import org.stypox.dicio.settings.datastore.SpeechOutputDevice
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.Theme
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.WakeDevice
import org.stypox.dicio.util.toStateFlowDistinctBlockingFirst
import java.io.File
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class MainSettingsViewModel @Inject constructor(
    application: Application,
    private val wakeDeviceWrapper: WakeDeviceWrapper?,
    private val dataStore: DataStore<UserSettings>
) : AndroidViewModel(application) {
    // run blocking because the settings screen cannot start if settings have not been loaded yet
    val settingsState = dataStore.data
        .toStateFlowDistinctBlockingFirst(viewModelScope)

    private fun updateData(transform: (UserSettings.Builder) -> Unit) {
        viewModelScope.launch {
            dataStore.updateData {
                it.toBuilder()
                    .apply(transform)
                    .build()
            }
        }
    }

    val isHeyDicio: StateFlow<Boolean> = wakeDeviceWrapper?.isHeyDicio ?: MutableStateFlow(true)

    private val _mwwConfigs = MutableStateFlow<List<MicroWakeWordConfig>>(emptyList())
    val mwwConfigs: StateFlow<List<MicroWakeWordConfig>> = _mwwConfigs

    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userMessages: SharedFlow<String> = _userMessages

    init {
        viewModelScope.launch { refreshMwwConfigs() }
    }

    private suspend fun refreshMwwConfigs() {
        val configs = withContext(Dispatchers.IO) {
            MicroWakeWordConfig.listAvailable(getApplication())
        }
        _mwwConfigs.value = configs
    }

    fun addMwwUserModel(tflite: Uri, json: Uri) {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val newId = withContext(Dispatchers.IO) {
                    val tmpJson = File.createTempFile("mww_user", ".json", ctx.cacheDir)
                    try {
                        ctx.contentResolver.openInputStream(json)?.use { input ->
                            tmpJson.outputStream().use { input.copyTo(it) }
                        } ?: throw IOException("Cannot read selected JSON file")
                        val parsed = JSONObject(tmpJson.readText())
                        val wakeWord = parsed.optString("wake_word", "Custom")
                        val baseId = MicroWakeWordConfig.slugify(wakeWord)
                        val id = uniqueModelId(baseId)
                        MicroWakeWordDevice.addUserModel(ctx, id, tflite, json)
                        id
                    } finally {
                        tmpJson.delete()
                    }
                }
                refreshMwwConfigs()
                wakeDeviceWrapper?.reinitialize()
                updateData { it.setMwwModel(newId) }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                _userMessages.tryEmit(
                    getApplication<Application>().getString(
                        R.string.mww_import_failed,
                        t.localizedMessage ?: t.javaClass.simpleName,
                    )
                )
            }
        }
    }

    private fun uniqueModelId(base: String): String {
        val ctx = getApplication<Application>()
        if (!MicroWakeWordConfig.configFile(ctx, base).exists() &&
            !MicroWakeWordConfig.modelFile(ctx, base).exists() &&
            !MicroWakeWordConfig.isBuiltin(base)
        ) return base
        var i = 2
        while (true) {
            val cand = "${base}_$i"
            if (!MicroWakeWordConfig.configFile(ctx, cand).exists() &&
                !MicroWakeWordConfig.modelFile(ctx, cand).exists() &&
                !MicroWakeWordConfig.isBuiltin(cand)
            ) return cand
            i++
        }
    }

    fun removeMwwUserModel(modelId: String) {
        viewModelScope.launch {
            MicroWakeWordDevice.removeUserModel(getApplication(), modelId)
            refreshMwwConfigs()
            // If the deleted model was selected, fall back to default
            if (settingsState.value.mwwModel == modelId) {
                updateData { it.setMwwModel(MicroWakeWordConfig.DEFAULT_ID) }
            }
            wakeDeviceWrapper?.reinitialize()
        }
    }

    fun setMwwModel(value: String) =
        updateData { it.setMwwModel(value) }

    fun addOwwUserWakeFile(uri: Uri) {
        viewModelScope.launch {
            OpenWakeWordDevice.addUserWakeFile(getApplication(), uri)
            wakeDeviceWrapper?.reinitialize()
        }
    }

    fun removeOwwUserWakeFile() {
        viewModelScope.launch {
            OpenWakeWordDevice.removeUserWakeFile(getApplication())
            wakeDeviceWrapper?.reinitialize()
        }
    }

    fun setLanguage(value: Language) =
        updateData { it.setLanguage(value) }
    fun setTheme(value: Theme) =
        updateData { it.setTheme(value) }
    fun setDynamicColors(value: Boolean) =
        updateData { it.setDynamicColors(value) }
    fun setInputDevice(value: InputDevice) =
        updateData { it.setInputDevice(value) }
    fun setWakeDevice(value: WakeDevice) =
        updateData { it.setWakeDevice(value) }
    fun setSpeechOutputDevice(value: SpeechOutputDevice) =
        updateData { it.setSpeechOutputDevice(value) }
    fun setSttPlaySound(value: SttPlaySound) =
        updateData { it.setSttPlaySound(value) }
    fun setSttSilenceDuration(value: Int) =
        updateData { it.setSttSilenceDuration(value) }
    fun setAutoFinishSttPopup(value: Boolean) =
        updateData { it.setAutoFinishSttPopup(value) }
    fun setNumberSelectionMode(value: NumberSelectionMode) =
        updateData { it.setNumberSelectionMode(value) }
    fun setScrollAmount(value: ScrollAmount) =
        updateData { it.setScrollAmount(value) }
    fun setLabelOpacity(value: Int) =
        updateData { it.setLabelOpacity(value) }
    fun setLabelContrast(value: Int) =
        updateData { it.setLabelContrast(value) }
    fun setLabelTheme(value: LabelTheme) =
        updateData { it.setLabelTheme(value) }
}
