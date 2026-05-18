package com.sound2inat.app.ui.review

import com.sound2inat.inference.SceneTags

/**
 * Heuristic mapper from common/scientific taxon names to [SceneTags] categories.
 *
 * Used as a fallback when YamNet did not produce confident scene data (or when
 * the model is not installed). The mapping is keyword-based on lowercased names
 * and intentionally narrow — anything that doesn't match a non-bird keyword is
 * treated as "bird" (BirdNET's training corpus is overwhelmingly birds, so this
 * is the safest default for an unknown name from a BirdNET detection).
 *
 * Each present keyword contributes a fully-confident (1.0) score on its category,
 * which makes the downstream [AutoDisplayRangePicker] pick the matching preset.
 * No partial scores: this is a discrete signal, not a probability.
 */
object TaxonCategoryHints {

    fun fromTaxonNames(names: Collection<String>): SceneTags {
        if (names.isEmpty()) return SceneTags.EMPTY
        var bird = 0f
        var owl = 0f
        var frog = 0f
        var insect = 0f
        var mammal = 0f
        for (name in names) {
            val lower = name.lowercase()
            when {
                anyContains(lower, OWL_KEYWORDS) -> owl = 1f
                anyContains(lower, FROG_KEYWORDS) -> frog = 1f
                anyContains(lower, INSECT_KEYWORDS) -> insect = 1f
                anyContains(lower, MAMMAL_KEYWORDS) -> mammal = 1f
                else -> bird = 1f
            }
        }
        return SceneTags(bird = bird, owl = owl, frog = frog, insect = insect, mammal = mammal)
    }

    private fun anyContains(haystack: String, keywords: Array<String>): Boolean {
        for (k in keywords) if (k in haystack) return true
        return false
    }

    private val OWL_KEYWORDS = arrayOf("owl", "strix", "bubo", "tyto", "asio", "athene")
    private val FROG_KEYWORDS = arrayOf("frog", "toad", "rana", "bufo", "hyla")
    private val INSECT_KEYWORDS = arrayOf(
        "cricket", "cicada", "grasshopper", "bee", "wasp", "hornet",
        "katydid", "beetle", "fly", "mosquito",
    )
    private val MAMMAL_KEYWORDS = arrayOf(
        "bat", "deer", "wolf", "fox", "coyote", "rat", "mouse",
        "squirrel", "raccoon", "boar", "bear", "moose", "elk",
    )
}
