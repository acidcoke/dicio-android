package org.stypox.dicio.skills.pin_key

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.PinKey
import org.stypox.dicio.voiceaccess.VoiceAccessService

class PinKeySkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<PinKey>,
) : StandardRecognizerSkill<PinKey>(correspondingSkillInfo, data) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, PinKey> {
        val (score, result) = super.score(ctx, input)
        // these phonetic / "delete" / "enter" words are common; only ever match while a PIN pad is up
        if (VoiceAccessService.instance?.isPinModeActive() != true) {
            return Pair(AlwaysWorstScore, result)
        }
        return Pair(score, result)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: PinKey): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return PinKeyOutput.ServiceDisabled

        val pressed = when (inputData) {
            PinKey.SlotA -> service.clickPinSlot(0)
            PinKey.SlotB -> service.clickPinSlot(1)
            PinKey.SlotC -> service.clickPinSlot(2)
            PinKey.SlotD -> service.clickPinSlot(3)
            PinKey.SlotE -> service.clickPinSlot(4)
            PinKey.SlotF -> service.clickPinSlot(5)
            PinKey.SlotG -> service.clickPinSlot(6)
            PinKey.SlotH -> service.clickPinSlot(7)
            PinKey.SlotI -> service.clickPinSlot(8)
            PinKey.SlotJ -> service.clickPinSlot(9)
            PinKey.Delete -> service.clickPinDelete()
            PinKey.Enter -> service.clickPinEnter()
        }
        return if (pressed) PinKeyOutput.Pressed else PinKeyOutput.NoKey
    }
}
