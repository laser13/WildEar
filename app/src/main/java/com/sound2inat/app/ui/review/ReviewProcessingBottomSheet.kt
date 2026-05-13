package com.sound2inat.app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewProcessingBottomSheet(
    state: ReviewUiState,
    onConfigSelected: (ReviewAudioProcessingConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Processing profile",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "One shared profile controls playback, analysis, and the spectrogram view. Original WAV is kept unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProcessingPresetChip(
                        preset = ReviewAudioProcessingConfig.Original,
                        selected = state.processingProfile.audioProcessingConfig == ReviewAudioProcessingConfig.Original,
                        onClick = { onConfigSelected(ReviewAudioProcessingConfig.Original) },
                        modifier = Modifier.weight(1f),
                    )
                    ProcessingPresetChip(
                        preset = ReviewAudioProcessingConfig.BirdClean,
                        selected = state.processingProfile.audioProcessingConfig == ReviewAudioProcessingConfig.BirdClean,
                        onClick = { onConfigSelected(ReviewAudioProcessingConfig.BirdClean) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProcessingPresetChip(
                        preset = ReviewAudioProcessingConfig.BirdHighClarity,
                        selected = state.processingProfile.audioProcessingConfig == ReviewAudioProcessingConfig.BirdHighClarity,
                        onClick = { onConfigSelected(ReviewAudioProcessingConfig.BirdHighClarity) },
                        modifier = Modifier.weight(1f),
                    )
                    ProcessingPresetChip(
                        preset = ReviewAudioProcessingConfig.BoostQuiet,
                        selected = state.processingProfile.audioProcessingConfig == ReviewAudioProcessingConfig.BoostQuiet,
                        onClick = { onConfigSelected(ReviewAudioProcessingConfig.BoostQuiet) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider()
            Text(
                "High-pass",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                reviewHighPassOptions().forEach { hz ->
                    val selected = state.processingProfile.audioProcessingConfig.highPassHz == hz
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onConfigSelected(
                                state.processingProfile.audioProcessingConfig.withHighPass(hz),
                            )
                        },
                        label = { Text(formatHighPassLabel(hz)) },
                    )
                }
            }
            Text(
                "Gain",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                reviewGainOptions().forEach { gain ->
                    val selected = state.processingProfile.audioProcessingConfig.gainDb == gain
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onConfigSelected(
                                state.processingProfile.audioProcessingConfig.withGain(gain),
                            )
                        },
                        label = { Text(formatGainLabel(gain)) },
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Normalize",
                    style = MaterialTheme.typography.labelMedium,
                )
                Switch(
                    checked = state.processingProfile.audioProcessingConfig.normalizePeak,
                    onCheckedChange = { enabled ->
                        onConfigSelected(
                            state.processingProfile.audioProcessingConfig.withNormalize(enabled),
                        )
                    },
                )
            }
            when {
                state.processingAudio -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Updating preview...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.processedAudioPath != null -> Text(
                    "Preview ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProcessingPresetChip(
    preset: ReviewAudioProcessingConfig,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        label = { Text(audioProcessingLabel(preset)) },
    )
}

private fun audioProcessingLabel(config: ReviewAudioProcessingConfig): String =
    when (config.preset) {
        ReviewAudioProcessingConfig.Preset.ORIGINAL -> "Orig"
        ReviewAudioProcessingConfig.Preset.BIRD_CLEAN -> "Clean"
        ReviewAudioProcessingConfig.Preset.BIRD_HIGH_CLARITY -> "Clear"
        ReviewAudioProcessingConfig.Preset.BOOST_QUIET -> "Boost"
        ReviewAudioProcessingConfig.Preset.CUSTOM -> "Custom"
    }

private fun formatHighPassLabel(highPassHz: Int?): String =
    when (highPassHz) {
        null -> "Off"
        300 -> "300"
        600 -> "600"
        1_000 -> "1k"
        1_600 -> "1.6k"
        else -> "${highPassHz}Hz"
    }

private fun formatGainLabel(gainDb: Float): String =
    when {
        gainDb > 0f -> "+${gainDb.toInt()}"
        gainDb == 0f -> "0"
        else -> gainDb.toInt().toString()
    }
