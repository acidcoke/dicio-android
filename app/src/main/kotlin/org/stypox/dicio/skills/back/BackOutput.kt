package org.stypox.dicio.skills.back

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

class BackOutput(private val result: Result) : HeadlineSpeechSkillOutput {
    enum class Result { WENT_BACK, WENT_HOME, SERVICE_DISABLED }

    override fun getSpeechOutput(ctx: SkillContext): String = when (result) {
        Result.WENT_BACK -> ctx.getString(R.string.skill_back_went_back)
        Result.WENT_HOME -> ctx.getString(R.string.skill_back_went_home)
        Result.SERVICE_DISABLED -> ctx.getString(R.string.skill_back_service_disabled)
    }
}
