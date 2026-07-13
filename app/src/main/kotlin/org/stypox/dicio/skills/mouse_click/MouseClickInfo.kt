package org.stypox.dicio.skills.mouse_click

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object MouseClickInfo : SkillInfo("mouse_click") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_mouse_click)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_mouse_click)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Mouse)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.MouseClick[ctx.sentencesLanguage] ?: return null
        return MouseClickSkill(MouseClickInfo, data)
    }
}
