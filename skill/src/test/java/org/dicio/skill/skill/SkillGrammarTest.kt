package org.dicio.skill.skill

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SkillGrammarTest : StringSpec({
    "merging keeps every word exactly once, in encounter order" {
        val a = SkillGrammar(words = listOf("go", "back"), dictationTriggers = listOf("open"))
        val b = SkillGrammar(words = listOf("back", "home"), dictationTriggers = listOf("open", "search"))

        (a + b) shouldBe SkillGrammar(
            words = listOf("go", "back", "home"),
            dictationTriggers = listOf("open", "search"),
        )
    }

    "merging with the empty grammar changes nothing" {
        val grammar = SkillGrammar(
            words = listOf("zoom", "in"),
            dictationTriggers = listOf("open"),
            fullDecodeTriggers = listOf("open"),
        )

        (SkillGrammar.EMPTY + grammar) shouldBe grammar
        (grammar + SkillGrammar.EMPTY) shouldBe grammar
        SkillGrammar.merge(listOf(grammar)) shouldBe grammar
        SkillGrammar.merge(listOf()) shouldBe SkillGrammar.EMPTY
    }

    "a grammar with no words but a trigger is not empty, since the trigger is recognizable" {
        SkillGrammar.EMPTY.isEmpty shouldBe true
        SkillGrammar.ofWords(listOf()).isEmpty shouldBe true
        SkillGrammar.ofWords(listOf("stop")).isEmpty shouldBe false
        SkillGrammar(dictationTriggers = listOf("open")).isEmpty shouldBe false
    }
})
