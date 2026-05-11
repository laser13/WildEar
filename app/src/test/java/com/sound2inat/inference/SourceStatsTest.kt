package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceStatsTest {

    @Test fun `encode round-trips through decode`() {
        val input = mapOf(
            "birdnet_v2_4" to SourceStat(0.85f, 3, 500L, 12_000L),
            "perch_v2" to SourceStat(0.62f, 1, 3_000L, 6_000L),
        )
        val encoded = SourceStats.encode(input)!!
        val decoded = SourceStats.decode(encoded)
        assertThat(decoded).isEqualTo(input)
    }

    @Test fun `encode returns null for empty map`() {
        assertThat(SourceStats.encode(emptyMap())).isNull()
    }

    @Test fun `decode null returns empty map`() {
        assertThat(SourceStats.decode(null)).isEmpty()
    }

    @Test fun `decode blank string returns empty map`() {
        assertThat(SourceStats.decode("   ")).isEmpty()
    }

    @Test fun `decode old format (conf only) returns empty map — caller uses aggregated columns`() {
        // Rows written before this feature only have "src=conf", no colon fields.
        assertThat(SourceStats.decode("birdnet_v2_4=0.85")).isEmpty()
    }

    @Test fun `decode skips malformed tokens silently`() {
        val result = SourceStats.decode("bad;birdnet_v2_4=0.85:3:500:12000;=also-bad;key=")
        assertThat(result).hasSize(1)
        assertThat(result["birdnet_v2_4"]).isEqualTo(SourceStat(0.85f, 3, 500L, 12_000L))
    }

    @Test fun `decodeConfidenceOnly handles new format`() {
        val text = "birdnet_v2_4=0.85:3:500:12000;perch_v2=0.62:1:3000:6000"
        val result = SourceStats.decodeConfidenceOnly(text)
        assertThat(result).containsExactly("birdnet_v2_4", 0.85f, "perch_v2", 0.62f)
    }

    @Test fun `decodeConfidenceOnly handles old format (conf only)`() {
        val result = SourceStats.decodeConfidenceOnly("birdnet_v2_4=0.85;perch_v2=0.62")
        assertThat(result).containsExactly("birdnet_v2_4", 0.85f, "perch_v2", 0.62f)
    }

    @Test fun `encode sorts entries by key for deterministic output`() {
        val input = mapOf(
            "perch_v2" to SourceStat(0.62f, 1, 0L, 5_000L),
            "birdnet_v2_4" to SourceStat(0.85f, 3, 0L, 9_000L),
        )
        val encoded = SourceStats.encode(input)!!
        assertThat(encoded.indexOf("birdnet")).isLessThan(encoded.indexOf("perch"))
    }

    @Test fun `encode rejects key containing equals sign`() {
        val stat = SourceStat(0.8f, 1, 0L, 3_000L)
        try {
            SourceStats.encode(mapOf("bad=key" to stat))
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("bad=key")
        }
    }

    @Test fun `encode rejects key containing semicolon`() {
        val stat = SourceStat(0.8f, 1, 0L, 3_000L)
        try {
            SourceStats.encode(mapOf("bad;key" to stat))
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("bad;key")
        }
    }

    @Test fun `encode rejects key containing colon`() {
        val stat = SourceStat(0.8f, 1, 0L, 3_000L)
        try {
            SourceStats.encode(mapOf("bad:key" to stat))
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("bad:key")
        }
    }
}
