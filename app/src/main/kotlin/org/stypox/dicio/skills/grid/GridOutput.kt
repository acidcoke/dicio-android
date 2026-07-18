package org.stypox.dicio.skills.grid

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

sealed interface GridOutput : HeadlineSpeechSkillOutput {
    data object Shown : GridOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_grid_shown)
    }

    data object Hidden : GridOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_grid_hidden)
    }

    data class Tapped(private val cell: String) : GridOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_grid_tapped, cell)
    }

    data class SubShown(private val cell: String) : GridOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_grid_sub_shown, cell)
    }

    data class OutOfRange(private val cell: String) : GridOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_grid_out_of_range, cell)
    }

    data object NotUnderstood : GridOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_grid_not_understood)
    }

    data object ServiceDisabled : GridOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_grid_service_disabled)
    }
}
