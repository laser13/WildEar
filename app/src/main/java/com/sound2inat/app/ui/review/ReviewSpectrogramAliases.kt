package com.sound2inat.app.ui.review

import com.sound2inat.audio.SpectrogramDisplayPlane
import com.sound2inat.audio.SpectrogramPngRenderer
import com.sound2inat.audio.SpectrogramPreview

typealias ReviewSpectrogramDisplayPlane = com.sound2inat.audio.SpectrogramDisplayPlane
typealias ReviewSpectrogramPreview = com.sound2inat.audio.SpectrogramPreview
typealias LiveStyleReviewRenderer = com.sound2inat.audio.SpectrogramPngRenderer
typealias RenderedSpectrogram = com.sound2inat.audio.RenderedSpectrogram
typealias SpectrogramBitmap = com.sound2inat.audio.SpectrogramBitmap

/** UI-layer bridge: colorize a display plane using a review config's palette. */
fun previewFromDisplayPlane(
    displayPlane: SpectrogramDisplayPlane,
    config: ReviewSpectrogramConfig,
): SpectrogramPreview = SpectrogramPngRenderer.colorize(displayPlane, config.effectivePalette)
