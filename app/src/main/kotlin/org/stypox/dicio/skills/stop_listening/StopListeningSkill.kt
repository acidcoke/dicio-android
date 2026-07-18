package org.stypox.dicio.skills.stop_listening

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.StopListening
import org.stypox.dicio.voiceaccess.VoiceAccessService

class StopListeningSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<StopListening>,
) : StandardRecognizerSkill<StopListening>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: StopListening): SkillOutput {
        // ends the session: stops STT and removes the listening bar / any labels
        VoiceAccessService.instance?.stopVoiceSession()
        return StopListeningOutput
    }
}
