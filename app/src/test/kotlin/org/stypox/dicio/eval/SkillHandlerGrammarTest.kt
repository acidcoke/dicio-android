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
 * user turned off must not leave its words behind, where they would keep pulling recognitions away
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
private val backWords = Sentences.Back["en"]!!.vocabulary
private val labelsOnlyWords = Sentences.Labels["en"]!!.vocabulary - backWords.toSet()
private val searchTriggers = Sentences.Search["en"]!!.dictationTriggers
private val confirmationWords = Sentences.Confirmation["en"]!!.vocabulary

class SkillHandlerGrammarTest : StringSpec({
    "the skills used below really do have words of their own" {
        // otherwise the assertions further down would hold vacuously
        withClue("labels must understand words the back skill doesn't") {
            labelsOnlyWords.shouldNotBeEmpty()
        }
        searchTriggers.shouldNotBeEmpty()
        confirmationWords.shouldNotBeEmpty()
    }

    "a skill the settings say nothing about counts as enabled" {
        val grammar = grammarOf(listOf(LabelsInfo, BackInfo), mapOf())

        grammar.words shouldContainAll labelsOnlyWords
        grammar.words shouldContainAll backWords
    }

    "a disabled skill contributes none of its words" {
        val grammar = grammarOf(listOf(LabelsInfo, BackInfo), mapOf(LabelsInfo.id to false))

        withClue("words only the disabled labels skill understands: $labelsOnlyWords") {
            grammar.words.intersect(labelsOnlyWords.toSet()).shouldBeEmpty()
        }
        withClue("the skills that are still enabled must keep their words") {
            grammar.words shouldContainAll backWords
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

        grammar.words shouldContainAll confirmationWords
        grammar.words.intersect(labelsOnlyWords.toSet()).shouldBeEmpty()
    }
})
