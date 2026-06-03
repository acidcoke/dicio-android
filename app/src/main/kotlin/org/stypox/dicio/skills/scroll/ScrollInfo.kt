package org.stypox.dicio.skills.scroll

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object ScrollInfo : SkillInfo("scroll") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_scroll)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_scroll)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.SwipeVertical)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Scroll[ctx.sentencesLanguage] ?: return null
        return ScrollSkill(ScrollInfo, data)
    }
}
