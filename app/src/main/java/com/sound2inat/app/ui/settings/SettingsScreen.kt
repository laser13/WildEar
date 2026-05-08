package com.sound2inat.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sound2inat.app.data.ThemeMode
import com.sound2inat.inat.INatWebLoginActivity
import com.sound2inat.modelmanager.ModelInstallState

@Suppress("FunctionNaming", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val hilt: SettingsViewModelHilt = hiltViewModel()
    val vm = hilt.delegate
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Appearance") {
                AppearanceSection(state, vm)
            }
            SectionCard(title = "Models") {
                state.sections.forEachIndexed { idx, sec ->
                    if (idx > 0) HorizontalDivider()
                    ModelSectionRow(sec, vm)
                }
            }
            SectionCard(title = "Inference") {
                InferenceSection(state, vm)
            }
            SectionCard(title = "Noise reduction") {
                NoiseReductionSection(state, vm)
            }
            SectionCard(title = "Regional filter") {
                RegionalFilterSection(state, vm)
            }
            SectionCard(title = "iNaturalist") {
                INaturalistSection(state, vm)
            }
            SectionCard(title = "About") {
                AboutSection()
            }
        }
    }

    state.sections.firstOrNull { it.showLicenseSheet }?.let { sec ->
        ModalBottomSheet(onDismissRequest = { vm.cancelLicenseSheet(sec.modelId) }) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Install ${sec.displayName}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "License: ${sec.license}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${"%,d".format(sec.sizeBytes)} bytes will be downloaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.confirmInstall(sec.modelId) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Confirm and download") }
                OutlinedButton(
                    onClick = { vm.cancelLicenseSheet(sec.modelId) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ModelSectionRow(sec: ModelSection, vm: SettingsViewModel) {
    Text(sec.displayName, style = MaterialTheme.typography.bodyLarge)
    when (val s = sec.install) {
        is ModelInstallState.Ready -> {
            Text(
                "Status: Installed (${sec.sizeBytes / 1024 / 1024} MB)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("License: ${sec.license}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.openLicenseSheet(sec.modelId) }) { Text("Reinstall") }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { vm.remove(sec.modelId) }) { Text("Remove") }
        }
        is ModelInstallState.Downloading -> {
            val pct = (s.progress * 100).toInt()
            Text("Status: Downloading… $pct%", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
        }
        is ModelInstallState.Verifying -> {
            Text("Status: Verifying SHA-256…", style = MaterialTheme.typography.bodyMedium)
        }
        is ModelInstallState.Failed -> {
            Text("Status: Failed — ${s.message}", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.openLicenseSheet(sec.modelId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Try again") }
        }
        is ModelInstallState.NotInstalled -> {
            Text("Status: Not installed", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.openLicenseSheet(sec.modelId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Install ${sec.displayName}") }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun InferenceSection(state: SettingsUiState, vm: SettingsViewModel) {
    Text("Min display confidence: ${"%.2f".format(state.minConfidenceDisplay)}")
    Slider(
        value = state.minConfidenceDisplay,
        onValueChange = { vm.setMinConfidence(it) },
        valueRange = MIN_CONF..MAX_CONF,
    )
    Text("Min detected windows: ${state.minWindows}")
    Slider(
        value = state.minWindows.toFloat(),
        onValueChange = { vm.setMinWindows(it.toInt().coerceIn(MIN_MIN_WINDOWS, MAX_MIN_WINDOWS)) },
        valueRange = MIN_MIN_WINDOWS.toFloat()..MAX_MIN_WINDOWS.toFloat(),
        steps = MAX_MIN_WINDOWS - MIN_MIN_WINDOWS - 1,
    )
}

@Suppress("FunctionNaming")
@Composable
private fun NoiseReductionSection(state: SettingsUiState, vm: SettingsViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Spectral noise reduction")
        Switch(
            checked = state.spectralSubtractionEnabled,
            onCheckedChange = { vm.setSpectralSubtractionEnabled(it) },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("YAMNet biological gate")
        Switch(
            checked = state.yamNetGateEnabled,
            onCheckedChange = { vm.setYamNetGateEnabled(it) },
        )
    }
}

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun INaturalistSection(state: SettingsUiState, vm: SettingsViewModel) {
    val launcher = rememberLauncherForActivityResult(
        contract = INatWebLoginActivity.Contract(),
        onResult = { token -> vm.onLoginCaptured(token) },
    )
    if (state.inatTokenPresent) {
        Text(
            "Logged in as ${state.inatLogin ?: "@iNaturalist"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "The app refreshes its API token automatically while your iNat " +
                "session stays valid. Sign out to clear stored credentials.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.inatTestStatus is InatTestStatus.Failure) {
            Text(
                "Last login attempt failed: ${state.inatTestStatus.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    } else {
        Text(
            "Sign in to iNaturalist so the app can submit your observations. " +
                "A web login screen opens once; the token refreshes itself afterwards.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.inatTestStatus is InatTestStatus.Failure) {
            Text(
                state.inatTestStatus.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = { launcher.launch(Unit) },
            enabled = state.inatTestStatus !is InatTestStatus.Loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (state.inatTestStatus) {
                    is InatTestStatus.Loading -> "Verifying…"
                    else -> "Log in to iNaturalist"
                },
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun RegionalFilterSection(state: SettingsUiState, vm: SettingsViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("iNaturalist observations filter")
        Switch(
            checked = state.regionalFilterEnabled,
            onCheckedChange = { vm.setRegionalFilterEnabled(it) },
        )
    }
    if (state.regionalFilterEnabled) {
        Text("Search radius: ${state.regionRadiusKm} km")
        Slider(
            value = state.regionRadiusKm.toFloat(),
            onValueChange = { vm.setRegionRadiusKm(it.toInt()) },
            valueRange = MIN_REGION_RADIUS.toFloat()..MAX_REGION_RADIUS.toFloat(),
            steps = (MAX_REGION_RADIUS - MIN_REGION_RADIUS) / REGION_RADIUS_STEP - 1,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("BirdNET regional model")
        Switch(
            checked = state.birdNetMetaEnabled,
            onCheckedChange = { vm.setBirdNetMetaEnabled(it) },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Allow deleting recordings with observations")
        Switch(
            checked = state.allowDeleteUploaded,
            onCheckedChange = { vm.setAllowDeleteUploaded(it) },
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun AppearanceSection(state: SettingsUiState, vm: SettingsViewModel) {
    Text("Theme", style = MaterialTheme.typography.bodyMedium)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ThemeMode.entries.forEachIndexed { idx, mode ->
            SegmentedButton(
                selected = state.themeMode == mode,
                onClick = { vm.setThemeMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(idx, ThemeMode.entries.size),
                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun AboutSection() {
    Text("WildEar 0.1.0", style = MaterialTheme.typography.bodyMedium)
}

private const val MIN_CONF = 0.05f
private const val MAX_CONF = 0.90f
private const val MIN_REGION_RADIUS = 50
private const val MAX_REGION_RADIUS = 500
private const val REGION_RADIUS_STEP = 50
private const val MIN_MIN_WINDOWS = 1
private const val MAX_MIN_WINDOWS = 10
