package org.stypox.dicio.voiceaccess

import org.stypox.dicio.settings.datastore.LabelTheme

/**
 * User-configurable appearance of the numbered labels overlay.
 *
 * @param opacity chip opacity, 0..1
 * @param contrast spread between the chip and text tones, 0..1 (higher = more contrast)
 * @param dark true for a dark chip with light text (the classic Voice Access look)
 */
data class LabelStyle(
    val opacity: Float,
    val contrast: Float,
    val dark: Boolean,
) {
    companion object {
        const val DEFAULT_OPACITY_PERCENT = 100
        const val DEFAULT_CONTRAST_PERCENT = 85

        val DEFAULT = LabelStyle(
            opacity = DEFAULT_OPACITY_PERCENT / 100f,
            contrast = DEFAULT_CONTRAST_PERCENT / 100f,
            dark = true,
        )

        /** Builds a style from the raw datastore values, applying defaults for unset (0) values. */
        fun from(opacityPercent: Int, contrastPercent: Int, theme: LabelTheme): LabelStyle {
            val opacity = (if (opacityPercent == 0) DEFAULT_OPACITY_PERCENT else opacityPercent)
            val contrast = (if (contrastPercent == 0) DEFAULT_CONTRAST_PERCENT else contrastPercent)
            return LabelStyle(
                opacity = opacity / 100f,
                contrast = contrast / 100f,
                dark = theme != LabelTheme.LABEL_THEME_LIGHT,
            )
        }
    }
}
