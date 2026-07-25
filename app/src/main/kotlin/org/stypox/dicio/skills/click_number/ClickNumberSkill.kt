package org.stypox.dicio.skills.click_number

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillGrammar
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.ClickNumber
import org.stypox.dicio.settings.datastore.NumberSelectionMode
import org.stypox.dicio.util.SpokenNumberParser
import org.stypox.dicio.voiceaccess.VoiceAccessService

class ClickNumberSkill(
    private val info: ClickNumberInfo,
    data: StandardRecognizerData<ClickNumber>,
    private val numberWords: List<String>,
) : StandardRecognizerSkill<ClickNumber>(info, data) {

    // the `.number.` capture matches a spoken number, which the sentences don't spell out
    override val grammar: SkillGrammar
        get() = data.grammar + SkillGrammar.ofWords(numberWords)

    override fun score(ctx: SkillContext, input: String): Pair<Score, ClickNumber> {
        val (score, result) = super.score(ctx, input)
        // when the user disabled bare-number selection, never let a bare number win
        if (result is ClickNumber.Bare &&
            info.numberSelectionMode == NumberSelectionMode.NUMBER_SELECTION_MODE_EXPLICIT_ONLY
        ) {
            return Pair(AlwaysWorstScore, result)
        }
        return Pair(score, result)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: ClickNumber): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return ClickNumberOutput.ServiceDisabled

        val numberText = when (inputData) {
            is ClickNumber.Explicit -> inputData.number
            is ClickNumber.Hold -> inputData.number
            is ClickNumber.Bare -> inputData.number
        }

        val number = SpokenNumberParser.parse(ctx, numberText)
            ?: return ClickNumberOutput.CouldNotUnderstand

        return if (inputData is ClickNumber.Hold) {
            if (service.longClickLabel(number)) {
                ClickNumberOutput.Held(number)
            } else {
                ClickNumberOutput.NoLabel(number)
            }
        } else if (service.clickLabel(number)) {
            ClickNumberOutput.Tapped(number)
        } else {
            ClickNumberOutput.NoLabel(number)
        }
    }
}
