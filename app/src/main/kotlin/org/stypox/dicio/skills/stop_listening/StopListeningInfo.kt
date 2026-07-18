package org.stypox.dicio.skills.stop_listening

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object StopListeningInfo : SkillInfo("stop_listening") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_stop_listening)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_stop_listening)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.MicOff)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.StopListening[ctx.sentencesLanguage] ?: return null
        return StopListeningSkill(StopListeningInfo, data)
    }
}
