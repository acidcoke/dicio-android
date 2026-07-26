package org.stypox.dicio.sentencesCompilerPlugin.gen

import org.dicio.sentences_compiler.construct.AggregateConstruct
import org.dicio.sentences_compiler.construct.CapturingGroup
import org.dicio.sentences_compiler.construct.Construct
import org.dicio.sentences_compiler.construct.OptionalConstruct
import org.dicio.sentences_compiler.construct.Word
import org.dicio.sentences_compiler.construct.WordWithVariations
import org.stypox.dicio.sentencesCompilerPlugin.data.ParsedSentence
import org.stypox.dicio.sentencesCompilerPlugin.data.ParsedSkill
import org.stypox.dicio.sentencesCompilerPlugin.util.DICTATION_TRIGGERS_KEY
import org.stypox.dicio.sentencesCompilerPlugin.util.SentencesCompilerPluginException

/**
 * The recognition grammar of one skill in one language: what a speech recognizer has to be able to
 * hear for the skill to work. Constraining recognition to the grammars of just the enabled skills
 * is what keeps a disabled skill from pulling recognitions towards its own words.
 *
 * The phrases are derived from the sentences; the triggers come from the reserved
 * `dictation_triggers` / `full_decode_triggers` keys of the sentences file. All lists are lowercase,
 * deduplicated and sorted, so that the generated code (and therefore the APK) stays reproducible.
 */
data class SkillVocabulary(
    val phrases: List<String>,
    val dictationTriggers: List<String>,
    val fullDecodeTriggers: List<String>,
)

/**
 * Above this many alternatives a sentence contributes its bare words instead of its phrases. The
 * sentences of the free-form skills nest so many optionals that spelling them all out would produce
 * thousands of phrases (and, worse, phrases whose words can then be recombined at will anyway).
 * Those sentences are reached through the dictation triggers, so their exact wording never has to be
 * recognizable.
 */
private const val MAX_ALTERNATIVES_PER_SENTENCE = 250

/** How [CapturingGroup.buildAlternatives] renders a capture, e.g. `.where.`. */
private val CAPTURING_GROUP = Regex("\\.[a-zA-Z_]+\\.")

private val WHITESPACE = Regex("\\s+")

fun buildVocabulary(
    skill: ParsedSkill,
    language: String,
    sentences: List<ParsedSentence>,
): SkillVocabulary {
    val phrases = HashSet<String>()
    for (sentence in sentences) {
        phrases.addAll(collectPhrases(sentence.constructs))
    }

    val triggers = skill.languageToTriggers[language]
    // a trigger hands the rest of the utterance over to free-form dictation, so it can only ever be
    // a word the skill itself listens for; anything else is a typo or a leftover
    val words = phrases.flatMapTo(HashSet()) { it.split(" ") }
    val unknownTriggers = triggers?.dictationTriggers.orEmpty() - words
    if (unknownTriggers.isNotEmpty()) {
        throw SentencesCompilerPluginException(
            "Skill sentences file $language/${skill.id}.yml lists these $DICTATION_TRIGGERS_KEY " +
                    "that do not appear in any of its sentences: $unknownTriggers"
        )
    }

    return SkillVocabulary(
        phrases = phrases.sorted(),
        dictationTriggers = triggers?.dictationTriggers.orEmpty().sorted(),
        fullDecodeTriggers = triggers?.fullDecodeTriggers.orEmpty().sorted(),
    )
}

/**
 * The whole command phrases [construct] can produce, e.g. `go home` and `back` for `go? back`. A
 * recognizer constrained to phrases can only ever return sequences of them, which is what makes
 * short commands reliable: with the words alone it is free to stitch "go home" together as "going".
 *
 * Captures split a phrase in two, since what they match is either free-form (and reached through the
 * dictation triggers) or supplied by the skill itself at runtime (e.g. spoken numbers): `call .who.`
 * contributes just `call`, and `look .what. up` contributes `look` and `up`.
 */
fun collectPhrases(construct: Construct): Set<String> {
    // countAlternatives() multiplies, so a deeply optional sentence can overflow into a negative
    // number: anything outside the sane range falls back to the words, it is never expanded
    if (construct.countAlternatives() !in 1..MAX_ALTERNATIVES_PER_SENTENCE) {
        return collectWords(construct)
    }

    return construct.buildAlternatives()
        .flatMap { it.split(CAPTURING_GROUP) }
        .mapNotNullTo(HashSet()) { phrase ->
            phrase.trim().replace(WHITESPACE, " ").lowercase().takeIf { it.isNotEmpty() }
        }
}

/**
 * All of the literal words that can appear in [construct], used for the sentences that are too
 * branchy to spell out as phrases. Capturing groups contribute nothing, see [collectPhrases].
 */
fun collectWords(construct: Construct): Set<String> {
    return when (construct) {
        // deliberately the raw value and not `normalizedValue`, which NFKD-normalizes the word:
        // a recognizer needs "öffne" and "für", not "offne" and "fur"
        is Word -> setOf(construct.value.lowercase())

        // buildAlternatives() spells the variations out, e.g. time<r|rs?> -> timer, timers, time
        is WordWithVariations -> construct.buildAlternatives()
            .filter { it.isNotBlank() }
            .mapTo(HashSet()) { it.lowercase() }

        is CapturingGroup, is OptionalConstruct -> emptySet()
        is AggregateConstruct -> construct.constructs.flatMapTo(HashSet(), ::collectWords)
        else -> throw SentencesCompilerPluginException(
            "Unexpected construct obtained from sentences compiler: type=${
                construct::class.simpleName
            }, value=\"$construct\""
        )
    }
}
