package org.stypox.dicio.skills.quick_settings

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

class QuickSettingsOutput(private val shown: Boolean) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        if (shown) ctx.getString(R.string.skill_quick_settings_shown)
        else ctx.getString(R.string.skill_quick_settings_service_disabled)
}
