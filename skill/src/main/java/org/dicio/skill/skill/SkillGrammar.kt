package org.dicio.skill.skill

/**
 * What a skill needs a speech recognizer to be able to hear. Constraining recognition to the union
 * of the grammars of the *enabled* skills makes short commands much more reliable, and ensures that
 * a disabled skill can't pull recognitions towards its own words.
 *
 * Most of it is generated at build time from the skill's sentence definitions (see
 * [org.dicio.skill.standard.StandardRecognizerData.grammar]); skills whose captures match more than
 * what their sentences spell out (spoken numbers, phonetic letters, ...) add those words on top by
 * overriding [Skill.grammar].
 */
data class SkillGrammar(
    /**
     * The command phrases the skill can understand, each one or more words separated by single
     * spaces, e.g. `go home`. A recognizer constrained to these can only return sequences of whole
     * phrases, which is what keeps "go home" from being stitched together as "going".
     */
    val phrases: List<String> = listOf(),
    /**
     * Leading words after which the rest of the utterance is open vocabulary (an app name, a search
     * query, ...) and must be decoded free-form rather than forced onto grammar words.
     */
    val dictationTriggers: List<String> = listOf(),
    /**
     * Subset of [dictationTriggers] whose whole utterance, trigger word included, is decoded
     * free-form.
     */
    val fullDecodeTriggers: List<String> = listOf(),
) {
    val isEmpty: Boolean
        get() = phrases.isEmpty() && dictationTriggers.isEmpty()

    /** Merges two grammars, dropping duplicates while keeping a deterministic order. */
    operator fun plus(other: SkillGrammar) = SkillGrammar(
        phrases = (phrases + other.phrases).distinct(),
        dictationTriggers = (dictationTriggers + other.dictationTriggers).distinct(),
        fullDecodeTriggers = (fullDecodeTriggers + other.fullDecodeTriggers).distinct(),
    )

    companion object {
        val EMPTY = SkillGrammar()

        /** Merges any number of grammars, see [plus]. */
        fun merge(grammars: Iterable<SkillGrammar>): SkillGrammar =
            grammars.fold(EMPTY) { acc, grammar -> acc + grammar }

        /**
         * A grammar made of single-word phrases, for the skills that add words their sentences
         * don't spell out. These have to stay free-standing: only then can the recognizer return
         * them next to a phrase, as in `tap` + `five` for "tap .number.".
         */
        fun ofWords(words: Iterable<String>) = SkillGrammar(phrases = words.toList())
    }
}
