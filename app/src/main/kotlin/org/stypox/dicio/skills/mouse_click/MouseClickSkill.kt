package org.stypox.dicio.skills.mouse_click

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.MouseClick
import org.stypox.dicio.voiceaccess.VoiceAccessService

class MouseClickSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<MouseClick>,
) : StandardRecognizerSkill<MouseClick>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: MouseClick): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return MouseClickOutput.ServiceDisabled

        return if (service.clickAtMousePointer()) {
            MouseClickOutput.Clicked
        } else {
            MouseClickOutput.NoPosition
        }
    }
}
