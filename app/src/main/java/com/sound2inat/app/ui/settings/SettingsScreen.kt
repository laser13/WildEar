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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sound2inat.app.R
import com.sound2inat.app.data.ThemeMode
import com.sound2inat.inat.INatWebLoginActivity
import com.sound2inat.modelmanager.ModelInstallState

@Suppress("FunctionNaming", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
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
            SectionCard(title = stringResource(R.string.section_appearance)) {
                AppearanceSection(state, vm)
            }
            SectionCard(title = stringResource(R.string.section_models)) {
                state.sections.forEachIndexed { idx, sec ->
                    if (idx > 0) HorizontalDivider()
                    ModelSectionRow(sec, vm)
                }
            }
            SectionCard(title = stringResource(R.string.section_inference)) {
                InferenceSection(state, vm)
            }
            SectionCard(title = stringResource(R.string.section_regional_filter)) {
                RegionalFilterSection(state, vm)
            }
            SectionCard(title = stringResource(R.string.section_inat)) {
                INaturalistSection(state, vm)
            }
            SectionCard(title = stringResource(R.string.section_about)) {
                AboutSection()
            }
        }
    }

    state.sections.firstOrNull { it.showLicenseSheet }?.let { sec ->
        ModalBottomSheet(onDismissRequest = { vm.cancelLicenseSheet(sec.modelId) }) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.settings_install_model_title, sec.displayName),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    stringResource(R.string.model_license, sec.license),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.model_size_download, "%,d".format(sec.sizeBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.confirmInstall(sec.modelId) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_confirm_download)) }
                OutlinedButton(
                    onClick = { vm.cancelLicenseSheet(sec.modelId) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_cancel)) }
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
                stringResource(R.string.model_status_installed, sec.sizeBytes / 1024 / 1024),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.model_license, sec.license), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.openLicenseSheet(sec.modelId) }
            ) { Text(stringResource(R.string.btn_reinstall)) }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { vm.remove(sec.modelId) }) { Text(stringResource(R.string.btn_remove)) }
        }
        is ModelInstallState.Downloading -> {
            val pct = (s.progress * 100).toInt()
            Text(stringResource(R.string.model_status_downloading, pct), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
        }
        is ModelInstallState.Verifying -> {
            Text(stringResource(R.string.model_status_verifying), style = MaterialTheme.typography.bodyMedium)
        }
        is ModelInstallState.Failed -> {
            Text(stringResource(R.string.model_status_failed, s.message), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.openLicenseSheet(sec.modelId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.btn_try_again)) }
        }
        is ModelInstallState.NotInstalled -> {
            Text(stringResource(R.string.model_status_not_installed), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.openLicenseSheet(sec.modelId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_install_model_title, sec.displayName)) }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ToggleRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Suppress("FunctionNaming")
@Composable
private fun InferenceSection(state: SettingsUiState, vm: SettingsViewModel) {
    ToggleRow(
        label = stringResource(R.string.label_audio_source_raw),
        subtitle = stringResource(R.string.label_audio_source_raw_sub),
        checked = state.audioSourceRaw,
        onCheckedChange = { vm.setAudioSourceRaw(it) },
    )
    ToggleRow(
        label = stringResource(R.string.label_normalize_audio),
        subtitle = stringResource(R.string.label_normalize_audio_sub),
        checked = state.normalizeAudio,
        onCheckedChange = { vm.setNormalizeAudio(it) },
    )
    ToggleRow(
        label = stringResource(R.string.label_yamnet_gate),
        checked = state.yamNetGateEnabled,
        onCheckedChange = { vm.setYamNetGateEnabled(it) },
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(stringResource(R.string.label_min_confidence, "%.2f".format(state.minConfidenceDisplay)))
    Slider(
        value = state.minConfidenceDisplay,
        onValueChange = { vm.setMinConfidence(it) },
        valueRange = MIN_CONF..MAX_CONF,
    )
    Text(stringResource(R.string.label_min_windows, state.minWindows))
    Slider(
        value = state.minWindows.toFloat(),
        onValueChange = { vm.setMinWindows(it.toInt().coerceIn(MIN_MIN_WINDOWS, MAX_MIN_WINDOWS)) },
        valueRange = MIN_MIN_WINDOWS.toFloat()..MAX_MIN_WINDOWS.toFloat(),
        steps = MAX_MIN_WINDOWS - MIN_MIN_WINDOWS - 1,
    )
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
            stringResource(R.string.inat_logged_in_as, state.inatLogin ?: "@iNaturalist"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.inat_token_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.inatTestStatus is InatTestStatus.Failure) {
            Text(
                stringResource(R.string.inat_login_failed, state.inatTestStatus.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.btn_sign_out))
        }
    } else {
        Text(
            stringResource(R.string.inat_sign_in_prompt),
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
                    is InatTestStatus.Loading -> stringResource(R.string.btn_verifying)
                    else -> stringResource(R.string.btn_log_in_to_inat)
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
        Text(stringResource(R.string.label_inat_filter))
        Switch(
            checked = state.regionalFilterEnabled,
            onCheckedChange = { vm.setRegionalFilterEnabled(it) },
        )
    }
    if (state.regionalFilterEnabled) {
        Text(stringResource(R.string.label_search_radius, state.regionRadiusKm))
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
        Text(stringResource(R.string.label_birdnet_regional))
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
        Text(stringResource(R.string.label_allow_delete_uploaded))
        Switch(
            checked = state.allowDeleteUploaded,
            onCheckedChange = { vm.setAllowDeleteUploaded(it) },
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun AppearanceSection(state: SettingsUiState, vm: SettingsViewModel) {
    Text(stringResource(R.string.label_theme), style = MaterialTheme.typography.bodyMedium)
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
    Text(stringResource(R.string.about_version), style = MaterialTheme.typography.bodyMedium)
}

private const val MIN_CONF = 0.05f
private const val MAX_CONF = 0.90f
private const val MIN_REGION_RADIUS = 50
private const val MAX_REGION_RADIUS = 500
private const val REGION_RADIUS_STEP = 50
private const val MIN_MIN_WINDOWS = 1
private const val MAX_MIN_WINDOWS = 10
