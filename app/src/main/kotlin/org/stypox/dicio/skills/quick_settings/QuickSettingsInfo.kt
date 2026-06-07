package org.stypox.dicio.skills.quick_settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object QuickSettingsInfo : SkillInfo("quick_settings") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_quick_settings)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_quick_settings)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Tune)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.QuickSettings[ctx.sentencesLanguage] ?: return null
        return QuickSettingsSkill(QuickSettingsInfo, data)
    }
}
