package org.stypox.dicio.skills.pin_key

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object PinKeyInfo : SkillInfo("pin_key") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_pin_key)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_pin_key)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Lock)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.PinKey[ctx.sentencesLanguage] ?: return null
        return PinKeySkill(PinKeyInfo, data)
    }
}
