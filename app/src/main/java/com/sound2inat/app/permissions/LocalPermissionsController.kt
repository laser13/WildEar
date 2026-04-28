package com.sound2inat.app.permissions

import androidx.compose.runtime.staticCompositionLocalOf

val LocalPermissionsController = staticCompositionLocalOf<PermissionsController> {
    error("PermissionsController not provided")
}
