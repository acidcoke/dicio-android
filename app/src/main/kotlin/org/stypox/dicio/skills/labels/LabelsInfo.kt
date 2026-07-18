package org.stypox.dicio.skills.labels

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pin
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object LabelsInfo : SkillInfo("labels") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_labels)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_labels)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Pin)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Labels[ctx.sentencesLanguage] ?: return null
        return LabelsSkill(LabelsInfo, data)
    }
}
