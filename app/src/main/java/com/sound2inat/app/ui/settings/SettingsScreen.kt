package com.sound2inat.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sound2inat.modelmanager.ModelInstallState

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val hilt: SettingsViewModelHilt = hiltViewModel()
    val vm = hilt.delegate
    val state by vm.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
        )
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ModelSection(state, vm)
            HorizontalDivider()
            InferenceSection(state, vm)
            HorizontalDivider()
            AboutSection()
        }
    }

    if (state.showLicenseSheet) {
        ModalBottomSheet(onDismissRequest = { vm.cancelLicenseSheet() }) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Install ${state.modelDisplayName}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "License: ${state.modelLicense}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${"%,d".format(state.modelSizeBytes)} bytes will be downloaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.confirmInstall() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Confirm and download")
                }
                OutlinedButton(onClick = { vm.cancelLicenseSheet() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ModelSection(state: SettingsUiState, vm: SettingsViewModel) {
    Text("Model", style = MaterialTheme.typography.titleMedium)
    Text(state.modelDisplayName, style = MaterialTheme.typography.bodyLarge)
    when (val s = state.modelInstall) {
        is ModelInstallState.Ready -> {
            Text("Status: Installed (${state.modelSizeBytes / 1024 / 1024} MB)", style = MaterialTheme.typography.bodyMedium)
            Text("License: ${state.modelLicense}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.openLicenseSheet() }) { Text("Reinstall") }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { vm.remove() }) { Text("Remove") }
        }
        is ModelInstallState.Downloading -> {
            Text("Status: Downloading\u2026", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
        }
        is ModelInstallState.Verifying -> {
            Text("Status: Verifying SHA-256\u2026", style = MaterialTheme.typography.bodyMedium)
        }
        is ModelInstallState.Failed -> {
            Text("Status: Failed \u2014 ${s.message}", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.openLicenseSheet() }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        }
        is ModelInstallState.NotInstalled -> {
            Text("Status: Not installed", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.openLicenseSheet() }, modifier = Modifier.fillMaxWidth()) { Text("Install model") }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun InferenceSection(state: SettingsUiState, vm: SettingsViewModel) {
    Text("Inference", style = MaterialTheme.typography.titleMedium)
    Text("Top K species: ${state.topK}")
    Slider(
        value = state.topK.toFloat(),
        onValueChange = { vm.setTopK(it.toInt().coerceIn(MIN_TOP_K, MAX_TOP_K)) },
        valueRange = MIN_TOP_K.toFloat()..MAX_TOP_K.toFloat(),
        steps = MAX_TOP_K - MIN_TOP_K - 1,
    )
    Text("Min display confidence: ${"%.2f".format(state.minConfidenceDisplay)}")
    Slider(
        value = state.minConfidenceDisplay,
        onValueChange = { vm.setMinConfidence(it) },
        valueRange = MIN_CONF..MAX_CONF,
    )
}

@Suppress("FunctionNaming")
@Composable
private fun AboutSection() {
    Text("About", style = MaterialTheme.typography.titleMedium)
    Text("Sound2iNat 0.1.0", style = MaterialTheme.typography.bodyMedium)
}

private const val MIN_TOP_K = 1
private const val MAX_TOP_K = 10
private const val MIN_CONF = 0.05f
private const val MAX_CONF = 0.90f
