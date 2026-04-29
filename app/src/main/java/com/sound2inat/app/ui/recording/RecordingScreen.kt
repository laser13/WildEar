package com.sound2inat.app.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sound2inat.app.permissions.LocalPermissionsController

@Suppress("FunctionNaming")
@Composable
fun RecordingScreen(
    onDone: (draftId: String) -> Unit,
    onCancel: () -> Unit,
) {
    val perms = LocalPermissionsController.current
    val hilt: RecordingViewModelHilt = hiltViewModel()
    val vm = remember(hilt) { hilt.factory(perms) }
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.start() }

    when (val s = state) {
        is RecordingUiState.Idle -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Starting…")
        }
        is RecordingUiState.Recording -> RecordingBody(
            s = s,
            onStop = { vm.stop() },
            onCancel = {
                vm.cancel()
                onCancel()
            },
        )
        is RecordingUiState.Done -> LaunchedEffect(s.draftId) { onDone(s.draftId) }
        is RecordingUiState.Error -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column {
                Text(s.message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onCancel) { Text("Back") }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun RecordingBody(
    s: RecordingUiState.Recording,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.Start)) { Text("Cancel") }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatElapsed(s.elapsedMs), style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { s.rms.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                when (val gps = s.gps) {
                    is GpsStatus.Acquiring -> "GPS: acquiring…"
                    is GpsStatus.Fix -> "GPS: %.4f, %.4f%s".format(
                        gps.latitude,
                        gps.longitude,
                        gps.accuracyMeters?.let { " (±%.0f m)".format(it) }.orEmpty(),
                    )
                    is GpsStatus.NoFix -> "No GPS fix; draft will be saved without location"
                },
            )
            if (s.warningSoftLimit) {
                Spacer(Modifier.height(8.dp))
                Text("Long recording — auto-stop at 10:00", color = MaterialTheme.colorScheme.error)
            }
        }
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) { Text("■ STOP") }
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
