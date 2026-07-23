package org.stypox.dicio.skills.grid

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.util.SpokenNumberParser
import org.stypox.dicio.voiceaccess.VoiceAccessService

/**
 * Shared parsing of a spoken grid cell reference like "a2" or "bravo two": one column token (a
 * plain letter a–h or a NATO/phonetic word), then a spoken row number. Returns 0-based [col] and
 * 1-based row, or null on any deviation. NATO words map through the service's full 10-word list on
 * purpose (see [VoiceAccessService.pinSlotForWord]), so "india 2" is resolved to a slot and later
 * answered out-of-range instead of leaking to another skill.
 *
 * Used by both [GridSkill] (tapping a cell) and the zoom skill (zooming at a cell).
 */
object GridCellReference {
    private val WHITESPACE = Regex("\\s+")

    fun parse(ctx: SkillContext, service: VoiceAccessService, text: String): Pair<Int, Int>? {
        val tokens = text.trim().lowercase().split(WHITESPACE).filter { it.isNotBlank() }
        return parse(ctx, service, tokens)
    }

    /** Parses already-tokenized input (lowercased, blank-free). */
    fun parse(ctx: SkillContext, service: VoiceAccessService, tokens: List<String>): Pair<Int, Int>? {
        if (tokens.isEmpty()) return null

        val letter = tokens[0]
        val col = if (letter.length == 1 && letter[0] in 'a'..'h') {
            letter[0] - 'a'
        } else {
            service.pinSlotForWord(letter) ?: return null
        }

        if (tokens.size < 2) return null
        val row = SpokenNumberParser.parse(ctx, tokens.subList(1, tokens.size).joinToString(" "))
            ?: return null
        if (row < 1) return null

        return col to row
    }
}
