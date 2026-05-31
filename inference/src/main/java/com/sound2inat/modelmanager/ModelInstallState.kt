package com.sound2inat.modelmanager

import java.io.File

sealed interface ModelInstallState {
    data object NotInstalled : ModelInstallState
    data class Downloading(val progress: Float) : ModelInstallState
    data object Verifying : ModelInstallState
    data class Ready(val modelFile: File, val labelsFile: File) : ModelInstallState
    data class Failed(val message: String) : ModelInstallState
}
