package com.sound2inat.app.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import com.sound2inat.app.R
import com.sound2inat.app.permissions.LocalPermissionsController
import com.sound2inat.app.ui.theme.detectionCardLikelyDark
import com.sound2inat.app.ui.theme.detectionCardLikelyLight
import com.sound2inat.app.ui.theme.detectionCardUnlikelyDark
import com.sound2inat.app.ui.theme.detectionCardUnlikelyLight
import com.sound2inat.inference.RegionalStatus
import kotlinx.coroutines.flow.SharedFlow

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onDone: (draftId: String) -> Unit,
    onCancel: () -> Unit,
) {
    val perms = LocalPermissionsController.current
    val hilt: RecordingViewModelHilt = hiltViewModel()
    val vm = remember(hilt) { hilt.factory(perms) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.start() }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is RecordingUiState.Idle -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.recording_starting), style = MaterialTheme.typography.titleMedium)
                }
                is RecordingUiState.Recording -> RecordingBody(
                    s = s,
                    audioBlocks = vm.audioBlocks,
                    sampleRateHz = vm.sampleRateHz,
                    onStop = { vm.stop() },
                    onCancel = { vm.cancel(); onCancel() },
                    vm = vm,
                )
                is RecordingUiState.Done -> LaunchedEffect(s.draftId) { onDone(s.draftId) }
                is RecordingUiState.Error -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.btn_back)) }
                    }
                }
            }
        }
    }
}

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun RecordingBody(
    s: RecordingUiState.Recording,
    audioBlocks: SharedFlow<FloatArray>,
    sampleRateHz: Int,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    vm: RecordingViewModel,
) {
    // Pending photo state: (photoId, filePath) captured before launching camera.
    var pendingPhoto by remember { mutableStateOf<Pair<String, String>?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val pending = pendingPhoto
        if (pending != null) {
            val (pid, filePath) = pending
            if (success) {
                vm.onPhotoTaken(draftId = s.draftId, photoId = pid, photoPath = filePath)
            } else {
                vm.onPhotoCancelled(draftId = s.draftId, photoId = pid)
            }
            pendingPhoto = null
        }
    }

    val spectrogramBg = MaterialTheme.colorScheme.surface

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Live spectrogram — pressed to the top, no padding above
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(SPECTROGRAM_WEIGHT),
        ) {
            LiveSpectrogramView(
                audioBlocks = audioBlocks,
                sampleRateHz = sampleRateHz,
                backgroundColor = spectrogramBg,
                modifier = Modifier.fillMaxSize(),
            )
            // Close button overlaid on the spectrogram (top-left)
            IconButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_cancel))
            }
            // Backlog delay hint (bottom-left pill)
            if (s.backlogWindows > BACKLOG_VISIBLE_THRESHOLD) {
                val delaySeconds = (s.backlogWindows * BACKLOG_SECONDS_PER_WINDOW).roundToInt()
                Text(
                    stringResource(R.string.recording_backlog_hint, delaySeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(
                            color = Color.Black.copy(alpha = BACKLOG_PILL_ALPHA),
                            shape = RoundedCornerShape(BACKLOG_PILL_RADIUS_DP.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        // Live detections list (Merlin per-species cards)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(LIVE_CARDS_WEIGHT)
                .padding(horizontal = 16.dp),
        ) {
            if (s.liveCards.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.live_listening),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Split by regional filter result to mirror the Review screen:
                // anything not flagged NOT_CONFIRMED stays on top (likely +
                // pending lookup), confirmed-absent species drop into the
                // "Unlikely" section. With the filter disabled or GPS missing
                // every card has null status → all land on top, single section.
                val (likely, unlikely) = s.liveCards.partition {
                    it.regionalStatus != RegionalStatus.NOT_CONFIRMED
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            stringResource(R.string.live_matches_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(likely, key = { "likely-${it.scientificName}" }) { card ->
                        LiveCardRow(card = card)
                    }
                    if (unlikely.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.live_unlikely_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        items(unlikely, key = { "unlikely-${it.scientificName}" }) { card ->
                            LiveCardRow(card = card)
                        }
                    }
                }
            }
        }

        if (s.warningSoftLimit) {
            Text(
                stringResource(R.string.recording_auto_stop),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // Status row: elapsed time + camera button + GPS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatElapsed(s.elapsedMs), style = MaterialTheme.typography.headlineMedium)
            if (vm.hasCamera) {
                BadgedBox(
                    badge = {
                        if (s.habitatPhotoCount > 0) {
                            Badge { Text("${s.habitatPhotoCount}") }
                        }
                    },
                ) {
                    IconButton(
                        onClick = {
                            val pid = UUID.randomUUID().toString()
                            val prepared = vm.preparePhotoCapture(s.draftId, pid)
                            pendingPhoto = pid to prepared.filePath
                            cameraLauncher.launch(prepared.uri)
                        },
                    ) {
                        Icon(
                            Icons.Filled.CameraAlt,
                            contentDescription = stringResource(R.string.cd_take_habitat_photo),
                        )
                    }
                }
            }
            GpsIndicator(s.gps)
        }

        FilledIconButton(
            onClick = onStop,
            shape = CircleShape,
            modifier = Modifier
                .size(STOP_BUTTON_DP.dp)
                .padding(bottom = 8.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(
                Icons.Filled.Stop,
                contentDescription = stringResource(R.string.cd_stop),
                modifier = Modifier.size(STOP_ICON_DP.dp),
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun LiveCardRow(card: LiveCard) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val cardColor = if (card.regionalStatus == RegionalStatus.NOT_CONFIRMED)
        if (isDark) detectionCardUnlikelyDark else detectionCardUnlikelyLight
    else
        if (isDark) detectionCardLikelyDark else detectionCardLikelyLight
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = cardColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    card.commonName ?: card.scientificName,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (card.commonName != null) {
                    Text(
                        card.scientificName,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text("×${card.count}") },
                colors = AssistChipDefaults.assistChipColors(
                    disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Text(
                "%.0f%%".format(card.peakConfidence * PERCENT_SCALE),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Compact GPS state indicator. Shows the label "GPS" plus a coloured dot
 * (green = fix, amber = acquiring, red = no fix) instead of raw coordinates,
 * which were not actionable for the user.
 */
@Suppress("FunctionNaming")
@Composable
private fun GpsIndicator(gps: GpsStatus, modifier: Modifier = Modifier) {
    val color = when (gps) {
        is GpsStatus.Fix -> GPS_FIX_COLOR
        is GpsStatus.Acquiring -> GPS_ACQUIRING_COLOR
        is GpsStatus.NoFix -> GPS_NO_FIX_COLOR
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.label_gps), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(GPS_DOT_GAP_DP.dp))
        Box(
            modifier = Modifier
                .size(GPS_DOT_DP.dp)
                .background(color, CircleShape),
        )
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / MS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val STOP_BUTTON_DP = 96
private const val STOP_ICON_DP = 48
private const val SPECTROGRAM_WEIGHT = 0.3f
private const val LIVE_CARDS_WEIGHT = 1f
private const val PERCENT_SCALE = 100f

// LiveInferenceEngine emits one window every 1.5 s (3 s window, 50 % overlap),
// so each unprocessed window in the queue means ~1.5 s of detection delay.
private const val BACKLOG_SECONDS_PER_WINDOW = 1.5f
private const val BACKLOG_PILL_ALPHA = 0.55f
private const val BACKLOG_PILL_RADIUS_DP = 4

// Hide the indicator at backlog=1: that's the normal steady state when
// inference matches the real-time hop rate (one window queued while the
// worker finishes the previous). Show only when actually falling behind.
private const val BACKLOG_VISIBLE_THRESHOLD = 1

private const val GPS_DOT_DP = 10
private const val GPS_DOT_GAP_DP = 6
private val GPS_FIX_COLOR = Color(0xFF4CAF50)       // green
private val GPS_ACQUIRING_COLOR = Color(0xFFFFA000) // amber
private val GPS_NO_FIX_COLOR = Color(0xFFE53935)    // red
