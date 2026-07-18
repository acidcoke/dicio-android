package org.stypox.dicio.skills.labels

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Labels
import org.stypox.dicio.voiceaccess.VoiceAccessService

class LabelsSkill(correspondingSkillInfo: SkillInfo, data: StandardRecognizerData<Labels>) :
    StandardRecognizerSkill<Labels>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Labels): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return LabelsOutput(LabelsOutput.Result.SERVICE_DISABLED)

        return when (inputData) {
            Labels.Show -> {
                service.showLabels()
                LabelsOutput(LabelsOutput.Result.SHOWED)
            }
            Labels.Hide -> {
                service.hideLabels()
                LabelsOutput(LabelsOutput.Result.HID)
            }
        }
    }
}
