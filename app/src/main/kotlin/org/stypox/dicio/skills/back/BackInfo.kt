package org.stypox.dicio.skills.back

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object BackInfo : SkillInfo("back") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_back)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_back)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Back[ctx.sentencesLanguage] ?: return null
        return BackSkill(BackInfo, data)
    }
}
