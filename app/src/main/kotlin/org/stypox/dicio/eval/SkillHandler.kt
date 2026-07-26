package org.stypox.dicio.eval

import android.content.Context
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillGrammar
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.fallback.text.TextFallbackInfo
import org.stypox.dicio.skills.listening.ListeningInfo
import org.stypox.dicio.skills.lyrics.LyricsInfo
import org.stypox.dicio.skills.media.MediaInfo
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.notify.NotifyInfo
import org.stypox.dicio.skills.open.OpenInfo
import org.stypox.dicio.skills.search.SearchInfo
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.timer.TimerInfo
import org.stypox.dicio.skills.translation.TranslationInfo
import org.stypox.dicio.skills.weather.WeatherInfo
import org.stypox.dicio.skills.joke.JokeInfo
import org.stypox.dicio.skills.flashlight.FlashlightInfo
import org.stypox.dicio.skills.back.BackInfo
import org.stypox.dicio.skills.grid.GridInfo
import org.stypox.dicio.skills.zoom.ZoomInfo
import org.stypox.dicio.skills.labels.LabelsInfo
import org.stypox.dicio.skills.click_number.ClickNumberInfo
import org.stypox.dicio.skills.mouse_click.MouseClickInfo
import org.stypox.dicio.skills.stop_listening.StopListeningInfo
import org.stypox.dicio.skills.scroll.ScrollInfo
import org.stypox.dicio.skills.notifications.NotificationsInfo
import org.stypox.dicio.skills.quick_settings.QuickSettingsInfo
import org.stypox.dicio.skills.pin_key.PinKeyInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillHandler @Inject constructor(
    private val dataStore: DataStore<UserSettings>,
    private val localeManager: LocaleManager,
    private val skillContext: SkillContextInternal,
) {
    // TODO improve id handling (maybe just use an int that can point to an Android resource)
    val allSkillInfoList = listOf(
        WeatherInfo,
        SearchInfo,
        LyricsInfo,
        OpenInfo,
        CalculatorInfo,
        NavigationInfo,
        TelephoneInfo,
        TimerInfo,
        CurrentTimeInfo,
        MediaInfo,
        JokeInfo,
        ListeningInfo(dataStore),
        TranslationInfo,
        NotifyInfo,
        FlashlightInfo,
        BackInfo,
        LabelsInfo,
        GridInfo,
        ZoomInfo,
        ClickNumberInfo(dataStore),
        MouseClickInfo,
        StopListeningInfo,
        ScrollInfo(dataStore),
        NotificationsInfo,
        QuickSettingsInfo,
        PinKeyInfo,
    )

    private val fallbackSkillInfoList = listOf(
        TextFallbackInfo,
    )

    private val scope = CoroutineScope(Dispatchers.Default)

    // will be null when it has not been initialized yet
    private val _enabledSkillsInfo: MutableStateFlow<List<SkillInfo>?> = MutableStateFlow(null)
    val enabledSkillsInfo: StateFlow<List<SkillInfo>?> = _enabledSkillsInfo

    private val _skillRanker = MutableStateFlow(
        // an initial dummy value, will be overwritten directly by the launched job
        SkillRanker(listOf(), fallbackSkillInfoList[0].build(skillContext)!!)
    )
    val skillRanker: StateFlow<SkillRanker> = _skillRanker

    /**
     * The command phrases the currently enabled skills need a speech recognizer to be able to hear,
     * used to constrain recognition to a closed grammar. A disabled skill contributes nothing, so
     * its words can't pull recognitions away from the skills the user actually kept.
     */
    private val _skillGrammar = MutableStateFlow(SkillGrammar.EMPTY)
    val skillGrammar: StateFlow<SkillGrammar> = _skillGrammar

    init {
        scope.launch {
            localeManager.locale
                .combine(dataStore.data) { locale, data -> Pair(locale, data.enabledSkillsMap) }
                .distinctUntilChanged()
                .collectLatest { (_, enabledSkills) ->
                    // locale is not used here, because the skills directly use the sections locale

                    val newEnabledSkillsInfo =
                        buildEnabledSkills(allSkillInfoList, enabledSkills, skillContext)

                    _enabledSkillsInfo.value = newEnabledSkillsInfo.map { (info, _skill) -> info }
                    _skillRanker.value = SkillRanker(
                        newEnabledSkillsInfo.map { (_info, skill) -> skill },
                        fallbackSkillInfoList[0].build(skillContext)!!,
                    )
                    _skillGrammar.value = mergeGrammar(
                        newEnabledSkillsInfo.map { (_info, skill) -> skill },
                        skillContext.sentencesLanguage,
                    )
                }
        }
    }

    companion object {
        /**
         * Builds the skills the user kept enabled, dropping the ones that are unavailable in the
         * current language (their [SkillInfo.build] returns `null`). A skill missing from
         * [enabledSkills] counts as enabled.
         */
        internal fun buildEnabledSkills(
            allSkillInfoList: List<SkillInfo>,
            enabledSkills: Map<String, Boolean>,
            ctx: SkillContext,
        ): List<Pair<SkillInfo, Skill<*>>> = allSkillInfoList
            .filter { enabledSkills.getOrDefault(it.id, true) }
            .mapNotNull { info -> info.build(ctx)?.let { skill -> Pair(info, skill) } }

        /**
         * The phrases a speech recognizer must be able to hear for [skills] to work, plus the
         * continue/stop answers, which [org.stypox.dicio.io.wake.WakeService] scores regardless of
         * which skills are enabled and which therefore always have to be recognizable.
         */
        internal fun mergeGrammar(skills: List<Skill<*>>, language: String): SkillGrammar =
            SkillGrammar.merge(
                listOf(Sentences.Confirmation[language]?.grammar ?: SkillGrammar.EMPTY) +
                        skills.map { it.grammar }
            )

        fun newForPreviews(context: Context): SkillHandler {
            return SkillHandler(
                UserSettingsModule.newDataStoreForPreviews(),
                LocaleManager.newForPreviews(context),
                SkillContextImpl.newForPreviews(context),
            )
        }
    }
}
