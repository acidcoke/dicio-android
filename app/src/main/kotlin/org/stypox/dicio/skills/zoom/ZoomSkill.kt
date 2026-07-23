package org.stypox.dicio.skills.zoom

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
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
) : StandardRecognizerSkill<Zoom>(correspondingSkillInfo, data) {

    // The cell reference parsed from the utterance in score(), consumed by generateOutput() within
    // the same (sequential) utterance evaluation, mirroring GridSkill's pendingCell.
    @Volatile
    private var pendingCell: Pair<Int, Int>? = null

    override fun score(ctx: SkillContext, input: String): Pair<Score, Zoom> {
        val (score, result) = super.score(ctx, input)

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
}
