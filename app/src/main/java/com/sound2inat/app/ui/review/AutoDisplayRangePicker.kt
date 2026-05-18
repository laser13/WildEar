package com.sound2inat.app.ui.review

import com.sound2inat.inference.SceneTags

object AutoDisplayRangePicker {

    const val SCENE_TAG_THRESHOLD = 0.3f

    fun pickDisplayRange(tags: SceneTags): SpectrogramDisplayRange? {
        val active = buildList {
            if (tags.bird >= SCENE_TAG_THRESHOLD) add(SpectrogramDisplayRange.BIRDNET_BIRD)
            if (tags.owl >= SCENE_TAG_THRESHOLD) add(SpectrogramDisplayRange.OWL_LOW_VOICE)
            if (tags.frog >= SCENE_TAG_THRESHOLD) add(SpectrogramDisplayRange.INSECT_AMPHIBIAN)
            if (tags.insect >= SCENE_TAG_THRESHOLD) add(SpectrogramDisplayRange.INSECT_AMPHIBIAN)
            if (tags.mammal >= SCENE_TAG_THRESHOLD) add(SpectrogramDisplayRange.WILDLIFE)
        }.distinct()

        if (active.isEmpty()) return null
        if (active.size == 1) return active.single()

        val unionMin = active.minOf { it.fMinHz }
        val unionMax = active.maxOf { it.fMaxHz }
        return SpectrogramDisplayRange.entries
            .filter { it.fMinHz <= unionMin && it.fMaxHz >= unionMax }
            .minByOrNull { it.fMaxHz - it.fMinHz }
            ?: SpectrogramDisplayRange.FULL
    }

    /**
     * Picks a preset from [sceneTags] first; if that yields no confident category
     * the fallback maps [taxonNames] (BirdNET / live detection labels) to scene
     * categories via [TaxonCategoryHints] and tries again. Returns null when
     * both signals are silent.
     */
    fun pickDisplayRangeWithFallback(
        sceneTags: SceneTags?,
        taxonNames: Collection<String>,
    ): SpectrogramDisplayRange? {
        val primary = sceneTags?.let { pickDisplayRange(it) }
        if (primary != null) return primary
        if (taxonNames.isEmpty()) return null
        val hints = TaxonCategoryHints.fromTaxonNames(taxonNames)
        return pickDisplayRange(hints)
    }
}
