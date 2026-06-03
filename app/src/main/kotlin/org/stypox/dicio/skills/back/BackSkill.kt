package org.stypox.dicio.skills.back

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Back
import org.stypox.dicio.voiceaccess.VoiceAccessService

class BackSkill(correspondingSkillInfo: SkillInfo, data: StandardRecognizerData<Back>) :
    StandardRecognizerSkill<Back>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Back): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return BackOutput(BackOutput.Result.SERVICE_DISABLED)

        return when (inputData) {
            Back.GoBack -> {
                service.goBack()
                BackOutput(BackOutput.Result.WENT_BACK)
            }
            Back.GoHome -> {
                service.goHome()
                BackOutput(BackOutput.Result.WENT_HOME)
            }
        }
    }
}
