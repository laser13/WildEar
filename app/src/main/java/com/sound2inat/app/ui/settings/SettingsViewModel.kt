package com.sound2inat.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.data.Settings
import com.sound2inat.modelmanager.KnownModels
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
    private val descriptors: List<ModelDescriptor>,
    private val installModel: suspend (ModelDescriptor, (ModelInstallState) -> Unit) -> Unit,
    private val removeModel: (ModelDescriptor) -> Unit,
    private val resolveState: suspend (ModelDescriptor) -> ModelInstallState,
    private val minConfFlow: Flow<Float>,
    private val topKFlow: Flow<Int>,
    private val writeMinConf: suspend (Float) -> Unit,
    private val writeTopK: suspend (Int) -> Unit,
    private val inatTokenFlow: Flow<String?>,
    private val inatLoginFlow: Flow<String?>,
    private val writeInatToken: suspend (String?) -> Unit,
    private val writeInatLogin: suspend (String?) -> Unit,
    private val verifyInatToken: suspend (String) -> String,
    private val regionalFilterEnabledFlow: Flow<Boolean>,
    private val regionRadiusKmFlow: Flow<Int>,
    private val writeRegionalFilterEnabled: suspend (Boolean) -> Unit,
    private val writeRegionRadiusKm: suspend (Int) -> Unit,
    private val minWindowsFlow: Flow<Int>,
    private val writeMinWindows: suspend (Int) -> Unit,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(
        SettingsUiState(
            sections = descriptors.map { d ->
                ModelSection(
                    modelId = d.id,
                    displayName = d.displayName,
                    license = d.license,
                    sizeBytes = d.sizeBytes,
                )
            },
        ),
    )
    val state: StateFlow<SettingsUiState> = _state

    init {
        for (d in descriptors) {
            scope.launch {
                val st = resolveState(d)
                updateSection(d.id) { it.copy(install = st) }
            }
        }
        scope.launch {
            minConfFlow.collect { v -> _state.value = _state.value.copy(minConfidenceDisplay = v) }
        }
        scope.launch {
            topKFlow.collect { v -> _state.value = _state.value.copy(topK = v) }
        }
        scope.launch {
            inatTokenFlow.collect { v ->
                _state.value = _state.value.copy(inatTokenField = v.orEmpty())
            }
        }
        scope.launch {
            inatLoginFlow.collect { v -> _state.value = _state.value.copy(inatLogin = v) }
        }
        scope.launch {
            regionalFilterEnabledFlow.collect { v ->
                _state.value = _state.value.copy(regionalFilterEnabled = v)
            }
        }
        scope.launch {
            regionRadiusKmFlow.collect { v ->
                _state.value = _state.value.copy(regionRadiusKm = v)
            }
        }
        scope.launch {
            minWindowsFlow.collect { v -> _state.value = _state.value.copy(minWindows = v) }
        }
    }

    fun openLicenseSheet(modelId: String) =
        updateSection(modelId) { it.copy(showLicenseSheet = true) }

    fun cancelLicenseSheet(modelId: String) =
        updateSection(modelId) { it.copy(showLicenseSheet = false) }

    fun confirmInstall(modelId: String) {
        val d = descriptors.firstOrNull { it.id == modelId } ?: return
        updateSection(modelId) { it.copy(showLicenseSheet = false) }
        scope.launch {
            installModel(d) { s ->
                updateSection(modelId) { sec ->
                    sec.copy(
                        install = s,
                        installProgress = (s as? ModelInstallState.Downloading)?.progress,
                    )
                }
            }
        }
    }

    fun remove(modelId: String) {
        val d = descriptors.firstOrNull { it.id == modelId } ?: return
        scope.launch(Dispatchers.IO) { removeModel(d) }
        updateSection(modelId) {
            it.copy(install = ModelInstallState.NotInstalled, installProgress = null)
        }
    }

    fun setMinConfidence(v: Float) { scope.launch { writeMinConf(v) } }
    fun setTopK(v: Int) { scope.launch { writeTopK(v) } }

    fun setInatTokenField(v: String) {
        _state.value = _state.value.copy(
            inatTokenField = v,
            inatTestStatus = InatTestStatus.Idle,
        )
    }

    fun saveInatToken() {
        val token = _state.value.inatTokenField.trim()
        scope.launch { writeInatToken(token.ifBlank { null }) }
        if (token.isBlank()) {
            scope.launch { writeInatLogin(null) }
            _state.value = _state.value.copy(inatTestStatus = InatTestStatus.Idle)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun testInatConnection() {
        val token = _state.value.inatTokenField.trim()
        if (token.isEmpty()) {
            _state.value = _state.value.copy(inatTestStatus = InatTestStatus.Failure("Paste the token first"))
            return
        }
        _state.value = _state.value.copy(inatTestStatus = InatTestStatus.Loading)
        scope.launch {
            try {
                val login = verifyInatToken(token)
                writeInatToken(token)
                writeInatLogin(login)
                _state.value = _state.value.copy(inatTestStatus = InatTestStatus.Ok(login))
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    inatTestStatus = InatTestStatus.Failure(e.message ?: "Failed"),
                )
            }
        }
    }

    fun signOutInat() {
        scope.launch {
            writeInatToken(null)
            writeInatLogin(null)
        }
        _state.value = _state.value.copy(
            inatTokenField = "",
            inatTestStatus = InatTestStatus.Idle,
        )
    }

    fun setRegionalFilterEnabled(v: Boolean) { scope.launch { writeRegionalFilterEnabled(v) } }
    fun setRegionRadiusKm(v: Int) { scope.launch { writeRegionRadiusKm(v) } }
    fun setMinWindows(v: Int) { scope.launch { writeMinWindows(v) } }

    private fun updateSection(modelId: String, transform: (ModelSection) -> ModelSection) {
        _state.value = _state.value.copy(
            sections = _state.value.sections.map { sec ->
                if (sec.modelId == modelId) transform(sec) else sec
            },
        )
    }
}

@HiltViewModel
class SettingsViewModelHilt @Inject constructor(
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val inatClient: com.sound2inat.inat.INaturalistClient,
) : ViewModel() {
    val delegate = SettingsViewModel(
        descriptors = KnownModels,
        installModel = { d, emit -> modelManager.install(d, emit) },
        removeModel = { d -> modelManager.remove(d) },
        resolveState = { d -> modelManager.stateFor(d) },
        minConfFlow = settings.minConfidenceDisplay,
        topKFlow = settings.topK,
        writeMinConf = { settings.setMinConfidenceDisplay(it) },
        writeTopK = { settings.setTopK(it) },
        inatTokenFlow = settings.inatToken,
        inatLoginFlow = settings.inatLogin,
        writeInatToken = { settings.setInatToken(it) },
        writeInatLogin = { settings.setInatLogin(it) },
        verifyInatToken = { inatClient.verifyToken(it) },
        regionalFilterEnabledFlow = settings.regionalFilterEnabled,
        writeRegionalFilterEnabled = { settings.setRegionalFilterEnabled(it) },
        regionRadiusKmFlow = settings.regionRadiusKm,
        writeRegionRadiusKm = { settings.setRegionRadiusKm(it) },
        minWindowsFlow = settings.minWindows,
        writeMinWindows = { settings.setMinWindows(it) },
    )
}
