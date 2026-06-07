package org.stypox.dicio.skills.notifications

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Notifications
import org.stypox.dicio.voiceaccess.VoiceAccessService

class NotificationsSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Notifications>,
) : StandardRecognizerSkill<Notifications>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Notifications): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return NotificationsOutput(shown = false)
        service.openNotifications()
        return NotificationsOutput(shown = true)
    }
}
