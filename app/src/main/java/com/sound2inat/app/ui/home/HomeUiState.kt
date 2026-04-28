package com.sound2inat.app.ui.home

import com.sound2inat.storage.DraftStatus

data class DraftSummary(
    val id: String,
    val recordedAtUtcMs: Long,
    val durationMs: Long,
    val status: DraftStatus,
    val topLabel: String?,
)

data class HomeUiState(
    val isModelReady: Boolean = false,
    val drafts: List<DraftSummary> = emptyList(),
)
