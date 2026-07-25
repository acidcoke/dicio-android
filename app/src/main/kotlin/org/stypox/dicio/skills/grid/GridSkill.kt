package org.stypox.dicio.skills.grid

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillGrammar
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Grid
import org.stypox.dicio.voiceaccess.VoiceAccessService

class GridSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Grid>,
    private val cellWords: List<String>,
) : StandardRecognizerSkill<Grid>(correspondingSkillInfo, data) {

    // the `.cell.` capture matches a column letter or phonetic word plus a spoken row number, none
    // of which the sentences spell out
    override val grammar: SkillGrammar
        get() = data.grammar + SkillGrammar.ofWords(cellWords)

    // The cell reference parsed from the utterance in score(), consumed by generateOutput() within
    // the same (sequential) utterance evaluation, mirroring PinKeySkill's pendingChain.
    @Volatile
    private var pendingCell: PendingCell? = null

    private data class PendingCell(val col: Int, val row: Int, val explicitPress: Boolean) {
        val name: String get() = "${'a' + col} $row"
    }

    override fun score(ctx: SkillContext, input: String): Pair<Score, Grid> {
        val (score, result) = super.score(ctx, input)
        // show/hide always pass through, so the grid can be toggled from any state
        if (result is Grid.Show || result is Grid.Hide) {
            pendingCell = null
            return Pair(score, result)
        }

        // cell references only make sense while the grid is up; the PIN pad wins over the grid
        // because it shares the NATO letter words and is the more security-sensitive surface
        val service = VoiceAccessService.instance
        if (service == null || !service.isGridActive() || service.isPinModeActive()) {
            pendingCell = null
            return Pair(AlwaysWorstScore, result)
        }

        // strict letter+number shape or bust, so scroll/back/…, and click_number's bare numbers,
        // keep working while the grid is up
        val parsed = parseCell(ctx, service, input)
        pendingCell = parsed
        return if (parsed == null) {
            Pair(AlwaysWorstScore, result)
        } else {
            Pair(AlwaysBestScore, result)
        }
    }

    /**
     * Parses a cell utterance: an optional press verb, then a column/row reference resolved by
     * [GridCellReference]. Any deviation returns null.
     */
    private fun parseCell(
        ctx: SkillContext,
        service: VoiceAccessService,
        input: String,
    ): PendingCell? {
        val tokens = input.trim().lowercase().split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        var i = 0
        var explicitPress = false
        if (tokens[i] in verbWords(ctx.sentencesLanguage)) {
            explicitPress = true
            i++
        }

        val (col, row) = GridCellReference.parse(ctx, service, tokens.subList(i, tokens.size))
            ?: return null
        return PendingCell(col, row, explicitPress)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Grid): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return GridOutput.ServiceDisabled

        return when (inputData) {
            is Grid.Show -> {
                service.showGrid()
                GridOutput.Shown
            }
            is Grid.Hide -> {
                service.hideGrid()
                GridOutput.Hidden
            }
            else -> {
                val cell = pendingCell ?: return GridOutput.NotUnderstood
                pendingCell = null
                when (service.handleGridCell(cell.col, cell.row, cell.explicitPress)) {
                    VoiceAccessService.GridCellResult.TAPPED -> GridOutput.Tapped(cell.name)
                    VoiceAccessService.GridCellResult.SUB_SHOWN -> GridOutput.SubShown(cell.name)
                    VoiceAccessService.GridCellResult.OUT_OF_RANGE -> GridOutput.OutOfRange(cell.name)
                }
            }
        }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        // The verb sets mirror app/src/main/sentences/<lang>/grid.yml; keep them in sync.
        private val EN_VERBS = setOf("press", "tap")
        private val DE_VERBS = setOf(
            "tipp", "tippe", "drück", "drücke", "drueck", "druecke",
            "wähl", "wähle", "waehl", "waehle",
        )

        private fun verbWords(language: String) = if (language == "de") DE_VERBS else EN_VERBS
    }
}
