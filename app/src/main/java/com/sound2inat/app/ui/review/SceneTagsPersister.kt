package com.sound2inat.app.ui.review

import com.sound2inat.inference.SceneTags
import com.sound2inat.storage.DraftRepository

/**
 * Stores per-recording YamNet [SceneTags] on a draft and, if the draft has no
 * explicit `displayRangeName` yet, applies an auto-picked preset on the user's
 * behalf. Manual picks are never overwritten.
 *
 * Used by both the post-inference path ([com.sound2inat.app.inference.InferenceQueue])
 * and the post-live-recording path ([com.sound2inat.app.recording.DefaultRecordingController]).
 */
object SceneTagsPersister {
    suspend fun persistAndApplyAuto(
        repo: DraftRepository,
        draftId: String,
        sceneTags: SceneTags,
    ) {
        if (sceneTags == SceneTags.EMPTY) return
        repo.updateSceneTags(draftId, sceneTags.toJson())
        if (repo.getDisplayRangeName(draftId) != null) return
        AutoDisplayRangePicker.pickDisplayRange(sceneTags)
            ?.let { repo.updateDisplayRange(draftId, it.name) }
    }
}
