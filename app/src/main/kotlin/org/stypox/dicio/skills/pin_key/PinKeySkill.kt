package org.stypox.dicio.skills.pin_key

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.PinKey
import org.stypox.dicio.voiceaccess.VoiceAccessService

class PinKeySkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<PinKey>,
) : StandardRecognizerSkill<PinKey>(correspondingSkillInfo, data) {

    // The PIN actions parsed from a single utterance ("alpha bravo charlie [enter]"), set in score()
    // and consumed by generateOutput() within the same (sequential) utterance evaluation. An empty
    // list is the "suppress" marker (utterance mixed PIN words with a foreign word, so do nothing).
    // Null means this is a single key / not a chain, in which case the standard recognizer result
    // (inputData) is used instead.
    @Volatile
    private var pendingChain: List<PinAction>? = null

    private sealed interface PinAction {
        data class Tap(val slot: Int) : PinAction
        data object Enter : PinAction
    }

    private sealed interface ParseResult {
        /** Pin-only utterance with >= 2 chainable actions: press them all in order. */
        data class Chain(val actions: List<PinAction>) : ParseResult
        /** PIN words mixed with a foreign word: claim the utterance but do nothing. */
        data object Suppress : ParseResult
        /** Single key / delete / pure foreign / ambiguous: defer to the standard recognizer. */
        data object Defer : ParseResult
    }

    override fun score(ctx: SkillContext, input: String): Pair<Score, PinKey> {
        val service = VoiceAccessService.instance

        // the phonetic slot chain only ever makes sense while a numeric PIN pad is up
        if (service?.isPinModeActive() == true) {
            return when (val parsed = parse(ctx, service, input)) {
                is ParseResult.Chain -> {
                    pendingChain = parsed.actions
                    Pair(AlwaysBestScore, parsed.actions.first().toPinKey())
                }
                // claim the utterance (beating scroll/back/… too) so a mixed command is ignored
                ParseResult.Suppress -> {
                    pendingChain = emptyList()
                    Pair(AlwaysBestScore, PinKey.Enter)
                }
                // single key, lone delete, pure foreign command, or ambiguous: standard wins
                ParseResult.Defer -> {
                    pendingChain = null
                    super.score(ctx, input)
                }
            }
        }

        pendingChain = null
        val (score, result) = super.score(ctx, input)
        // outside PIN mode, only the plain enter/delete/shift/space keys of a generic on-screen
        // keyboard are valid (the phonetic slot words are common; don't let them match here)
        return if (service?.isKeyboardActive() == true && result.isKeyboardKey()) {
            Pair(score, result)
        } else {
            Pair(AlwaysWorstScore, result)
        }
    }

    private fun PinKey.isKeyboardKey(): Boolean =
        this is PinKey.Enter || this is PinKey.Delete || this is PinKey.Shift || this is PinKey.Space ||
            this is PinKey.HoldDelete || this is PinKey.HoldEnter ||
            this is PinKey.HoldShift || this is PinKey.HoldSpace

    /**
     * Classifies [input] (only called while a PIN pad is up):
     *  - a clean pin-only utterance with >= 2 slot taps (+ optional trailing enter) is a [Chain];
     *  - PIN words mixed with any foreign word is [Suppress] (do nothing — neither press nor let a
     *    navigation command run);
     *  - everything else (single key, lone delete, pure foreign command, delete combined with keys)
     *    is [Defer], handled by the standard recognizer.
     * "delete" is deliberately not chainable.
     */
    private fun parse(
        ctx: SkillContext,
        service: VoiceAccessService,
        input: String,
    ): ParseResult {
        val verbs = verbWords(ctx.sentencesLanguage)
        val enters = enterWords(ctx.sentencesLanguage)
        val deletes = deleteWords(ctx.sentencesLanguage)

        val actions = ArrayList<PinAction>()
        var pinPresent = false
        var foreignPresent = false
        var deletePresent = false
        for (token in input.trim().split(WHITESPACE)) {
            if (token.isBlank()) continue
            val word = token.lowercase()
            val slot = service.pinSlotForWord(word)
            when {
                slot != null -> { actions.add(PinAction.Tap(slot)); pinPresent = true }
                word in enters -> { actions.add(PinAction.Enter); pinPresent = true }
                word in deletes -> { deletePresent = true; pinPresent = true }
                word in verbs -> {} // optional modifier, skip
                else -> foreignPresent = true
            }
        }

        return when {
            foreignPresent && pinPresent -> ParseResult.Suppress
            foreignPresent -> ParseResult.Defer // pure foreign command (scroll/back/…)
            !deletePresent && actions.size >= 2 -> ParseResult.Chain(actions)
            else -> ParseResult.Defer // single key, lone delete, or delete mixed with keys
        }
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: PinKey): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return PinKeyOutput.ServiceDisabled

        val chain = pendingChain
        if (chain != null) {
            pendingChain = null
            if (chain.isEmpty()) return PinKeyOutput.NoKey // suppressed mixed utterance: do nothing
            var anyPressed = false
            for (action in chain) {
                val pressed = when (action) {
                    is PinAction.Tap -> service.clickPinSlot(action.slot)
                    PinAction.Enter -> service.clickPinEnter()
                }
                anyPressed = anyPressed || pressed
            }
            return if (anyPressed) PinKeyOutput.Pressed else PinKeyOutput.NoKey
        }

        val pressed = when (inputData) {
            PinKey.SlotA -> service.clickPinSlot(0)
            PinKey.SlotB -> service.clickPinSlot(1)
            PinKey.SlotC -> service.clickPinSlot(2)
            PinKey.SlotD -> service.clickPinSlot(3)
            PinKey.SlotE -> service.clickPinSlot(4)
            PinKey.SlotF -> service.clickPinSlot(5)
            PinKey.SlotG -> service.clickPinSlot(6)
            PinKey.SlotH -> service.clickPinSlot(7)
            PinKey.SlotI -> service.clickPinSlot(8)
            PinKey.SlotJ -> service.clickPinSlot(9)
            PinKey.Delete -> if (service.isPinModeActive()) service.clickPinDelete() else service.clickKeyboardDelete()
            PinKey.Enter -> if (service.isPinModeActive()) service.clickPinEnter() else service.clickKeyboardEnter()
            PinKey.Shift -> service.clickKeyboardShift()
            PinKey.Space -> service.clickKeyboardSpace()
            PinKey.HoldDelete -> service.holdKeyboardDelete()
            PinKey.HoldEnter -> service.holdKeyboardEnter()
            PinKey.HoldShift -> service.holdKeyboardShift()
            PinKey.HoldSpace -> service.holdKeyboardSpace()
        }
        return if (pressed) PinKeyOutput.Pressed else PinKeyOutput.NoKey
    }

    private fun PinAction.toPinKey(): PinKey = when (this) {
        is PinAction.Tap -> SLOT_KEYS[slot]
        PinAction.Enter -> PinKey.Enter
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        // slot index -> generated PinKey object (only used as the value carried out of score())
        private val SLOT_KEYS = listOf(
            PinKey.SlotA, PinKey.SlotB, PinKey.SlotC, PinKey.SlotD, PinKey.SlotE,
            PinKey.SlotF, PinKey.SlotG, PinKey.SlotH, PinKey.SlotI, PinKey.SlotJ,
        )

        // The word sets below mirror app/src/main/sentences/<lang>/pin_key.yml; keep them in sync.
        // optional verbs that may precede a key; skipped when parsing a chain
        private val EN_VERBS = setOf("press", "tap", "type")
        private val DE_VERBS = setOf(
            "tipp", "tippe", "drück", "drücke", "drueck", "druecke",
            "wähl", "wähle", "waehl", "waehle",
        )

        // the "enter" word and synonyms (the only non-slot action allowed in a chain)
        private val EN_ENTERS = setOf("enter", "confirm", "submit", "done", "ok", "accept")
        private val DE_ENTERS = setOf("eingabe", "bestätigen", "bestaetigen", "fertig", "ok", "enter")

        // "delete" words: recognized so they are not treated as foreign, but never chainable
        private val EN_DELETES = setOf("delete", "backspace", "clear", "remove")
        private val DE_DELETES = setOf("löschen", "loeschen", "entfernen", "rücktaste", "ruecktaste")

        private fun verbWords(language: String) = if (language == "de") DE_VERBS else EN_VERBS
        private fun enterWords(language: String) = if (language == "de") DE_ENTERS else EN_ENTERS
        private fun deleteWords(language: String) = if (language == "de") DE_DELETES else EN_DELETES
    }
}
