package org.stypox.dicio.skills.click_number

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

sealed interface ClickNumberOutput : HeadlineSpeechSkillOutput {
    data class Tapped(private val number: Int) : ClickNumberOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_click_number_tapped, number)
    }

    data class Held(private val number: Int) : ClickNumberOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_click_number_held, number)
    }

    data class NoLabel(private val number: Int) : ClickNumberOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_click_number_no_label, number)
    }

    data object CouldNotUnderstand : ClickNumberOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_click_number_could_not_understand)
    }

    data object ServiceDisabled : ClickNumberOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_click_number_service_disabled)
    }
}
