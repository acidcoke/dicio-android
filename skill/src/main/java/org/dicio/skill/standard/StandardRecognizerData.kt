package org.dicio.skill.standard

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillGrammar
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.construct.Construct
import org.dicio.skill.standard.util.MatchHelper
import org.dicio.skill.standard.util.initialMemToEnd

open class StandardRecognizerData<out T>(
    val specificity: Specificity,
    private val converter: (input: String, sentenceId: String, matchResult: StandardScore) -> T,
    private val sentencesWithId: List<Pair<String, Construct>>,
    /**
     * The whole command phrases these sentences can produce, generated at build time from the
     * sentence definitions. Used to constrain a speech recognizer to a closed grammar, see
     * [SkillGrammar].
     */
    val phrases: List<String> = listOf(),
    /**
     * Leading words of the sentences containing an open-vocabulary capture, see [SkillGrammar].
     */
    val dictationTriggers: List<String> = listOf(),
    /**
     * Subset of [dictationTriggers] whose whole utterance must be decoded free-form, see
     * [SkillGrammar].
     */
    val fullDecodeTriggers: List<String> = listOf(),
) {
    /** The grammar these sentences contribute to the speech recognizer. */
    val grammar: SkillGrammar
        get() = SkillGrammar(phrases, dictationTriggers, fullDecodeTriggers)

    fun score(ctx: SkillContext, input: String): Pair<StandardScore, T> {
        return score(ctx.standardMatchHelper!!, input)
    }

    fun score(helper: MatchHelper, input: String): Pair<StandardScore, T> {
        val cumulativeWeight = helper.cumulativeWeight

        var bestRes: Pair<String, StandardScore>? = null
        for ((sentenceId, construct) in sentencesWithId) {
            val memToEnd = initialMemToEnd(cumulativeWeight)
            construct.matchToEnd(memToEnd, helper)

            if (bestRes == null || memToEnd[0].score() > bestRes.second.score()) {
                bestRes = Pair(sentenceId, memToEnd[0])
            }
        }

        // it is impossible for the result to be null because sentencesWithId is non-empty
        bestRes!!
        return Pair(
            bestRes.second,
            converter(
                input,
                bestRes.first,
                bestRes.second,
            )
        )
    }
}
