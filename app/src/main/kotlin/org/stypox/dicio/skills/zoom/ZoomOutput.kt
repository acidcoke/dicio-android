package org.stypox.dicio.skills.zoom

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

sealed interface ZoomOutput : HeadlineSpeechSkillOutput {
    data class Zoomed(private val zoomIn: Boolean, private val cell: String?) : ZoomOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            if (cell == null) {
                ctx.getString(if (zoomIn) R.string.skill_zoom_in else R.string.skill_zoom_out)
            } else {
                ctx.getString(
                    if (zoomIn) R.string.skill_zoom_at_in else R.string.skill_zoom_at_out,
                    cell,
                )
            }
    }

    data class OutOfRange(private val cell: String) : ZoomOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_zoom_out_of_range, cell)
    }

    data object ServiceDisabled : ZoomOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_zoom_service_disabled)
    }

    data object NotUnderstood : ZoomOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_zoom_not_understood)
    }
}
