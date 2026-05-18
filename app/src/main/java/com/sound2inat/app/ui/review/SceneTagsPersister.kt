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
    /**
     * Persists [sceneTags] on the draft and, if no manual displayRange is set,
     * applies an Auto pick. When YamNet was not confident enough,
     * [taxonNamesHint] (e.g. detection labels already on the draft) is used as
     * a fallback signal — so even drafts without scene data still land on a
     * sensible preset.
     */
    suspend fun persistAndApplyAuto(
        repo: DraftRepository,
        draftId: String,
        sceneTags: SceneTags,
        taxonNamesHint: Collection<String> = emptyList(),
    ) {
        if (sceneTags == SceneTags.EMPTY && taxonNamesHint.isEmpty()) return
        if (sceneTags != SceneTags.EMPTY) {
            repo.updateSceneTags(draftId, sceneTags.toJson())
        }
        if (repo.getDisplayRangeName(draftId) != null) return
        val picked = AutoDisplayRangePicker.pickDisplayRangeWithFallback(
            sceneTags = sceneTags.takeIf { it != SceneTags.EMPTY },
            taxonNames = taxonNamesHint,
        ) ?: return
        repo.updateDisplayRange(draftId, picked.name)
    }
}
