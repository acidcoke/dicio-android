package org.stypox.dicio.skills.scroll

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString
import org.stypox.dicio.voiceaccess.SwipeDirection

class ScrollOutput(
    private val isSwipe: Boolean,
    private val direction: SwipeDirection,
    private val available: Boolean,
) : HeadlineSpeechSkillOutput {

    override fun getSpeechOutput(ctx: SkillContext): String {
        if (!available) return ctx.getString(R.string.skill_scroll_service_disabled)
        val dir = ctx.getString(
            when (direction) {
                SwipeDirection.UP -> R.string.direction_up
                SwipeDirection.DOWN -> R.string.direction_down
                SwipeDirection.LEFT -> R.string.direction_left
                SwipeDirection.RIGHT -> R.string.direction_right
            }
        )
        return ctx.getString(
            if (isSwipe) R.string.skill_scroll_swiped else R.string.skill_scroll_scrolled,
            dir,
        )
    }
}
