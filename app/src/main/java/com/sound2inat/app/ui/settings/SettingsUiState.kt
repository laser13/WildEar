package com.sound2inat.app.ui.settings

import com.sound2inat.app.data.ThemeMode
import com.sound2inat.modelmanager.ModelInstallState

/**
 * Per-descriptor section in the Settings UI. The screen renders one
 * [ModelSection] per known [com.sound2inat.modelmanager.ModelDescriptor];
 * each has its own license sheet / install / progress state so installing
 * one model doesn't disturb the other.
 */
data class ModelSection(
    val modelId: String,
    val displayName: String,
    val license: String,
    val sizeBytes: Long,
    val install: ModelInstallState = ModelInstallState.NotInstalled,
    val installProgress: Float? = null,
    val showLicenseSheet: Boolean = false,
)

data class SettingsUiState(
    val sections: List<ModelSection> = emptyList(),
    val minConfidenceDisplay: Float = 0.25f,
    /** True when an api_token is stored — drives the Login/Logout split. */
    val inatTokenPresent: Boolean = false,
    val inatLogin: String? = null,
    val inatTestStatus: InatTestStatus = InatTestStatus.Idle,
    val regionalFilterEnabled: Boolean = true,
    val regionRadiusKm: Int = 200,
    val minWindows: Int = 2,
    val spectralSubtractionEnabled: Boolean = true,
    val yamNetGateEnabled: Boolean = true,
    val birdNetMetaEnabled: Boolean = true,
    val allowDeleteUploaded: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

sealed interface InatTestStatus {
    data object Idle : InatTestStatus
    data object Loading : InatTestStatus
    data class Ok(val login: String) : InatTestStatus
    data class Failure(val message: String) : InatTestStatus
}
