package com.sound2inat.inference

/**
 * Serialises per-source max confidence as a flat string so it fits a single
 * SQLite TEXT column without a join table. Format: `"src1=0.85;src2=0.62"`.
 *
 * Empty / blank input parses to an empty map; unknown tokens are skipped
 * silently rather than throwing — the column is best-effort metadata, never
 * load-bearing for the upload flow.
 */
object SourceConfidences {

    fun encode(map: Map<String, Float>): String? {
        if (map.isEmpty()) return null
        return map.entries
            .sortedBy { it.key }
            .joinToString(";") { (k, v) -> "$k=$v" }
    }

    fun decode(text: String?): Map<String, Float> {
        if (text.isNullOrBlank()) return emptyMap()
        return text.split(';')
            .mapNotNull { token ->
                val eq = token.indexOf('=')
                if (eq <= 0 || eq == token.length - 1) return@mapNotNull null
                val k = token.substring(0, eq).trim()
                val v = token.substring(eq + 1).trim().toFloatOrNull() ?: return@mapNotNull null
                k to v
            }
            .toMap()
    }
}
