package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SceneTagsAggregatorTest {
    @Test
    fun `merge takes pairwise max of two scene tags`() {
        val a = SceneTags(0.1f, 0.7f, 0.0f, 0.5f, 0.2f)
        val b = SceneTags(0.5f, 0.2f, 0.9f, 0.3f, 0.4f)
        val merged = SceneTagsAggregator.merge(a, b)
        assertThat(merged).isEqualTo(SceneTags(0.5f, 0.7f, 0.9f, 0.5f, 0.4f))
    }

    @Test
    fun `merging with EMPTY is identity`() {
        val a = SceneTags(0.1f, 0.7f, 0.0f, 0.5f, 0.2f)
        assertThat(SceneTagsAggregator.merge(a, SceneTags.EMPTY)).isEqualTo(a)
        assertThat(SceneTagsAggregator.merge(SceneTags.EMPTY, a)).isEqualTo(a)
    }

    @Test
    fun `merge is commutative for arbitrary values`() {
        val a = SceneTags(0.9f, 0.1f, 0.5f, 0.4f, 0.0f)
        val b = SceneTags(0.2f, 0.3f, 0.5f, 0.4f, 0.7f)
        assertThat(SceneTagsAggregator.merge(a, b)).isEqualTo(SceneTagsAggregator.merge(b, a))
    }
}
