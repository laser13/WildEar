package com.sound2inat.app.ui.settings

import com.google.common.truth.Truth.assertThat
import com.sound2inat.modelmanager.BirdNetV24
import com.sound2inat.modelmanager.ModelDescriptor
import com.sound2inat.modelmanager.ModelInstallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val descriptor = BirdNetV24.descriptor

    private fun firstSection(vm: SettingsViewModel): ModelSection =
        vm.state.value.sections.first { it.modelId == descriptor.id }

    @Test
    fun `initial state reflects descriptor and current install state`() = runTest(UnconfinedTestDispatcher()) {
        val vm = build(initial = ModelInstallState.NotInstalled, scope = backgroundScope)
        val sec = firstSection(vm)
        assertThat(sec.license).isEqualTo(descriptor.license)
        assertThat(sec.install).isInstanceOf(ModelInstallState.NotInstalled::class.java)
    }

    @Test
    fun `confirmInstall walks states to Ready`() = runTest(UnconfinedTestDispatcher()) {
        val ready = ModelInstallState.Ready(File("/m.tflite"), File("/l.txt"))
        val emissions = listOf(
            ModelInstallState.Downloading(0f),
            ModelInstallState.Downloading(0.5f),
            ModelInstallState.Verifying(false),
            ready,
        )
        val vm = build(
            initial = ModelInstallState.NotInstalled,
            install = { _, emit -> emissions.forEach(emit) },
            scope = backgroundScope,
        )
        vm.confirmInstall(descriptor.id)
        val sec = firstSection(vm)
        assertThat(sec.install).isEqualTo(ready)
        assertThat(sec.installProgress).isNull()
    }

    @Test
    fun `remove transitions section state to NotInstalled`() = runTest(UnconfinedTestDispatcher()) {
        val ready = ModelInstallState.Ready(File("/m"), File("/l"))
        val vm = build(initial = ready, scope = backgroundScope)
        assertThat(firstSection(vm).install).isEqualTo(ready)
        vm.remove(descriptor.id)
        assertThat(firstSection(vm).install).isEqualTo(ModelInstallState.NotInstalled)
    }

    @Test
    fun `multiple descriptors produce independent sections`() = runTest(UnconfinedTestDispatcher()) {
        val second = descriptor.copy(id = "fake-2", displayName = "Fake 2")
        val vm = SettingsViewModel(
            descriptors = listOf(descriptor, second),
            installModel = { _, _ -> },
            removeModel = {},
            resolveState = { d ->
                if (d.id == descriptor.id) {
                    ModelInstallState.NotInstalled
                } else {
                    ModelInstallState.Ready(File("/m"), File("/l"))
                }
            },
            minConfFlow = MutableStateFlow(0.25f),
            writeMinConf = {},
            inatTokenFlow = MutableStateFlow<String?>(null),
            inatLoginFlow = MutableStateFlow<String?>(null),
            acceptInatToken = {},
            signOutInat = {},
            regionalFilterEnabledFlow = MutableStateFlow(true),
            writeRegionalFilterEnabled = {},
            regionRadiusKmFlow = MutableStateFlow(200),
            writeRegionRadiusKm = {},
            minWindowsFlow = MutableStateFlow(2),
            writeMinWindows = {},
            spectralSubtractionEnabledFlow = MutableStateFlow(true),
            writeSpectralSubtractionEnabled = {},
            yamNetGateEnabledFlow = MutableStateFlow(true),
            writeYamNetGateEnabled = {},
            birdNetMetaEnabledFlow = MutableStateFlow(true),
            writeBirdNetMetaEnabled = {},
            allowDeleteUploadedFlow = MutableStateFlow(false),
            writeAllowDeleteUploaded = {},
            externalScope = backgroundScope,
        )
        val sections = vm.state.value.sections.associateBy { it.modelId }
        assertThat(sections.keys).containsExactly(descriptor.id, "fake-2")
        assertThat(sections[descriptor.id]!!.install).isInstanceOf(ModelInstallState.NotInstalled::class.java)
        assertThat(sections["fake-2"]!!.install).isInstanceOf(ModelInstallState.Ready::class.java)
    }

    @Test
    fun `setRegionalFilterEnabled propagates via setter`() = runTest(UnconfinedTestDispatcher()) {
        val captured = mutableListOf<Boolean>()
        val flow = MutableStateFlow(true)
        val vm = build(
            initial = ModelInstallState.NotInstalled,
            regionalFilterEnabledFlow = flow,
            writeRegionalFilterEnabled = {
                captured += it
                flow.value = it
            },
            scope = backgroundScope,
        )
        vm.setRegionalFilterEnabled(false)
        assertThat(captured).containsExactly(false)
        assertThat(vm.state.value.regionalFilterEnabled).isFalse()
    }

    @Test
    fun `setRegionRadiusKm propagates via setter`() = runTest(UnconfinedTestDispatcher()) {
        val captured = mutableListOf<Int>()
        val flow = MutableStateFlow(200)
        val vm = build(
            initial = ModelInstallState.NotInstalled,
            regionRadiusKmFlow = flow,
            writeRegionRadiusKm = {
                captured += it
                flow.value = it
            },
            scope = backgroundScope,
        )
        vm.setRegionRadiusKm(350)
        assertThat(captured).containsExactly(350)
        assertThat(vm.state.value.regionRadiusKm).isEqualTo(350)
    }

    @Test
    fun `setMinWindows propagates via setter`() = runTest(UnconfinedTestDispatcher()) {
        val captured = mutableListOf<Int>()
        val flow = MutableStateFlow(2)
        val vm = build(
            initial = ModelInstallState.NotInstalled,
            minWindowsFlow = flow,
            writeMinWindows = {
                captured += it
                flow.value = it
            },
            scope = backgroundScope,
        )
        vm.setMinWindows(5)
        assertThat(captured).containsExactly(5)
        assertThat(vm.state.value.minWindows).isEqualTo(5)
    }

    @Test
    fun `setSpectralSubtractionEnabled propagates via setter`() = runTest(UnconfinedTestDispatcher()) {
        val captured = mutableListOf<Boolean>()
        val flow = MutableStateFlow(true)
        val vm = build(
            initial = ModelInstallState.NotInstalled,
            spectralSubtractionEnabledFlow = flow,
            writeSpectralSubtractionEnabled = {
                captured += it
                flow.value = it
            },
            scope = backgroundScope,
        )
        vm.setSpectralSubtractionEnabled(false)
        assertThat(captured).containsExactly(false)
        assertThat(vm.state.value.spectralSubtractionEnabled).isFalse()
    }

    @Test
    fun `setYamNetGateEnabled propagates via setter`() = runTest(UnconfinedTestDispatcher()) {
        val captured = mutableListOf<Boolean>()
        val flow = MutableStateFlow(true)
        val vm = build(
            initial = ModelInstallState.NotInstalled,
            yamNetGateEnabledFlow = flow,
            writeYamNetGateEnabled = {
                captured += it
                flow.value = it
            },
            scope = backgroundScope,
        )
        vm.setYamNetGateEnabled(false)
        assertThat(captured).containsExactly(false)
        assertThat(vm.state.value.yamNetGateEnabled).isFalse()
    }

    @Test
    fun `setBirdNetMetaEnabled propagates via setter`() = runTest(UnconfinedTestDispatcher()) {
        val captured = mutableListOf<Boolean>()
        val flow = MutableStateFlow(true)
        val vm = build(
            initial = ModelInstallState.NotInstalled,
            birdNetMetaEnabledFlow = flow,
            writeBirdNetMetaEnabled = {
                captured += it
                flow.value = it
            },
            scope = backgroundScope,
        )
        vm.setBirdNetMetaEnabled(false)
        assertThat(captured).containsExactly(false)
        assertThat(vm.state.value.birdNetMetaEnabled).isFalse()
    }

    private fun build(
        initial: ModelInstallState,
        install: suspend (ModelDescriptor, (ModelInstallState) -> Unit) -> Unit = { _, _ -> },
        regionalFilterEnabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(true),
        writeRegionalFilterEnabled: suspend (Boolean) -> Unit = {},
        regionRadiusKmFlow: MutableStateFlow<Int> = MutableStateFlow(200),
        writeRegionRadiusKm: suspend (Int) -> Unit = {},
        minWindowsFlow: MutableStateFlow<Int> = MutableStateFlow(2),
        writeMinWindows: suspend (Int) -> Unit = {},
        spectralSubtractionEnabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(true),
        writeSpectralSubtractionEnabled: suspend (Boolean) -> Unit = {},
        yamNetGateEnabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(true),
        writeYamNetGateEnabled: suspend (Boolean) -> Unit = {},
        birdNetMetaEnabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(true),
        writeBirdNetMetaEnabled: suspend (Boolean) -> Unit = {},
        scope: CoroutineScope,
    ): SettingsViewModel = SettingsViewModel(
        descriptors = listOf(descriptor),
        installModel = install,
        removeModel = {},
        resolveState = { initial },
        minConfFlow = MutableStateFlow(0.25f),
        writeMinConf = {},
        inatTokenFlow = MutableStateFlow<String?>(null),
        inatLoginFlow = MutableStateFlow<String?>(null),
        acceptInatToken = {},
        signOutInat = {},
        regionalFilterEnabledFlow = regionalFilterEnabledFlow,
        writeRegionalFilterEnabled = writeRegionalFilterEnabled,
        regionRadiusKmFlow = regionRadiusKmFlow,
        writeRegionRadiusKm = writeRegionRadiusKm,
        minWindowsFlow = minWindowsFlow,
        writeMinWindows = writeMinWindows,
        spectralSubtractionEnabledFlow = spectralSubtractionEnabledFlow,
        writeSpectralSubtractionEnabled = writeSpectralSubtractionEnabled,
        yamNetGateEnabledFlow = yamNetGateEnabledFlow,
        writeYamNetGateEnabled = writeYamNetGateEnabled,
        birdNetMetaEnabledFlow = birdNetMetaEnabledFlow,
        writeBirdNetMetaEnabled = writeBirdNetMetaEnabled,
        allowDeleteUploadedFlow = MutableStateFlow(false),
        writeAllowDeleteUploaded = {},
        externalScope = scope,
    )
}
