package org.stypox.dicio.skills.notifications

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object NotificationsInfo : SkillInfo("notifications") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_notifications)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_notifications)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Notifications)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Notifications[ctx.sentencesLanguage] ?: return null
        return NotificationsSkill(NotificationsInfo, data)
    }
}
