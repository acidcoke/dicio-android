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
 * The word list is derived from the sentences; the triggers come from the reserved
 * `dictation_triggers` / `full_decode_triggers` keys of the sentences file. All lists are lowercase,
 * deduplicated and sorted, so that the generated code (and therefore the APK) stays reproducible.
 */
data class SkillVocabulary(
    val words: List<String>,
    val dictationTriggers: List<String>,
    val fullDecodeTriggers: List<String>,
)

fun buildVocabulary(
    skill: ParsedSkill,
    language: String,
    sentences: List<ParsedSentence>,
): SkillVocabulary {
    val words = HashSet<String>()
    for (sentence in sentences) {
        words.addAll(collectWords(sentence.constructs))
    }

    val triggers = skill.languageToTriggers[language]
    // a trigger hands the rest of the utterance over to free-form dictation, so it can only ever be
    // a word the skill itself listens for; anything else is a typo or a leftover
    val unknownTriggers = triggers?.dictationTriggers.orEmpty() - words
    if (unknownTriggers.isNotEmpty()) {
        throw SentencesCompilerPluginException(
            "Skill sentences file $language/${skill.id}.yml lists these $DICTATION_TRIGGERS_KEY " +
                    "that do not appear in any of its sentences: $unknownTriggers"
        )
    }

    return SkillVocabulary(
        words = words.sorted(),
        dictationTriggers = triggers?.dictationTriggers.orEmpty().sorted(),
        fullDecodeTriggers = triggers?.fullDecodeTriggers.orEmpty().sorted(),
    )
}

/**
 * All of the literal words that can appear in [construct]. Capturing groups contribute nothing:
 * what they match is either free-form (and reached through the dictation triggers) or supplied by
 * the skill itself at runtime (e.g. spoken numbers).
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
