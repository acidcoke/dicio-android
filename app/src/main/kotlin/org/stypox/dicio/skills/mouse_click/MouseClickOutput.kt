package org.stypox.dicio.skills.mouse_click

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

sealed interface MouseClickOutput : HeadlineSpeechSkillOutput {
    data object Clicked : MouseClickOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_mouse_click_clicked)
    }

    data object NoPosition : MouseClickOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_mouse_click_no_position)
    }

    data object ServiceDisabled : MouseClickOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_mouse_click_service_disabled)
    }
}
