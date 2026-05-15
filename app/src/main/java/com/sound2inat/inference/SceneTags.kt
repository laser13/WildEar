package com.sound2inat.inference

import java.util.Locale

/**
 * Per-scene maximum probabilities aggregated across YamNet frames for a recording window.
 *
 * Each field holds the maximum of the YamNet class probabilities mapped to that scene
 * (e.g. [bird] is `max(probs[BIRD_CLASS_INDEX_i])` over all bird classes and frames).
 *
 * Serialised as compact JSON via [toJson] / [fromJson] for storage on the draft row.
 */
data class SceneTags(
    val bird: Float,
    val owl: Float,
    val frog: Float,
    val insect: Float,
    val mammal: Float,
) {
    fun toJson(): String = buildString {
        append('{')
        appendField("bird", bird)
        append(',')
        appendField("owl", owl)
        append(',')
        appendField("frog", frog)
        append(',')
        appendField("insect", insect)
        append(',')
        appendField("mammal", mammal)
        append('}')
    }

    private fun StringBuilder.appendField(name: String, value: Float) {
        append('"').append(name).append("\":")
        val trimmed = String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.')
        append(trimmed.ifEmpty { "0" })
    }

    companion object {
        val EMPTY = SceneTags(0f, 0f, 0f, 0f, 0f)

        fun fromJson(input: String?): SceneTags? {
            if (input.isNullOrBlank()) return null
            return runCatching { parseInternal(input) }.getOrNull()
        }

        private fun parseInternal(input: String): SceneTags? {
            val trimmed = input.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
            val body = trimmed.substring(1, trimmed.length - 1)
            val fields = mutableMapOf<String, Float>()
            for (raw in body.split(',')) {
                val colon = raw.indexOf(':')
                if (colon < 0) return null
                val key = raw.substring(0, colon).trim().trim('"')
                val valueStr = raw.substring(colon + 1).trim()
                val value = valueStr.toFloatOrNull() ?: return null
                fields[key] = value
            }
            val bird = fields["bird"] ?: return null
            val owl = fields["owl"] ?: return null
            val frog = fields["frog"] ?: return null
            val insect = fields["insect"] ?: return null
            val mammal = fields["mammal"] ?: return null
            return SceneTags(bird, owl, frog, insect, mammal)
        }
    }
}
