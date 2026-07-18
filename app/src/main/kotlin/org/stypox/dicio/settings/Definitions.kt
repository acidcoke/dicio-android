package org.stypox.dicio.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BreakfastDining
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.stypox.dicio.R
import org.stypox.dicio.io.wake.mww.MicroWakeWordConfig
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.Language
import org.stypox.dicio.settings.datastore.LabelTheme
import org.stypox.dicio.settings.datastore.ListeningDuration
import org.stypox.dicio.settings.datastore.NumberSelectionMode
import org.stypox.dicio.settings.datastore.ScrollAmount
import org.stypox.dicio.settings.datastore.SpeechOutputDevice
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.Theme
import org.stypox.dicio.settings.datastore.WakeDevice
import org.stypox.dicio.settings.ui.BooleanSetting
import org.stypox.dicio.settings.ui.IntSetting
import org.stypox.dicio.settings.ui.ListSetting


@Composable
fun languageSetting() = ListSetting(
    title = stringResource(R.string.pref_language),
    icon = Icons.Default.Language,
    description = stringResource(R.string.pref_language_summary),
    possibleValues = listOf(
        ListSetting.Value(Language.LANGUAGE_SYSTEM, stringResource(R.string.pref_language_system)),
        ListSetting.Value(Language.LANGUAGE_CS, "Čeština"),
        ListSetting.Value(Language.LANGUAGE_DE, "Deutsch"),
        ListSetting.Value(Language.LANGUAGE_EN, "English"),
        ListSetting.Value(Language.LANGUAGE_EN_IN, "English (India)"),
        ListSetting.Value(Language.LANGUAGE_ES, "Español"),
        ListSetting.Value(Language.LANGUAGE_EL, "Ελληνικά"),
        ListSetting.Value(Language.LANGUAGE_FR, "Français"),
        ListSetting.Value(Language.LANGUAGE_IT, "Italiano"),
        ListSetting.Value(Language.LANGUAGE_NL, "Nederlands"),
        ListSetting.Value(Language.LANGUAGE_PL, "Polski"),
        ListSetting.Value(Language.LANGUAGE_RU, "Русский"),
        ListSetting.Value(Language.LANGUAGE_SL, "Slovenščina"),
        ListSetting.Value(Language.LANGUAGE_SV, "Svenska"),
        ListSetting.Value(Language.LANGUAGE_UK, "Українська"),
        ListSetting.Value(Language.LANGUAGE_TR, "Türkçe"),
    ),
)

@Composable
fun themeSetting() = ListSetting(
    title = stringResource(R.string.pref_theme),
    icon = Icons.Default.ColorLens,
    description = stringResource(R.string.pref_theme_summary),
    possibleValues = listOf(
        ListSetting.Value(
            value = Theme.THEME_SYSTEM,
            name = stringResource(R.string.pref_theme_system),
            icon = Icons.Default.PhoneAndroid,
        ),
        ListSetting.Value(
            value = Theme.THEME_SYSTEM_DARK_BLACK,
            name = stringResource(R.string.pref_theme_system_dark_black),
            icon = Icons.Default.PhoneAndroid,
        ),
        ListSetting.Value(
            value = Theme.THEME_LIGHT,
            name = stringResource(R.string.pref_theme_light),
            icon = Icons.Default.LightMode,
        ),
        ListSetting.Value(
            value = Theme.THEME_DARK,
            name = stringResource(R.string.pref_theme_dark),
            icon = Icons.Default.Cloud,
        ),
        ListSetting.Value(
            value = Theme.THEME_BLACK,
            name = stringResource(R.string.pref_theme_black),
            icon = Icons.Default.DarkMode,
        ),
    ),
)

@Composable
fun dynamicColors() = BooleanSetting(
    title = stringResource(R.string.pref_dynamic_colors_title),
    icon = Icons.Default.InvertColors,
    descriptionOff = stringResource(R.string.pref_dynamic_colors_summary),
    descriptionOn = stringResource(R.string.pref_dynamic_colors_summary),
)

@Composable
fun inputDevice() = ListSetting(
    title = stringResource(R.string.pref_input_method),
    icon = Icons.Default.Mic,
    description = stringResource(R.string.pref_input_method_summary),
    possibleValues = listOf(
        ListSetting.Value(
            value = InputDevice.INPUT_DEVICE_VOSK,
            name = stringResource(R.string.pref_input_method_vosk),
            description = stringResource(R.string.pref_input_method_vosk_summary),
            icon = Icons.Default.Mic,
        ),
        ListSetting.Value(
            value = InputDevice.INPUT_DEVICE_EXTERNAL_POPUP,
            name = stringResource(R.string.pref_input_method_external_popup),
            description = stringResource(R.string.pref_input_method_external_popup_summary),
            icon = Icons.Default.PictureInPictureAlt,
        ),
        ListSetting.Value(
            value = InputDevice.INPUT_DEVICE_NOTHING,
            name = stringResource(R.string.pref_input_method_text),
            icon = Icons.Default.KeyboardAlt,
        ),
    ),
)

@Composable
fun wakeDevice() = ListSetting(
    title = stringResource(R.string.pref_wake_method),
    icon = Icons.Default.Hearing,
    description = stringResource(R.string.pref_wake_method_summary),
    possibleValues = listOf(
        ListSetting.Value(
            value = WakeDevice.WAKE_DEVICE_OWW,
            name = stringResource(R.string.pref_wake_method_openwakeword),
        ),
        ListSetting.Value(
            value = WakeDevice.WAKE_DEVICE_MWW,
            name = stringResource(R.string.pref_wake_method_microwakeword),
        ),
        ListSetting.Value(
            value = WakeDevice.WAKE_DEVICE_NOTHING,
            name = stringResource(R.string.pref_wake_method_disabled),
        )
    )
)

@Composable
fun mwwModel(configs: List<MicroWakeWordConfig>) = ListSetting(
    title = stringResource(R.string.pref_mww_model),
    icon = Icons.Default.RecordVoiceOver,
    description = stringResource(R.string.pref_mww_model_summary),
    possibleValues = buildList {
        val onDiskIds = configs.mapTo(mutableSetOf()) { it.id }
        configs.forEach { cfg ->
            add(
                ListSetting.Value(
                    value = cfg.id,
                    name = cfg.wakeWord,
                    description = cfg.trainedLanguages.joinToString(", ").ifBlank { null },
                )
            )
        }
        MicroWakeWordConfig.BUILTINS.forEach { b ->
            if (b.id !in onDiskIds) {
                add(
                    ListSetting.Value(
                        value = b.id,
                        name = b.displayName,
                    )
                )
            }
        }
    },
)

@Composable
fun speechOutputDevice() = ListSetting(
    title = stringResource(R.string.pref_speech_output_method),
    icon = Icons.Default.SpeakerPhone,
    description = stringResource(R.string.pref_speech_output_method_summary),
    possibleValues = listOf(
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_ANDROID_TTS,
            name = stringResource(R.string.pref_speech_output_method_android),
            icon = Icons.Default.SpeakerPhone,
        ),
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_TOAST,
            name = stringResource(R.string.pref_speech_output_method_toast),
            icon = Icons.Default.BreakfastDining,
        ),
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_SNACKBAR,
            name = stringResource(R.string.pref_speech_output_method_snackbar),
            icon = Icons.Default.Minimize,
        ),
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_NOTHING,
            name = stringResource(R.string.pref_speech_output_method_nothing),
        ),
    ),
)

@Composable
fun sttSilenceDuration() = IntSetting(
    title = stringResource(R.string.pref_stt_silence_duration_title),
    icon = Icons.Default.HourglassEmpty,
    description = @Composable { stringResource(R.string.pref_stt_silence_duration_description, it) },
    minimum = 1,
    maximum = 7,
)

@Composable
fun sttAutoFinish() = BooleanSetting(
    title = stringResource(R.string.pref_stt_auto_finish_title),
    icon = Icons.AutoMirrored.Filled.Send,
    descriptionOff = stringResource(R.string.pref_stt_auto_finish_summary_off),
    descriptionOn = stringResource(R.string.pref_stt_auto_finish_summary_on),
)

@Composable
fun numberSelectionMode() = ListSetting(
    title = stringResource(R.string.pref_number_selection_mode_title),
    icon = Icons.Default.TouchApp,
    description = stringResource(R.string.pref_number_selection_mode_summary),
    possibleValues = listOf(
        ListSetting.Value(
            value = NumberSelectionMode.NUMBER_SELECTION_MODE_EXPLICIT_AND_BARE,
            name = stringResource(R.string.pref_number_selection_mode_explicit_and_bare),
        ),
        ListSetting.Value(
            value = NumberSelectionMode.NUMBER_SELECTION_MODE_EXPLICIT_ONLY,
            name = stringResource(R.string.pref_number_selection_mode_explicit_only),
        ),
    ),
)

@Composable
fun scrollAmount() = ListSetting(
    title = stringResource(R.string.pref_scroll_amount_title),
    icon = Icons.Default.SwipeVertical,
    description = stringResource(R.string.pref_scroll_amount_summary),
    possibleValues = listOf(
        ListSetting.Value(
            value = ScrollAmount.SCROLL_AMOUNT_SHORT,
            name = stringResource(R.string.pref_scroll_amount_short),
        ),
        ListSetting.Value(
            value = ScrollAmount.SCROLL_AMOUNT_MEDIUM,
            name = stringResource(R.string.pref_scroll_amount_medium),
        ),
        ListSetting.Value(
            value = ScrollAmount.SCROLL_AMOUNT_LONG,
            name = stringResource(R.string.pref_scroll_amount_long),
        ),
    ),
)

@Composable
fun labelOpacity() = IntSetting(
    title = stringResource(R.string.pref_label_opacity_title),
    icon = Icons.Default.Opacity,
    description = @Composable { stringResource(R.string.pref_label_opacity_description, it) },
    minimum = 20,
    maximum = 100,
)

@Composable
fun gridOpacity() = IntSetting(
    title = stringResource(R.string.pref_grid_opacity_title),
    icon = Icons.Default.Grid4x4,
    description = @Composable { stringResource(R.string.pref_grid_opacity_description, it) },
    minimum = 20,
    maximum = 100,
)

@Composable
fun labelContrast() = IntSetting(
    title = stringResource(R.string.pref_label_contrast_title),
    icon = Icons.Default.Contrast,
    description = @Composable { stringResource(R.string.pref_label_contrast_description, it) },
    minimum = 10,
    maximum = 100,
)

@Composable
fun listeningDuration() = ListSetting(
    title = stringResource(R.string.pref_listening_duration_title),
    icon = Icons.Default.Timer,
    description = stringResource(R.string.pref_listening_duration_summary),
    possibleValues = listOf(
        ListSetting.Value(
            value = ListeningDuration.LISTENING_DURATION_TIMEOUT_30S,
            name = stringResource(R.string.pref_listening_duration_timeout_30s),
        ),
        ListSetting.Value(
            value = ListeningDuration.LISTENING_DURATION_UNTIL_SCREEN_OFF,
            name = stringResource(R.string.pref_listening_duration_until_screen_off),
        ),
    ),
)

@Composable
fun invalidCommandsBeforePrompt() = IntSetting(
    title = stringResource(R.string.pref_invalid_commands_before_prompt_title),
    icon = Icons.Default.Timer,
    description = @Composable {
        stringResource(R.string.pref_invalid_commands_before_prompt_description, it)
    },
    minimum = 1,
    maximum = 7,
)

@Composable
fun labelTheme() = ListSetting(
    title = stringResource(R.string.pref_label_theme_title),
    icon = Icons.Default.DarkMode,
    description = stringResource(R.string.pref_label_theme_summary),
    possibleValues = listOf(
        ListSetting.Value(
            value = LabelTheme.LABEL_THEME_DARK,
            name = stringResource(R.string.pref_label_theme_dark),
        ),
        ListSetting.Value(
            value = LabelTheme.LABEL_THEME_LIGHT,
            name = stringResource(R.string.pref_label_theme_light),
        ),
    ),
)

@Composable
fun sttPlaySound() = ListSetting(
    title = stringResource(R.string.pref_stt_play_sound_title),
    icon = Icons.Default.Campaign,
    description = stringResource(R.string.pref_stt_play_sound_summary),
    possibleValues = listOf(
        ListSetting.Value(
            value = SttPlaySound.STT_PLAY_SOUND_NOTIFICATION,
            name = stringResource(R.string.pref_stt_play_sound_notification),
            icon = Icons.Default.Notifications,
        ),
        ListSetting.Value(
            value = SttPlaySound.STT_PLAY_SOUND_ALARM,
            name = stringResource(R.string.pref_stt_play_sound_alarm),
            icon = Icons.Default.Alarm,
        ),
        ListSetting.Value(
            value = SttPlaySound.STT_PLAY_SOUND_MEDIA,
            name = stringResource(R.string.pref_stt_play_sound_media),
            icon = Icons.Default.MusicNote,
        ),
        ListSetting.Value(
            value = SttPlaySound.STT_PLAY_SOUND_NONE,
            name = stringResource(R.string.pref_stt_play_sound_none),
        ),
    ),
)
