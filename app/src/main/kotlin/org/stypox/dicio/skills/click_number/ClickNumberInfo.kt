package org.stypox.dicio.skills.click_number

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.NumberSelectionMode
import org.stypox.dicio.settings.datastore.UserSettings

class ClickNumberInfo(val dataStore: DataStore<UserSettings>) : SkillInfo("click_number") {

    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Cached so that [ClickNumberSkill.score] (which is not a suspend function) can read it
     * synchronously. Kept up to date by collecting the settings data store.
     */
    @Volatile
    var numberSelectionMode: NumberSelectionMode = defaultMode
        private set

    init {
        scope.launch {
            dataStore.data
                .map { it.numberSelectionMode }
                .collect { mode -> numberSelectionMode = normalize(mode) }
        }
    }

    override fun name(context: Context) =
        context.getString(R.string.skill_name_click_number)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_click_number)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.TouchApp)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.ClickNumber[ctx.sentencesLanguage] ?: return null
        return ClickNumberSkill(this, data)
    }

    companion object {
        val defaultMode = NumberSelectionMode.NUMBER_SELECTION_MODE_EXPLICIT_AND_BARE

        /** Treat UNSET / unrecognized as the default ("mode 2"). */
        fun normalize(mode: NumberSelectionMode): NumberSelectionMode = when (mode) {
            NumberSelectionMode.NUMBER_SELECTION_MODE_EXPLICIT_ONLY,
            NumberSelectionMode.NUMBER_SELECTION_MODE_EXPLICIT_AND_BARE -> mode
            else -> defaultMode
        }
    }
}
