package com.sound2inat.inference

data class SourceStat(
    val maxConf: Float,
    val windows: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

/**
 * Serialises per-source detection stats as a flat string for the `sources`
 * TEXT column. Format: `"src1=conf:windows:firstMs:lastMs;src2=…"`.
 *
 * Backward-compatible with the v4 confidence-only format `"src=conf"`:
 * [decode] returns an empty map for those rows (caller falls back to
 * the aggregated `detectedWindows`/`firstSeenMs`/`lastSeenMs` columns).
 * [decodeConfidenceOnly] handles both formats and always returns the
 * confidence map (used by ReviewViewModel / SpeciesDetailsSheet).
 */
object SourceStats {

    fun encode(map: Map<String, SourceStat>): String? {
        if (map.isEmpty()) return null
        return map.entries
            .sortedBy { it.key }
            .joinToString(";") { (k, v) ->
                "$k=${v.maxConf}:${v.windows}:${v.firstSeenMs}:${v.lastSeenMs}"
            }
    }

    fun decode(text: String?): Map<String, SourceStat> {
        if (text.isNullOrBlank()) return emptyMap()
        return buildMap {
            for (token in text.split(';')) {
                val eq = token.indexOf('=')
                if (eq <= 0 || eq == token.length - 1) continue
                val key = token.substring(0, eq).trim()
                val parts = token.substring(eq + 1).trim().split(':')
                if (parts.size < 4) continue  // old format or corrupt — skip
                val conf    = parts[0].toFloatOrNull() ?: continue
                val windows = parts[1].toIntOrNull()  ?: continue
                val firstMs = parts[2].toLongOrNull()  ?: continue
                val lastMs  = parts[3].toLongOrNull()  ?: continue
                put(key, SourceStat(conf, windows, firstMs, lastMs))
            }
        }
    }

    /** Returns per-source max confidence. Handles both old and new format. */
    fun decodeConfidenceOnly(text: String?): Map<String, Float> {
        if (text.isNullOrBlank()) return emptyMap()
        return buildMap {
            for (token in text.split(';')) {
                val eq = token.indexOf('=')
                if (eq <= 0 || eq == token.length - 1) continue
                val key  = token.substring(0, eq).trim()
                val conf = token.substring(eq + 1).trim().split(':')[0].toFloatOrNull() ?: continue
                put(key, conf)
            }
        }
    }
}