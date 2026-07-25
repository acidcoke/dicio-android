package org.stypox.dicio.util

import android.content.res.Configuration
import android.content.res.Resources
import org.dicio.numbers.unit.Duration
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R

/**
 * Words that some skills can understand but that their sentence definitions don't spell out,
 * because they are matched by a capturing group at runtime: spoken numbers, durations and the
 * phonetic words labelling grid columns and PIN keys.
 *
 * A closed recognition grammar has to contain them, so the skills concerned add them to their
 * [org.dicio.skill.skill.Skill.grammar]. Everything here is resolved for the language of the
 * sentences (and therefore of the loaded speech model), never for the system locale.
 */
object GrammarVocabulary {

    /** How far up spoken numbers are put into the grammar; labels/grid rows never go higher. */
    private const val MAX_NUMBER = 100

    /** The letters labelling grid columns, see [org.stypox.dicio.skills.grid.GridCellReference]. */
    val gridColumnLetters: List<String> = ('a'..'h').map { it.toString() }

    /**
     * Spoken number words, e.g. `one, two, ..., twenty one, ...`. Built with the number formatter of
     * the current language, falling back to the hand-rolled German word list since dicio-numbers has
     * no German [org.dicio.numbers.ParserFormatter] (mirroring [SpokenNumberParser]). Empty for
     * languages that have neither, which is correct: there the numbers can't be parsed anyway.
     */
    fun numberWords(ctx: SkillContext): List<String> {
        val parserFormatter = ctx.parserFormatter
            ?: return if (ctx.sentencesLanguage == "de") GermanNumberParser.numberWords else listOf()

        return (0..MAX_NUMBER)
            .flatMap { splitWords(parserFormatter.niceNumber(it.toDouble()).get()) }
            .distinct()
    }

    /**
     * Spoken duration words, e.g. `second, seconds, minute, ..., hour, hours`, as needed by the
     * `.duration.` capture of the timer skill. Empty when the language has no number formatter, in
     * which case the skills using durations are unavailable anyway.
     */
    fun durationWords(ctx: SkillContext): List<String> {
        val parserFormatter = ctx.parserFormatter ?: return listOf()

        // one and two of each unit, so that both the singular and the plural form are covered
        return listOf(1L, 2L)
            .flatMap { amount ->
                listOf(
                    java.time.Duration.ofSeconds(amount),
                    java.time.Duration.ofMinutes(amount),
                    java.time.Duration.ofHours(amount),
                    java.time.Duration.ofDays(amount),
                )
            }
            .flatMap { splitWords(parserFormatter.niceDuration(Duration(it)).speech(true).get()) }
            .distinct()
    }

    /**
     * The phonetic words labelling PIN keys and grid columns (`alpha, bravo, ...` in English), in
     * the language of the sentences. These are compared against the spoken input in
     * [org.stypox.dicio.voiceaccess.VoiceAccessService.pinSlotForWord].
     */
    fun phoneticWords(ctx: SkillContext): List<String> =
        localeResources(ctx).getStringArray(R.array.va_pin_words).map { it.lowercase() }

    /**
     * Everything a spoken grid cell reference like "a2" or "bravo two" is made of, i.e. exactly what
     * [org.stypox.dicio.skills.grid.GridCellReference] parses.
     */
    fun gridCellWords(ctx: SkillContext): List<String> =
        gridColumnLetters + phoneticWords(ctx) + numberWords(ctx)

    /**
     * Resources forced to the language of the sentences, and not to the system locale: the grammar
     * must match the language of the loaded speech model, exactly like the labels drawn on screen
     * (see [org.stypox.dicio.voiceaccess.VoiceAccessService]).
     */
    private fun localeResources(ctx: SkillContext): Resources {
        val config = Configuration(ctx.android.resources.configuration)
        config.setLocale(ctx.locale)
        return ctx.android.createConfigurationContext(config).resources
    }

    private val NON_LETTERS = Regex("[^\\p{L}]+")

    private fun splitWords(text: String): List<String> =
        text.lowercase().split(NON_LETTERS).filter { it.isNotBlank() }
}
