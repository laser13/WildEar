package com.sound2inat.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.data.Settings
import com.sound2inat.modelmanager.BirdNetV24
import com.sound2inat.modelmanager.ModelDescriptor
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@Suppress("LongParameterList")
class SettingsViewModel(
    private val descriptor: ModelDescriptor,
    private val installModel: suspend (ModelDescriptor, (ModelInstallState) -> Unit) -> Unit,
    private val removeModel: (ModelDescriptor) -> Unit,
    private val initialState: () -> ModelInstallState,
    private val minConfFlow: Flow<Float>,
    private val topKFlow: Flow<Int>,
    private val writeMinConf: suspend (Float) -> Unit,
    private val writeTopK: suspend (Int) -> Unit,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(
        SettingsUiState(
            modelInstall = initialState(),
            modelLicense = descriptor.license,
            modelDisplayName = descriptor.displayName,
            modelSizeBytes = descriptor.sizeBytes,
        ),
    )
    val state: StateFlow<SettingsUiState> = _state

    init {
        scope.launch {
            minConfFlow.collect { v -> _state.value = _state.value.copy(minConfidenceDisplay = v) }
        }
        scope.launch {
            topKFlow.collect { v -> _state.value = _state.value.copy(topK = v) }
        }
    }

    fun openLicenseSheet() { _state.value = _state.value.copy(showLicenseSheet = true) }
    fun cancelLicenseSheet() { _state.value = _state.value.copy(showLicenseSheet = false) }

    fun confirmInstall() {
        _state.value = _state.value.copy(showLicenseSheet = false)
        scope.launch {
            installModel(descriptor) { s ->
                _state.value = _state.value.copy(
                    modelInstall = s,
                    installProgress = (s as? ModelInstallState.Downloading)?.progress,
                )
            }
        }
    }

    fun remove() {
        scope.launch(Dispatchers.IO) { removeModel(descriptor) }
        _state.value = _state.value.copy(modelInstall = ModelInstallState.NotInstalled, installProgress = null)
    }

    fun setMinConfidence(v: Float) { scope.launch { writeMinConf(v) } }
    fun setTopK(v: Int) { scope.launch { writeTopK(v) } }
}

@HiltViewModel
class SettingsViewModelHilt @Inject constructor(
    private val modelManager: ModelManager,
    private val settings: Settings,
) : ViewModel() {
    val delegate = SettingsViewModel(
        descriptor = BirdNetV24.descriptor,
        installModel = { d, emit -> modelManager.install(d, emit) },
        removeModel = { d -> modelManager.remove(d) },
        initialState = { modelManager.stateFor(BirdNetV24.descriptor) },
        minConfFlow = settings.minConfidenceDisplay,
        topKFlow = settings.topK,
        writeMinConf = { settings.setMinConfidenceDisplay(it) },
        writeTopK = { settings.setTopK(it) },
    )
}
