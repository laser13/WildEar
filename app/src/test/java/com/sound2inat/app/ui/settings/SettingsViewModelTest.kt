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

    @Test
    fun `initial state reflects descriptor and current install state`() = runTest(UnconfinedTestDispatcher()) {
        val vm = build(initial = ModelInstallState.NotInstalled, scope = backgroundScope)
        val s = vm.state.value
        assertThat(s.modelLicense).isEqualTo(descriptor.license)
        assertThat(s.modelInstall).isInstanceOf(ModelInstallState.NotInstalled::class.java)
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
        vm.confirmInstall()
        assertThat(vm.state.value.modelInstall).isEqualTo(ready)
        assertThat(vm.state.value.installProgress).isNull()
    }

    @Test
    fun `setTopK propagates via setter`() = runTest(UnconfinedTestDispatcher()) {
        val captured = mutableListOf<Int>()
        val topKFlow = MutableStateFlow(5)
        val vm = build(
            initial = ModelInstallState.NotInstalled,
            topKFlow = topKFlow,
            writeTopK = {
                captured += it
                topKFlow.value = it
            },
            scope = backgroundScope,
        )
        vm.setTopK(8)
        assertThat(captured).containsExactly(8)
    }

    @Test
    fun `remove transitions state to NotInstalled`() = runTest(UnconfinedTestDispatcher()) {
        val ready = ModelInstallState.Ready(File("/m"), File("/l"))
        val vm = build(initial = ready, scope = backgroundScope)
        assertThat(vm.state.value.modelInstall).isEqualTo(ready)
        vm.remove()
        assertThat(vm.state.value.modelInstall).isEqualTo(ModelInstallState.NotInstalled)
    }

    private fun build(
        initial: ModelInstallState,
        install: suspend (ModelDescriptor, (ModelInstallState) -> Unit) -> Unit = { _, _ -> },
        topKFlow: MutableStateFlow<Int> = MutableStateFlow(5),
        writeTopK: suspend (Int) -> Unit = {},
        scope: CoroutineScope,
    ): SettingsViewModel = SettingsViewModel(
        descriptor = descriptor,
        installModel = install,
        removeModel = {},
        initialState = { initial },
        minConfFlow = MutableStateFlow(0.25f),
        topKFlow = topKFlow,
        writeMinConf = {},
        writeTopK = writeTopK,
        externalScope = scope,
    )
}
