package org.stypox.dicio.skills.labels

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

class LabelsOutput(private val result: Result) : HeadlineSpeechSkillOutput {
    enum class Result { SHOWED, HID, SERVICE_DISABLED }

    override fun getSpeechOutput(ctx: SkillContext): String = when (result) {
        Result.SHOWED -> ctx.getString(R.string.skill_labels_showed)
        Result.HID -> ctx.getString(R.string.skill_labels_hid)
        Result.SERVICE_DISABLED -> ctx.getString(R.string.skill_labels_service_disabled)
    }
}
