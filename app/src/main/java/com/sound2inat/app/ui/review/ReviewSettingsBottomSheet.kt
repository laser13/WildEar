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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sound2inat.audio.SpectrogramPalette

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReviewSettingsBottomSheet(
    state: ReviewUiState,
    profile: ReviewProcessingProfile,
    onDismiss: () -> Unit,
    onPaletteChange: (SpectrogramPalette?) -> Unit,
    onContrastDelta: (Float) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Spectrogram", style = MaterialTheme.typography.titleLarge)

            Text("Palette", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpectrogramPalette.entries
                    .filter { it != SpectrogramPalette.INK }
                    .forEach { palette ->
                        FilterChip(
                            selected = profile.spectrogramConfig.palette == palette,
                            onClick = { onPaletteChange(palette) },
                            label = { Text(paletteLabel(palette)) },
                        )
                    }
            }

            Text("Contrast", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(onClick = { onContrastDelta(-CONTRAST_STEP_DB) }) {
                    Icon(
                        imageVector = Icons.Filled.Remove,
                        contentDescription = "Lower contrast",
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = formatContrastLabel(profile.spectrogramConfig.gainDb),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = { onContrastDelta(CONTRAST_STEP_DB) }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Raise contrast",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            if (state.visualsLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Rendering preview...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (state.visualsError != null) {
                Text(
                    state.visualsError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

private const val CONTRAST_STEP_DB = 5f

private fun paletteLabel(palette: SpectrogramPalette): String =
    when (palette) {
        SpectrogramPalette.VIRIDIS -> "Viridis"
        SpectrogramPalette.MAGMA -> "Magma"
        SpectrogramPalette.GRAY -> "Gray"
        SpectrogramPalette.INK -> "Ink"
    }

private fun formatContrastLabel(gainDb: Float?): String {
    val value = (gainDb ?: 0f).toInt()
    return when {
        value > 0 -> "+$value dB"
        value == 0 -> "0 dB"
        else -> "$value dB"
    }
}
