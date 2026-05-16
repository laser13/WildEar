package com.sound2inat.app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewSettingsBottomSheet(
    state: ReviewUiState,
    profile: ReviewProcessingProfile,
    selectedTab: ReviewSettingsTab,
    onSelectedTabChange: (ReviewSettingsTab) -> Unit,
    onApply: (ReviewProcessingProfile) -> Unit,
    onDismiss: () -> Unit,
    sceneTagsAvailable: Boolean,
    onPressAuto: () -> Unit,
    onDisplayRangeChange: (SpectrogramDisplayRange?) -> Unit,
    onPaletteChange: (SpectrogramPalette?) -> Unit,
    onGainChange: (Float?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var audioDraftConfig by remember { mutableStateOf(profile.audioProcessingConfig) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                ReviewSettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { onSelectedTabChange(tab) },
                        text = {
                            Text(
                                when (tab) {
                                    ReviewSettingsTab.Audio -> "Audio"
                                    ReviewSettingsTab.Visual -> "Visual"
                                },
                            )
                        },
                    )
                }
            }
            if (selectedTab.showsConfirmationButtons()) {
                Text(
                    "Audio profile changes playback, analysis, and upload.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (selectedTab) {
                ReviewSettingsTab.Audio -> AudioSettingsTab(
                    config = audioDraftConfig,
                    onConfigChange = {
                        audioDraftConfig = it
                    },
                )
                ReviewSettingsTab.Visual -> VisualSettingsTab(
                    config = profile.spectrogramConfig,
                    sceneTagsAvailable = sceneTagsAvailable,
                    onPressAuto = onPressAuto,
                    onDisplayRangeChange = onDisplayRangeChange,
                    onPaletteChange = onPaletteChange,
                    onGainChange = onGainChange,
                )
            }
            when {
                state.visualsLoading || state.processingAudio -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        if (state.processingAudio) "Updating audio..." else "Rendering preview...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.visualsError != null -> Text(
                    state.visualsError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                state.processedAudioPath != null -> Text(
                    "Preview ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectedTab.showsConfirmationButtons()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onApply(profile.copy(audioProcessingConfig = audioDraftConfig))
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Apply")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AudioSettingsTab(
    config: ReviewAudioProcessingConfig,
    onConfigChange: (ReviewAudioProcessingConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProcessingPresetChip(
                preset = ReviewAudioProcessingConfig.Original,
                selected = config == ReviewAudioProcessingConfig.Original,
                onClick = { onConfigChange(ReviewAudioProcessingConfig.Original) },
                modifier = Modifier.weight(1f),
            )
            ProcessingPresetChip(
                preset = ReviewAudioProcessingConfig.BirdClean,
                selected = config == ReviewAudioProcessingConfig.BirdClean,
                onClick = { onConfigChange(ReviewAudioProcessingConfig.BirdClean) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProcessingPresetChip(
                preset = ReviewAudioProcessingConfig.BirdHighClarity,
                selected = config == ReviewAudioProcessingConfig.BirdHighClarity,
                onClick = { onConfigChange(ReviewAudioProcessingConfig.BirdHighClarity) },
                modifier = Modifier.weight(1f),
            )
            ProcessingPresetChip(
                preset = ReviewAudioProcessingConfig.BoostQuiet,
                selected = config == ReviewAudioProcessingConfig.BoostQuiet,
                onClick = { onConfigChange(ReviewAudioProcessingConfig.BoostQuiet) },
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()
        Text("High-pass", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            reviewHighPassOptions().forEach { hz ->
                FilterChip(
                    selected = config.highPassHz == hz,
                    onClick = { onConfigChange(config.withHighPass(hz)) },
                    label = { Text(formatHighPassLabel(hz)) },
                )
            }
        }
        Text("Gain", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            reviewGainOptions().forEach { gain ->
                FilterChip(
                    selected = config.gainDb == gain,
                    onClick = { onConfigChange(config.withGain(gain)) },
                    label = { Text(formatGainLabel(gain)) },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Normalize", style = MaterialTheme.typography.labelMedium)
            Switch(
                checked = config.normalizePeak,
                onCheckedChange = { enabled -> onConfigChange(config.withNormalize(enabled)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VisualSettingsTab(
    config: ReviewSpectrogramConfig,
    sceneTagsAvailable: Boolean,
    onPressAuto: () -> Unit,
    onDisplayRangeChange: (SpectrogramDisplayRange?) -> Unit,
    onPaletteChange: (SpectrogramPalette?) -> Unit,
    onGainChange: (Float?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onPressAuto,
            enabled = sceneTagsAvailable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(if (sceneTagsAvailable) "Auto" else "Auto (no scene data)")
        }
        Text("Range", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = config.displayRange == null,
                onClick = { onDisplayRangeChange(null) },
                label = { Text("Default") },
            )
            SpectrogramDisplayRange.entries.forEach { range ->
                FilterChip(
                    selected = config.displayRange == range,
                    onClick = { onDisplayRangeChange(range) },
                    label = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                range.displayName,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                range.rangeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
        Text("Palette", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = config.palette == null,
                onClick = { onPaletteChange(null) },
                label = { Text("Default") },
            )
            SpectrogramPalette.entries.filter { it != SpectrogramPalette.INK }.forEach { palette ->
                FilterChip(
                    selected = config.palette == palette,
                    onClick = { onPaletteChange(palette) },
                    label = { Text(paletteLabel(palette)) },
                )
            }
        }
        Text("Visual gain", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = config.gainDb == null,
                onClick = { onGainChange(null) },
                label = { Text("Default") },
            )
            reviewVisualGainOptions().forEach { gain ->
                FilterChip(
                    selected = config.gainDb == gain,
                    onClick = { onGainChange(gain) },
                    label = { Text(formatGainLabel(gain)) },
                )
            }
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

private fun paletteLabel(palette: SpectrogramPalette): String =
    when (palette) {
        SpectrogramPalette.VIRIDIS -> "Viridis"
        SpectrogramPalette.MAGMA -> "Magma"
        SpectrogramPalette.GRAY -> "Gray"
        SpectrogramPalette.INK -> "Ink"
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

private fun reviewVisualGainOptions(): List<Float> = listOf(-10f, -5f, 0f, 5f, 10f)
