package org.stypox.dicio.skills.zoom

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object ZoomInfo : SkillInfo("zoom") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_zoom)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_zoom)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.ZoomIn)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Zoom[ctx.sentencesLanguage] ?: return null
        return ZoomSkill(ZoomInfo, data)
    }
}
