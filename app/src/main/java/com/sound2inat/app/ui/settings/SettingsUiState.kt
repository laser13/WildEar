package com.sound2inat.app.ui.settings

import com.sound2inat.modelmanager.ModelInstallState

data class SettingsUiState(
    val modelInstall: ModelInstallState = ModelInstallState.NotInstalled,
    val modelLicense: String = "",
    val modelDisplayName: String = "",
    val modelSizeBytes: Long = 0L,
    val showLicenseSheet: Boolean = false,
    val installProgress: Float? = null,
    val minConfidenceDisplay: Float = 0.25f,
    val topK: Int = 5,
)
