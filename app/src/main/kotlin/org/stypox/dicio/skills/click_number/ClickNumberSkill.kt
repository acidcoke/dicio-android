package org.stypox.dicio.skills.click_number

import org.dicio.numbers.unit.Number
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.ClickNumber
import org.stypox.dicio.settings.datastore.NumberSelectionMode
import org.stypox.dicio.voiceaccess.VoiceAccessService

class ClickNumberSkill(
    private val info: ClickNumberInfo,
    data: StandardRecognizerData<ClickNumber>,
) : StandardRecognizerSkill<ClickNumber>(info, data) {

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
            is ClickNumber.Bare -> inputData.number
        }

        val number = parseNumber(ctx, numberText)
            ?: return ClickNumberOutput.CouldNotUnderstand

        return if (service.clickLabel(number)) {
            ClickNumberOutput.Tapped(number)
        } else {
            ClickNumberOutput.NoLabel(number)
        }
    }

    private fun parseNumber(ctx: SkillContext, text: String?): Int? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        // fast path: the STT returned a digit string like "5"
        trimmed.toIntOrNull()?.let { return it }

        // otherwise parse spelled-out numbers like "five" via the dicio numbers library
        val parsed = ctx.parserFormatter?.extractNumber(trimmed)?.parseMixedWithText()
        val number = parsed?.firstOrNull { it is Number } as? Number
        if (number != null) {
            return if (number.isDecimal) number.decimalValue().toInt()
            else number.integerValue().toInt()
        }

        // dicio-numbers has no German parser, so fall back to a hand-rolled one for German
        if (ctx.sentencesLanguage == "de") {
            GermanNumberParser.parse(trimmed)?.let { return it }
        }
        return null
    }
}
