package org.stypox.dicio.eval

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.MockSkillContext
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.back.BackInfo
import org.stypox.dicio.skills.labels.LabelsInfo
import org.stypox.dicio.skills.search.SearchInfo

/**
 * The recognition grammar is the union of the grammars of the *enabled* skills only: a skill the
 * user turned off must not leave its phrases behind, where they would keep pulling recognitions away
 * from the skills that are still on.
 *
 * These use real skills, but only ones whose [SkillInfo.build] needs nothing but their sentences —
 * grid and zoom for instance read Android resources through
 * [org.stypox.dicio.util.GrammarVocabulary] and can't be built in a plain JVM test.
 */
private object EnglishSkillContext : SkillContext by MockSkillContext {
    override val sentencesLanguage = "en"
}

private fun grammarOf(skillInfos: List<SkillInfo>, enabledSkills: Map<String, Boolean>) =
    SkillHandler.mergeGrammar(
        SkillHandler.buildEnabledSkills(skillInfos, enabledSkills, EnglishSkillContext)
            .map { (_info, skill) -> skill },
        EnglishSkillContext.sentencesLanguage,
    )

// derived from the sentence definitions instead of hardcoded, so that editing en/labels.yml or
// en/back.yml can't silently turn these assertions into no-ops
private val backPhrases = Sentences.Back["en"]!!.phrases
private val labelsOnlyPhrases = Sentences.Labels["en"]!!.phrases - backPhrases.toSet()
private val searchTriggers = Sentences.Search["en"]!!.dictationTriggers
private val confirmationPhrases = Sentences.Confirmation["en"]!!.phrases

class SkillHandlerGrammarTest : StringSpec({
    "the skills used below really do have phrases of their own" {
        // otherwise the assertions further down would hold vacuously
        withClue("labels must understand phrases the back skill doesn't") {
            labelsOnlyPhrases.shouldNotBeEmpty()
        }
        searchTriggers.shouldNotBeEmpty()
        confirmationPhrases.shouldNotBeEmpty()
    }

    "a skill the settings say nothing about counts as enabled" {
        val grammar = grammarOf(listOf(LabelsInfo, BackInfo), mapOf())

        grammar.phrases shouldContainAll labelsOnlyPhrases
        grammar.phrases shouldContainAll backPhrases
    }

    "a disabled skill contributes none of its phrases" {
        val grammar = grammarOf(listOf(LabelsInfo, BackInfo), mapOf(LabelsInfo.id to false))

        withClue("phrases only the disabled labels skill understands: $labelsOnlyPhrases") {
            grammar.phrases.intersect(labelsOnlyPhrases.toSet()).shouldBeEmpty()
        }
        withClue("the skills that are still enabled must keep their phrases") {
            grammar.phrases shouldContainAll backPhrases
        }
    }

    "a disabled skill contributes none of its dictation triggers either" {
        val enabled = grammarOf(listOf(SearchInfo), mapOf())
        val disabled = grammarOf(listOf(SearchInfo), mapOf(SearchInfo.id to false))

        enabled.dictationTriggers shouldContainAll searchTriggers
        disabled.dictationTriggers.shouldBeEmpty()
    }

    "the continue/stop answers stay recognizable even with every skill disabled" {
        val skillInfos = listOf(LabelsInfo, BackInfo, SearchInfo)
        val grammar = grammarOf(skillInfos, skillInfos.associate { it.id to false })

        grammar.phrases shouldContainAll confirmationPhrases
        grammar.phrases.intersect(labelsOnlyPhrases.toSet()).shouldBeEmpty()
    }
})
