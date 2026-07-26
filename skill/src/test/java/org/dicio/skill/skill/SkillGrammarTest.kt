package org.dicio.skill.skill

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SkillGrammarTest : StringSpec({
    "merging keeps every phrase exactly once, in encounter order" {
        val a = SkillGrammar(phrases = listOf("go home", "back"), dictationTriggers = listOf("open"))
        val b = SkillGrammar(
            phrases = listOf("back", "go back"),
            dictationTriggers = listOf("open", "search"),
        )

        (a + b) shouldBe SkillGrammar(
            phrases = listOf("go home", "back", "go back"),
            dictationTriggers = listOf("open", "search"),
        )
    }

    "merging with the empty grammar changes nothing" {
        val grammar = SkillGrammar(
            phrases = listOf("zoom in", "zoom out"),
            dictationTriggers = listOf("open"),
            fullDecodeTriggers = listOf("open"),
        )

        (SkillGrammar.EMPTY + grammar) shouldBe grammar
        (grammar + SkillGrammar.EMPTY) shouldBe grammar
        SkillGrammar.merge(listOf(grammar)) shouldBe grammar
        SkillGrammar.merge(listOf()) shouldBe SkillGrammar.EMPTY
    }

    "words added on top stay free-standing single-word phrases" {
        // they have to be usable next to a phrase, as in "tap" + "five" for "tap .number."
        SkillGrammar.ofWords(listOf("five", "six")) shouldBe
                SkillGrammar(phrases = listOf("five", "six"))
    }

    "a grammar with no phrases but a trigger is not empty, since the trigger is recognizable" {
        SkillGrammar.EMPTY.isEmpty shouldBe true
        SkillGrammar.ofWords(listOf()).isEmpty shouldBe true
        SkillGrammar.ofWords(listOf("stop")).isEmpty shouldBe false
        SkillGrammar(dictationTriggers = listOf("open")).isEmpty shouldBe false
    }
})
