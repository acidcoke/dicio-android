package org.stypox.dicio.util

import org.dicio.numbers.unit.Number
import org.dicio.skill.context.SkillContext

/**
 * Parses a spoken number from an STT transcript: digit strings fast-path, spelled-out numbers via
 * the dicio-numbers library, and a hand-rolled German fallback (dicio-numbers has no German
 * parser). Shared by the click-number and grid skills.
 */
object SpokenNumberParser {

    fun parse(ctx: SkillContext, text: String?): Int? {
        val trimmed = fixTeenTensMishearing(text?.trim().orEmpty())
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

    // Vosk's small model often confuses an English "-ty" tens word with the similar-sounding
    // "-teen" word ("thirty" -> "thirteen"). A teen immediately followed by a unit
    // ("thirteen nine") is never a valid number, so it must have been the matching tens word.
    private val TEEN_TO_TENS = mapOf(
        "thirteen" to "thirty", "fourteen" to "forty", "fifteen" to "fifty",
        "sixteen" to "sixty", "seventeen" to "seventy", "eighteen" to "eighty",
        "nineteen" to "ninety",
    )
    private val UNITS = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
    )
    private val WHITESPACE = Regex("\\s+")

    private fun fixTeenTensMishearing(text: String): String {
        if (text.isEmpty()) return text
        val tokens = text.split(WHITESPACE).toMutableList()
        for (i in 0 until tokens.size - 1) {
            val tens = TEEN_TO_TENS[tokens[i].lowercase()]
            if (tens != null && tokens[i + 1].lowercase() in UNITS) {
                tokens[i] = tens
            }
        }
        return tokens.joinToString(" ")
    }
}
