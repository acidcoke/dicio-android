package org.stypox.dicio.skills.click_number

/**
 * Minimal spelled-out German number parser for label selection (0..999).
 *
 * dicio-numbers has no German [org.dicio.numbers.ParserFormatter], so when the STT returns a
 * German number word like "fünf" or "einundzwanzig" the normal parsing path yields null. Label
 * numbers are always small sequential integers, so a compact hand-rolled parser is enough.
 */
object GermanNumberParser {

    private val units = mapOf(
        "null" to 0,
        "ein" to 1, "eins" to 1, "eine" to 1,
        "zwei" to 2, "zwo" to 2,
        "drei" to 3,
        "vier" to 4,
        "fünf" to 5, "fuenf" to 5,
        "sechs" to 6,
        "sieben" to 7,
        "acht" to 8,
        "neun" to 9,
    )

    private val teens = mapOf(
        "zehn" to 10,
        "elf" to 11,
        "zwölf" to 12, "zwoelf" to 12,
        "dreizehn" to 13,
        "vierzehn" to 14,
        "fünfzehn" to 15, "fuenfzehn" to 15,
        "sechzehn" to 16,
        "siebzehn" to 17,
        "achtzehn" to 18,
        "neunzehn" to 19,
    )

    private val tens = mapOf(
        "zwanzig" to 20,
        "dreißig" to 30, "dreissig" to 30,
        "vierzig" to 40,
        "fünfzig" to 50, "fuenfzig" to 50,
        "sechzig" to 60,
        "siebzig" to 70,
        "achtzig" to 80,
        "neunzig" to 90,
    )

    /** Parse a single German number word (0..999), or null if it isn't one. */
    fun parse(text: String): Int? {
        val word = text.trim().lowercase().replace(" ", "").replace("-", "")
        if (word.isEmpty()) return null

        // hundreds: "[N]hundert[rest]"
        val hundertIdx = word.indexOf("hundert")
        if (hundertIdx >= 0) {
            val before = word.substring(0, hundertIdx)
            val after = word.substring(hundertIdx + "hundert".length)
            val hundreds = if (before.isEmpty()) 1 else units[before] ?: return null
            val rest = if (after.isEmpty()) 0 else parseUnderHundred(after) ?: return null
            return hundreds * 100 + rest
        }

        return parseUnderHundred(word)
    }

    private fun parseUnderHundred(word: String): Int? {
        if (word.isEmpty()) return null
        units[word]?.let { return it }
        teens[word]?.let { return it }
        tens[word]?.let { return it }

        // compound: "[unit]und[tens]" e.g. "einundzwanzig"
        val undIdx = word.indexOf("und")
        if (undIdx > 0) {
            val unitPart = word.substring(0, undIdx)
            val tensPart = word.substring(undIdx + "und".length)
            val unit = units[unitPart] ?: return null
            val ten = tens[tensPart] ?: return null
            return ten + unit
        }
        return null
    }
}
