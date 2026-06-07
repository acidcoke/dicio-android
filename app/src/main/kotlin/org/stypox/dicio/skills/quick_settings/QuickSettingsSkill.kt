package org.stypox.dicio.skills.quick_settings

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.QuickSettings
import org.stypox.dicio.voiceaccess.VoiceAccessService

class QuickSettingsSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<QuickSettings>,
) : StandardRecognizerSkill<QuickSettings>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: QuickSettings): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return QuickSettingsOutput(shown = false)
        service.openQuickSettings()
        return QuickSettingsOutput(shown = true)
    }
}
