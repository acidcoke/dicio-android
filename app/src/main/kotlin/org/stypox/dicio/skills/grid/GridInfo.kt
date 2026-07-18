package org.stypox.dicio.skills.grid

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object GridInfo : SkillInfo("grid") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_grid)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_grid)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Grid4x4)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Grid[ctx.sentencesLanguage] ?: return null
        return GridSkill(GridInfo, data)
    }
}
