package org.stypox.dicio.skills.zoom

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillGrammar
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Zoom
import org.stypox.dicio.skills.grid.GridCellReference
import org.stypox.dicio.voiceaccess.VoiceAccessService

class ZoomSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Zoom>,
    private val cellWords: List<String>,
) : StandardRecognizerSkill<Zoom>(correspondingSkillInfo, data) {

    // the `.cell.` capture matches a grid cell reference, which the sentences don't spell out
    override val grammar: SkillGrammar
        get() = sentencesGrammar + SkillGrammar.ofWords(cellWords)

    // The cell reference parsed from the utterance in score(), consumed by generateOutput() within
    // the same (sequential) utterance evaluation, mirroring GridSkill's pendingCell.
    @Volatile
    private var pendingCell: Pair<Int, Int>? = null

    override fun score(ctx: SkillContext, input: String): Pair<Score, Zoom> {
        val (score, result) = super.score(ctx, input)

        // The standard recognizer allows partial/fuzzy matches, so a bare grid cell like "alpha 2"
        // can be matched to ".cell. zoom in" with the verb words missing. Only act when the utterance
        // actually contains a zoom keyword; otherwise defer (e.g. so "alpha 2" opens the grid
        // sub-grid instead of being hijacked as a zoom-at-cell command).
        if (!hasZoomKeyword(ctx.sentencesLanguage, input)) {
            pendingCell = null
            return Pair(AlwaysWorstScore, result)
        }

        // plain (center) zoom always passes through and works anywhere the service is running
        val cellText = when (result) {
            is Zoom.ZoomInAt -> result.cell
            is Zoom.ZoomOutAt -> result.cell
            else -> {
                pendingCell = null
                return Pair(score, result)
            }
        }

        // a cell reference only makes sense while the grid is up; the PIN pad wins over the grid
        // because it shares the NATO letter words and is the more security-sensitive surface
        val service = VoiceAccessService.instance
        if (cellText == null || service == null || !service.isGridActive() ||
            service.isPinModeActive()
        ) {
            pendingCell = null
            return Pair(AlwaysWorstScore, result)
        }

        val parsed = GridCellReference.parse(ctx, service, cellText)
        pendingCell = parsed
        return if (parsed == null) {
            Pair(AlwaysWorstScore, result)
        } else {
            Pair(AlwaysBestScore, result)
        }
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Zoom): SkillOutput {
        val service = VoiceAccessService.instance
            ?: return ZoomOutput.ServiceDisabled

        return when (inputData) {
            is Zoom.ZoomIn -> {
                service.zoom(zoomIn = true)
                ZoomOutput.Zoomed(zoomIn = true, cell = null)
            }
            is Zoom.ZoomOut -> {
                service.zoom(zoomIn = false)
                ZoomOutput.Zoomed(zoomIn = false, cell = null)
            }
            is Zoom.ZoomInAt -> zoomAtCell(service, zoomIn = true)
            is Zoom.ZoomOutAt -> zoomAtCell(service, zoomIn = false)
        }
    }

    private fun zoomAtCell(service: VoiceAccessService, zoomIn: Boolean): SkillOutput {
        val (col, row) = pendingCell ?: return ZoomOutput.NotUnderstood
        pendingCell = null
        val name = "${'a' + col} $row"
        return if (service.zoomAtCell(col, row, zoomIn)) {
            ZoomOutput.Zoomed(zoomIn = zoomIn, cell = name)
        } else {
            ZoomOutput.OutOfRange(name)
        }
    }

    companion object {
        // Substrings that mark an utterance as a zoom command; keep in sync with the zoom words in
        // app/src/main/sentences/<lang>/zoom.yml. "zoom" covers the compound forms (hineinzoomen,
        // rauszoomen, …); vergrößer/verkleiner cover the German verbs.
        private val EN_ZOOM_KEYWORDS = listOf("zoom", "enlarge", "shrink")
        private val DE_ZOOM_KEYWORDS = listOf("zoom", "vergrößer", "vergrösser", "verkleiner")

        private fun hasZoomKeyword(language: String, input: String): Boolean {
            val lower = input.lowercase()
            val keywords = if (language == "de") DE_ZOOM_KEYWORDS else EN_ZOOM_KEYWORDS
            return keywords.any { lower.contains(it) }
        }
    }
}
