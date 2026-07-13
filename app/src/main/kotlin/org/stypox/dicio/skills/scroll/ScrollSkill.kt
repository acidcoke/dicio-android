package org.stypox.dicio.skills.scroll

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Scroll
import org.stypox.dicio.settings.datastore.ScrollAmount
import org.stypox.dicio.voiceaccess.SwipeDirection
import org.stypox.dicio.voiceaccess.VoiceAccessService

class ScrollSkill(private val info: ScrollInfo, data: StandardRecognizerData<Scroll>) :
    StandardRecognizerSkill<Scroll>(info, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Scroll): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return ScrollOutput(isSwipe = false, direction = SwipeDirection.UP, available = false)

        val (isSwipe, isFlick, direction) = when (inputData) {
            Scroll.ScrollUp -> Triple(false, false, SwipeDirection.UP)
            Scroll.ScrollDown -> Triple(false, false, SwipeDirection.DOWN)
            Scroll.ScrollLeft -> Triple(false, false, SwipeDirection.LEFT)
            Scroll.ScrollRight -> Triple(false, false, SwipeDirection.RIGHT)
            Scroll.SwipeUp -> Triple(true, false, SwipeDirection.UP)
            Scroll.SwipeDown -> Triple(true, false, SwipeDirection.DOWN)
            Scroll.SwipeLeft -> Triple(true, false, SwipeDirection.LEFT)
            Scroll.SwipeRight -> Triple(true, false, SwipeDirection.RIGHT)
            Scroll.FlickUp -> Triple(true, true, SwipeDirection.UP)
            Scroll.FlickDown -> Triple(true, true, SwipeDirection.DOWN)
            Scroll.FlickLeft -> Triple(true, true, SwipeDirection.LEFT)
            Scroll.FlickRight -> Triple(true, true, SwipeDirection.RIGHT)
        }

        // flick always moves at the old scroll command's high speed, regardless of the configured
        // scroll distance setting
        val fraction = if (isFlick) ScrollInfo.fractionFor(ScrollAmount.SCROLL_AMOUNT_LONG)
            else info.swipeFraction
        if (isSwipe) service.swipe(direction, fraction) else service.scroll(direction, fraction)
        return ScrollOutput(isSwipe = isSwipe, direction = direction, available = true)
    }
}
