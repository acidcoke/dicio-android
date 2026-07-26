package org.stypox.dicio.sentences

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.dicio.skill.standard.StandardRecognizerData

/**
 * What the sentences compiler puts into the recognition grammar. The grammar is a list of whole
 * command *phrases*, not of single words: a recognizer constrained to words is free to stitch them
 * together in any order, which is what made "go home" come back as something else.
 *
 * These assertions run against the generated [Sentences] object, i.e. against the real output of
 * `GenerateVocabulary`, not against a reimplementation of it.
 */
private val allPhrases: List<Pair<String, List<String>>> = listOf<Pair<String, StandardRecognizerData<*>?>>(
    "en/back" to Sentences.Back["en"],
    "de/back" to Sentences.Back["de"],
    "en/scroll" to Sentences.Scroll["en"],
    "en/zoom" to Sentences.Zoom["en"],
    "en/labels" to Sentences.Labels["en"],
    "en/click_number" to Sentences.ClickNumber["en"],
    "en/pin_key" to Sentences.PinKey["en"],
    "en/stop_listening" to Sentences.StopListening["en"],
    "en/telephone" to Sentences.Telephone["en"],
    "en/translation" to Sentences.Translation["en"],
    "en/weather" to Sentences.Weather["en"],
).map { (name, data) -> name to data!!.phrases }

class GeneratedGrammarTest : StringSpec({
    "a two-word command is one phrase, and its words are not on their own" {
        val back = Sentences.Back["en"]!!.phrases

        back shouldContainAll listOf("go home", "go back")
        withClue("\"back\" alone is a sentence of its own (go? back), so it stays") {
            back shouldContain "back"
        }
        withClue("these would let the decoder assemble utterances the skill can't match: $back") {
            back shouldNotContain "home"
            back shouldNotContain "go"
        }
    }

    "alternatives are spelled out into separate phrases" {
        // scroll|go up + page up
        Sentences.Scroll["en"]!!.phrases shouldContainAll
                listOf("scroll up", "go up", "page up", "scroll down", "swipe left")
        Sentences.Zoom["en"]!!.phrases shouldContainAll
                listOf("zoom in", "zoom out", "zoom outside", "enlarge", "shrink")
        Sentences.Labels["en"]!!.phrases shouldContainAll listOf("show numbers", "hide numbers")
    }

    "an optional word yields the phrase with and without it" {
        // press|tap|type? alpha: the bare phonetic word has to stay speakable on its own
        Sentences.PinKey["en"]!!.phrases shouldContainAll
                listOf("alpha", "press alpha", "tap alpha", "type alpha", "long press delete")
        // stop (listening|(voice access))?
        Sentences.StopListening["en"]!!.phrases shouldContainAll
                listOf("stop", "stop listening", "stop voice access")
    }

    "words spelled with variations keep their diacritics" {
        // geh<e?>? zurück / geh<e?>? zum startbildschirm
        Sentences.Back["de"]!!.phrases shouldContainAll
                listOf("zurück", "geh zurück", "gehe zurück", "geh zum startbildschirm")
    }

    "a capture splits the phrase and never reaches the grammar itself" {
        // tap|press|number|numbers .number. — the spoken number is added by ClickNumberSkill
        Sentences.ClickNumber["en"]!!.phrases shouldContainAll listOf("tap", "press", "numbers")
        // (call up?)|(ring up)|phone|dial|contact .who.
        Sentences.Telephone["en"]!!.phrases shouldContainAll listOf("call", "call up", "dial")

        allPhrases.forAll { (name, phrases) ->
            withClue("$name must not contain a capture marker like \".who.\": $phrases") {
                phrases.none { "." in it } shouldBe true
            }
        }
    }

    "a skill reached through dictation keeps its trigger in the grammar" {
        // whether its deeply nested sentences get spelled out as phrases or hit
        // MAX_ALTERNATIVES_PER_SENTENCE and fall back to bare words, the trigger has to survive,
        // or the skill can never be reached at all
        val translation = Sentences.Translation["en"]!!
        translation.phrases.shouldNotBeEmpty()
        translation.phrases shouldContain "translate"
        translation.dictationTriggers shouldContain "translate"
    }

    "every phrase is lowercase, trimmed and single-spaced" {
        allPhrases.forAll { (name, phrases) ->
            phrases.shouldNotBeEmpty()
            phrases.forAll { phrase ->
                withClue("$name: \"$phrase\"") {
                    phrase shouldBe phrase.lowercase()
                    phrase shouldBe phrase.trim()
                    phrase.contains("  ") shouldBe false
                    phrase.isEmpty() shouldBe false
                }
            }
        }
    }
})
