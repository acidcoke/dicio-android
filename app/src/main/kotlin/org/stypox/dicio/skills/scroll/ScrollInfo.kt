package org.stypox.dicio.skills.scroll

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwipeVertical
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
import org.stypox.dicio.settings.datastore.ScrollAmount
import org.stypox.dicio.settings.datastore.UserSettings

class ScrollInfo(val dataStore: DataStore<UserSettings>) : SkillInfo("scroll") {

    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * The configured scroll distance as a portion of the screen (0..1). Cached so the (non-suspend)
     * skill path can read it synchronously. Kept up to date by collecting the settings data store.
     */
    @Volatile
    var swipeFraction: Float = fractionFor(defaultAmount)
        private set

    init {
        scope.launch {
            dataStore.data
                .map { it.scrollAmount }
                .collect { amount -> swipeFraction = fractionFor(amount) }
        }
    }

    override fun name(context: Context) =
        context.getString(R.string.skill_name_scroll)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_scroll)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.SwipeVertical)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Scroll[ctx.sentencesLanguage] ?: return null
        return ScrollSkill(this, data)
    }

    companion object {
        val defaultAmount = ScrollAmount.SCROLL_AMOUNT_MEDIUM

        /** Treat UNSET / unrecognized as the default (MEDIUM). */
        fun normalize(amount: ScrollAmount): ScrollAmount = when (amount) {
            ScrollAmount.SCROLL_AMOUNT_SHORT,
            ScrollAmount.SCROLL_AMOUNT_MEDIUM,
            ScrollAmount.SCROLL_AMOUNT_LONG -> amount
            else -> defaultAmount
        }

        /** Swipe distance as a portion of the screen for each amount. */
        fun fractionFor(amount: ScrollAmount): Float = when (normalize(amount)) {
            ScrollAmount.SCROLL_AMOUNT_SHORT -> 0.25f
            ScrollAmount.SCROLL_AMOUNT_LONG -> 0.75f
            else -> 0.5f // MEDIUM
        }
    }
}
