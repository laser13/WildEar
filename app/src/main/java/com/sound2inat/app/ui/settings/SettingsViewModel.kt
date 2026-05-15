package com.sound2inat.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.data.Settings
import com.sound2inat.app.data.ThemeMode
import com.sound2inat.inat.INatAuthRepository
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@Suppress("LongParameterList")
class SettingsViewModel(
    private val descriptors: List<ModelDescriptor>,
    private val installModel: suspend (ModelDescriptor, (ModelInstallState) -> Unit) -> Unit,
    private val removeModel: (ModelDescriptor) -> Unit,
    private val resolveState: suspend (ModelDescriptor) -> ModelInstallState,
    private val minConfFlow: Flow<Float>,
    private val writeMinConf: suspend (Float) -> Unit,
    private val inatTokenFlow: Flow<String?>,
    private val inatLoginFlow: Flow<String?>,
    private val acceptInatToken: suspend (String) -> Unit,
    private val signOutInat: suspend () -> Unit,
    private val regionalFilterEnabledFlow: Flow<Boolean>,
    private val regionRadiusKmFlow: Flow<Int>,
    private val writeRegionalFilterEnabled: suspend (Boolean) -> Unit,
    private val writeRegionRadiusKm: suspend (Int) -> Unit,
    private val minWindowsFlow: Flow<Int>,
    private val writeMinWindows: suspend (Int) -> Unit,
    private val yamNetGateEnabledFlow: Flow<Boolean>,
    private val writeYamNetGateEnabled: suspend (Boolean) -> Unit,
    private val birdNetMetaEnabledFlow: Flow<Boolean>,
    private val writeBirdNetMetaEnabled: suspend (Boolean) -> Unit,
    private val allowDeleteUploadedFlow: Flow<Boolean>,
    private val writeAllowDeleteUploaded: suspend (Boolean) -> Unit,
    private val themeModeFlow: Flow<ThemeMode> = kotlinx.coroutines.flow.flowOf(ThemeMode.SYSTEM),
    private val writeThemeMode: suspend (ThemeMode) -> Unit = {},
    private val audioSourceRawFlow: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
    private val writeAudioSourceRaw: suspend (Boolean) -> Unit = {},
    private val normalizeAudioFlow: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    private val writeNormalizeAudio: suspend (Boolean) -> Unit = {},
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    @Inject constructor(
        modelManager: ModelManager,
        settings: Settings,
        inatAuth: INatAuthRepository,
    ) : this(
        descriptors = KnownModels,
        installModel = { d, emit -> modelManager.install(d, emit) },
        removeModel = { d -> modelManager.remove(d) },
        resolveState = { d -> modelManager.stateFor(d) },
        minConfFlow = settings.minConfidenceDisplay,
        writeMinConf = { settings.setMinConfidenceDisplay(it) },
        inatTokenFlow = inatAuth.tokenState,
        inatLoginFlow = inatAuth.loginState,
        acceptInatToken = { inatAuth.acceptCapturedToken(it) },
        signOutInat = { inatAuth.logout() },
        regionalFilterEnabledFlow = settings.regionalFilterEnabled,
        writeRegionalFilterEnabled = { settings.setRegionalFilterEnabled(it) },
        regionRadiusKmFlow = settings.regionRadiusKm,
        writeRegionRadiusKm = { settings.setRegionRadiusKm(it) },
        minWindowsFlow = settings.minWindows,
        writeMinWindows = { settings.setMinWindows(it) },
        yamNetGateEnabledFlow = settings.yamNetGateEnabled,
        writeYamNetGateEnabled = { settings.setYamNetGateEnabled(it) },
        birdNetMetaEnabledFlow = settings.birdNetMetaEnabled,
        writeBirdNetMetaEnabled = { settings.setBirdNetMetaEnabled(it) },
        allowDeleteUploadedFlow = settings.allowDeleteUploaded,
        writeAllowDeleteUploaded = { settings.setAllowDeleteUploaded(it) },
        themeModeFlow = settings.themeMode,
        writeThemeMode = { settings.setThemeMode(it) },
        audioSourceRawFlow = settings.audioSourceRaw,
        writeAudioSourceRaw = { settings.setAudioSourceRaw(it) },
        normalizeAudioFlow = settings.normalizeAudio,
        writeNormalizeAudio = { settings.setNormalizeAudio(it) },
    )

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
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        for (d in descriptors) {
            scope.launch {
                val st = resolveState(d)
                updateSection(d.id) { it.copy(install = st) }
            }
        }
        // Group 1: iNat auth + regional filter settings
        scope.launch {
            combine(
                minConfFlow,
                inatTokenFlow,
                inatLoginFlow,
                regionalFilterEnabledFlow,
                regionRadiusKmFlow,
            ) { minConf, inatToken, inatLogin, regionalFilter, regionRadius ->
                Partial1(minConf, inatToken, inatLogin, regionalFilter, regionRadius)
            }.collect { p1 ->
                _state.update { s ->
                    s.copy(
                        minConfidenceDisplay = p1.minConf,
                        inatTokenPresent = p1.inatToken != null,
                        inatLogin = p1.inatLogin,
                        regionalFilterEnabled = p1.regionalFilter,
                        regionRadiusKm = p1.regionRadius,
                    )
                }
            }
        }
        // Group 2: inference settings + UI preferences
        scope.launch {
            combine(
                minWindowsFlow,
                yamNetGateEnabledFlow,
                birdNetMetaEnabledFlow,
                allowDeleteUploadedFlow,
                themeModeFlow,
            ) { minWin, yamNet, birdNetMeta, allowDelete, theme ->
                Partial2(minWin, yamNet, birdNetMeta, allowDelete, theme)
            }.collect { p2 ->
                _state.update { s ->
                    s.copy(
                        minWindows = p2.minWin,
                        yamNetGateEnabled = p2.yamNet,
                        birdNetMetaEnabled = p2.birdNetMeta,
                        allowDeleteUploaded = p2.allowDelete,
                        themeMode = p2.theme,
                    )
                }
            }
        }
        // Group 3: audio source settings
        scope.launch {
            combine(
                audioSourceRawFlow,
                normalizeAudioFlow,
            ) { audioRaw, normalize ->
                audioRaw to normalize
            }.collect { (audioRaw, normalize) ->
                _state.update { s ->
                    s.copy(audioSourceRaw = audioRaw, normalizeAudio = normalize)
                }
            }
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

    /**
     * Called from the Settings screen with the api_token captured by
     * [com.sound2inat.inat.INatWebLoginActivity]. Persists it via the
     * auth repository (which also runs `verifyToken` to populate login).
     * Null = the user cancelled or the WebView closed without a token —
     * leave existing state alone.
     */
    fun onLoginCaptured(token: String?) {
        if (token.isNullOrBlank()) {
            _state.value = _state.value.copy(inatTestStatus = InatTestStatus.Idle)
            return
        }
        _state.value = _state.value.copy(inatTestStatus = InatTestStatus.Loading)
        scope.launch {
            runCatching { acceptInatToken(token) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        inatTestStatus = InatTestStatus.Ok(_state.value.inatLogin ?: ""),
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        inatTestStatus = InatTestStatus.Failure(e.message ?: "Login failed"),
                    )
                }
        }
    }

    fun signOut() {
        scope.launch { signOutInat() }
        _state.value = _state.value.copy(
            inatTokenPresent = false,
            inatLogin = null,
            inatTestStatus = InatTestStatus.Idle,
        )
    }

    fun setRegionalFilterEnabled(v: Boolean) { scope.launch { writeRegionalFilterEnabled(v) } }
    fun setRegionRadiusKm(v: Int) { scope.launch { writeRegionRadiusKm(v) } }
    fun setMinWindows(v: Int) { scope.launch { writeMinWindows(v) } }
    fun setYamNetGateEnabled(v: Boolean) { scope.launch { writeYamNetGateEnabled(v) } }
    fun setBirdNetMetaEnabled(v: Boolean) { scope.launch { writeBirdNetMetaEnabled(v) } }
    fun setAllowDeleteUploaded(v: Boolean) { scope.launch { writeAllowDeleteUploaded(v) } }
    fun setThemeMode(v: ThemeMode) { scope.launch { writeThemeMode(v) } }
    fun setAudioSourceRaw(v: Boolean) { scope.launch { writeAudioSourceRaw(v) } }
    fun setNormalizeAudio(v: Boolean) { scope.launch { writeNormalizeAudio(v) } }

    private fun updateSection(modelId: String, transform: (ModelSection) -> ModelSection) {
        _state.value = _state.value.copy(
            sections = _state.value.sections.map { sec ->
                if (sec.modelId == modelId) transform(sec) else sec
            },
        )
    }
}

private data class Partial1(
    val minConf: Float,
    val inatToken: String?,
    val inatLogin: String?,
    val regionalFilter: Boolean,
    val regionRadius: Int,
)

private data class Partial2(
    val minWin: Int,
    val yamNet: Boolean,
    val birdNetMeta: Boolean,
    val allowDelete: Boolean,
    val theme: ThemeMode,
)
