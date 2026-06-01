package com.sound2inat.app.ui.review

import com.sound2inat.audio.SpectrogramDisplayPlane
import com.sound2inat.audio.SpectrogramPngRenderer
import com.sound2inat.audio.SpectrogramPreview

/** UI-layer bridge: colorize a display plane using a review config's palette. */
fun previewFromDisplayPlane(
    displayPlane: SpectrogramDisplayPlane,
    config: ReviewSpectrogramConfig,
): SpectrogramPreview = SpectrogramPngRenderer.colorize(displayPlane, config.effectivePalette)
