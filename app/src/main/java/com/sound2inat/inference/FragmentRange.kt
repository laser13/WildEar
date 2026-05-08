package com.sound2inat.inference

data class FragmentRange(val startMs: Long, val endMs: Long)

/**
 * Serialises a list of [FragmentRange] to/from a compact string so it fits a
 * single SQLite TEXT column. Format: `"startMs:endMs,startMs:endMs,…"`.
 *
 * Empty / blank input parses to an empty list; malformed tokens are skipped
 * silently — the column is best-effort debug metadata, never load-bearing.
 */
object FragmentRanges {

    fun encode(ranges: List<FragmentRange>): String {
        if (ranges.isEmpty()) return ""
        return ranges.joinToString(",") { "${it.startMs}:${it.endMs}" }
    }

    fun decode(value: String?): List<FragmentRange> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",").mapNotNull { token ->
            val colon = token.indexOf(':')
            if (colon <= 0 || colon == token.length - 1) return@mapNotNull null
            val s = token.substring(0, colon).trim().toLongOrNull() ?: return@mapNotNull null
            val e = token.substring(colon + 1).trim().toLongOrNull() ?: return@mapNotNull null
            FragmentRange(s, e)
        }
    }
}
