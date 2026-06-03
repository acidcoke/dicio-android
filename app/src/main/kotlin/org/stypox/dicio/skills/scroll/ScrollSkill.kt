package org.stypox.dicio.skills.scroll

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Scroll
import org.stypox.dicio.voiceaccess.SwipeDirection
import org.stypox.dicio.voiceaccess.VoiceAccessService

class ScrollSkill(correspondingSkillInfo: SkillInfo, data: StandardRecognizerData<Scroll>) :
    StandardRecognizerSkill<Scroll>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Scroll): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return ScrollOutput(isSwipe = false, direction = SwipeDirection.UP, available = false)

        val (isSwipe, direction) = when (inputData) {
            Scroll.ScrollUp -> false to SwipeDirection.UP
            Scroll.ScrollDown -> false to SwipeDirection.DOWN
            Scroll.ScrollLeft -> false to SwipeDirection.LEFT
            Scroll.ScrollRight -> false to SwipeDirection.RIGHT
            Scroll.SwipeUp -> true to SwipeDirection.UP
            Scroll.SwipeDown -> true to SwipeDirection.DOWN
            Scroll.SwipeLeft -> true to SwipeDirection.LEFT
            Scroll.SwipeRight -> true to SwipeDirection.RIGHT
        }

        if (isSwipe) service.swipe(direction) else service.scroll(direction)
        return ScrollOutput(isSwipe = isSwipe, direction = direction, available = true)
    }
}
