package org.stypox.dicio.skills.notifications

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

class NotificationsOutput(private val shown: Boolean) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        if (shown) ctx.getString(R.string.skill_notifications_shown)
        else ctx.getString(R.string.skill_notifications_service_disabled)
}
