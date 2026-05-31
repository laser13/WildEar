package com.sound2inat.app.ui.spectrogram

/**
 * Compatibility aliases so the large Compose/Review UI surface keeps referring to
 * the historical type names while the DSP render core lives in com.sound2inat.audio
 * (Phase 4 architecture-boundary move). New code should import the audio types
 * directly. These aliases can be deleted once all UI call sites are migrated.
 */
typealias SpectrogramPalette = com.sound2inat.audio.SpectrogramPalette
typealias SpectrogramColorMap = com.sound2inat.audio.SpectrogramColorMap
typealias SpectrogramRenderProfile = com.sound2inat.audio.SpectrogramRenderProfile
typealias SpectrogramVisualPipeline = com.sound2inat.audio.SpectrogramVisualPipeline
