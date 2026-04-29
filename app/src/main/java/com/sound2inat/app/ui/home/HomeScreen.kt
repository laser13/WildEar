package com.sound2inat.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sound2inat.storage.DraftStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRecord: () -> Unit,
    onOpenDraft: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val vm: HomeViewModelHilt = hiltViewModel()
    val state by vm.delegate.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshModelState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sound2iNat") },
            actions = { IconButton(onClick = onSettings) { Text("⚙") } },
        )
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.Top) {
            Button(
                onClick = onRecord,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) {
                Text("● RECORD")
            }
            if (!state.isModelReady) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Model not installed — analysis will run after you install it in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Drafts", style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            items(state.drafts, key = { it.id }) { d ->
                DraftRow(d, vm = vm, onClick = { onOpenDraft(d.id) })
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun DraftRow(
    summary: DraftSummary,
    vm: HomeViewModelHilt,
    onClick: () -> Unit,
) {
    val topLabel by remember(summary.id) { vm.observeTopLabel(summary.id) }.collectAsState(initial = null)
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = {
            Text("${formatTimestamp(summary.recordedAtUtcMs)}  ·  ${formatDuration(summary.durationMs)}")
        },
        supportingContent = {
            Text(
                topLabel ?: when (summary.status) {
                    DraftStatus.PENDING_INFERENCE -> "Analyzing…"
                    DraftStatus.PENDING_REVIEW -> "Awaiting review"
                    DraftStatus.REVIEWED -> "—"
                },
            )
        },
    )
    HorizontalDivider()
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / MS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
