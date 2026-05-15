package com.sound2inat.inference

/**
 * Element-wise max merge for [SceneTags]. Used by [InferenceRunner] to aggregate
 * per-window YamNet scene scores into a single per-recording vector, and by
 * [ProductionInferenceJob] to combine the per-model aggregates.
 *
 * "Max" is the right aggregation because [SceneTags] already stores the per-window
 * maximum across YamNet frames within that window — extending the max across
 * windows preserves the "strongest evidence anywhere in the recording" semantic
 * that the Review-screen Auto button relies on.
 */
object SceneTagsAggregator {
    fun merge(a: SceneTags, b: SceneTags): SceneTags = SceneTags(
        bird = maxOf(a.bird, b.bird),
        owl = maxOf(a.owl, b.owl),
        frog = maxOf(a.frog, b.frog),
        insect = maxOf(a.insect, b.insect),
        mammal = maxOf(a.mammal, b.mammal),
    )
}
