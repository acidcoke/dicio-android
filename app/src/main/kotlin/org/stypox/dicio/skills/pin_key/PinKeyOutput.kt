package org.stypox.dicio.skills.pin_key

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

sealed interface PinKeyOutput : HeadlineSpeechSkillOutput {
    data object Pressed : PinKeyOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_pin_key_pressed)
    }

    data object NoKey : PinKeyOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_pin_key_no_key)
    }

    data object ServiceDisabled : PinKeyOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_pin_key_service_disabled)
    }
}
