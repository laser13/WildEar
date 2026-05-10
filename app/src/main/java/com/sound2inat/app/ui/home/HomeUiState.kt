package com.sound2inat.app.ui.home

import com.sound2inat.app.inference.JobStatus
import com.sound2inat.storage.DraftStatus

data class DraftSummary(
    val id: String,
    val recordedAtUtcMs: Long,
    val durationMs: Long,
    val status: DraftStatus,
    val topLabel: String?,
    val inatCount: Int = 0,
    val detectionCount: Int = 0,
    val jobStatus: JobStatus? = null,
)

data class HomeUiState(
    val isModelReady: Boolean = false,
    val drafts: List<DraftSummary> = emptyList(),
)

enum class FilterMode { ALL, UPLOADED, NOTHING_DETECTED }

data class BulkDeletePreview(
    val toDelete: List<DraftSummary>,
    val skippedUploaded: Int,
)
