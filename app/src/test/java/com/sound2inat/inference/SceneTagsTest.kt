package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SceneTagsTest {

    @Test
    fun `toJson emits all five fields with locale-independent decimal formatting`() {
        val tags = SceneTags(bird = 0.85f, owl = 0.05f, frog = 0.02f, insect = 0.42f, mammal = 0.11f)
        val json = tags.toJson()
        assertThat(json).contains("\"bird\":0.85")
        assertThat(json).contains("\"owl\":0.05")
        assertThat(json).contains("\"frog\":0.02")
        assertThat(json).contains("\"insect\":0.42")
        assertThat(json).contains("\"mammal\":0.11")
    }

    @Test
    fun `fromJson parses valid input`() {
        val json = """{"bird":0.85,"owl":0.05,"frog":0.02,"insect":0.42,"mammal":0.11}"""
        val tags = SceneTags.fromJson(json)
        assertThat(tags).isNotNull()
        assertThat(tags!!.bird).isWithin(1e-4f).of(0.85f)
        assertThat(tags.owl).isWithin(1e-4f).of(0.05f)
        assertThat(tags.frog).isWithin(1e-4f).of(0.02f)
        assertThat(tags.insect).isWithin(1e-4f).of(0.42f)
        assertThat(tags.mammal).isWithin(1e-4f).of(0.11f)
    }

    @Test
    fun `fromJson tolerates whitespace and ordering changes`() {
        val json = """  {  "mammal" : 0.11 , "bird" : 0.85, "owl":0.05, "frog":0.02, "insect":0.42 }  """
        val tags = SceneTags.fromJson(json)
        assertThat(tags).isNotNull()
        assertThat(tags!!.mammal).isWithin(1e-4f).of(0.11f)
    }

    @Test
    fun `fromJson returns null for malformed input`() {
        assertThat(SceneTags.fromJson("")).isNull()
        assertThat(SceneTags.fromJson("not json")).isNull()
        assertThat(SceneTags.fromJson("""{"bird":0.85}""")).isNull() // missing fields
        assertThat(SceneTags.fromJson("""{"bird":"x","owl":0,"frog":0,"insect":0,"mammal":0}""")).isNull()
    }

    @Test
    fun `round-trip preserves values`() {
        val original = SceneTags(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        val parsed = SceneTags.fromJson(original.toJson())
        assertThat(parsed).isEqualTo(original)
    }
}
