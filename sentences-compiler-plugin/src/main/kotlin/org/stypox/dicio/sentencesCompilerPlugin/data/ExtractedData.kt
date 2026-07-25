package org.stypox.dicio.sentencesCompilerPlugin.data

import org.dicio.sentences_compiler.construct.SentenceConstructList
import java.io.File

data class ExtractedData(
    val skills: List<ExtractedSkill>,
    val languages: List<String>,
)

data class ExtractedSkill(
    val id: String,
    val specificity: Specificity,
    val sentenceDefinitions: List<SentenceDefinition>,
    // use a list of pairs instead of a map to ensure that the code is generated deterministically
    val languageToSentences: List<Pair<String, List<RawSentence>>>,
    val languageToTriggers: Map<String, SkillTriggers>,
)

/**
 * The words that make an utterance leave the closed recognition grammar, declared with the reserved
 * `dictation_triggers` / `full_decode_triggers` keys of a skill sentences file. They can't be
 * derived from the sentences: which words are safe to hand over to free-form dictation depends on
 * what the *other* skills listen for, e.g. "show" starts a sentence of the lyrics skill but is also
 * the whole of "show numbers", which must stay constrained.
 */
data class SkillTriggers(
    val dictationTriggers: List<String> = listOf(),
    val fullDecodeTriggers: List<String> = listOf(),
)

data class ParsedData(
    val skills: List<ParsedSkill>,
    val languages: List<String>,
)

data class ParsedSkill(
    val id: String,
    val specificity: Specificity,
    val sentenceDefinitions: List<SentenceDefinition>,
    // use a list of pairs instead of a map to ensure that the code is generated deterministically
    val languageToSentences: List<Pair<String, List<ParsedSentence>>>,
    val languageToTriggers: Map<String, SkillTriggers>,
)

data class RawSentence(
    val id: String,
    val file: File,
    val rawConstructs: String,
)

data class ParsedSentence(
    val id: String,
    val file: File,
    val rawConstructs: String,
    val constructs: SentenceConstructList,
)
