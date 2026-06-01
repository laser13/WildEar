package com.sound2inat.app.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sound2inat.app.R

/**
 * Top-level scaffold owning bottom navigation and capture shortcuts. Shell
 * chrome shows only on top-level browsing routes; full-screen flows hide it.
 */
@Suppress("FunctionNaming")
@Composable
fun RootScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevelRoutes = setOf(Routes.HOME, Routes.PHOTOS, Routes.RADAR)
    val showShellChrome = currentRoute in topLevelRoutes

    Scaffold(
        bottomBar = {
            if (showShellChrome) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick = { nav.navigateToTab(Routes.HOME) },
                        icon = {
                            Icon(
                                Icons.Outlined.Mic,
                                contentDescription = stringResource(R.string.nav_cd_recordings),
                            )
                        },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.PHOTOS,
                        onClick = { nav.navigateToTab(Routes.PHOTOS) },
                        icon = {
                            Icon(
                                Icons.Outlined.PhotoLibrary,
                                contentDescription = stringResource(R.string.nav_cd_photos),
                            )
                        },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.RADAR,
                        onClick = { nav.navigateToTab(Routes.RADAR) },
                        icon = {
                            Icon(
                                Icons.Outlined.Public,
                                contentDescription = stringResource(R.string.nav_cd_radar),
                            )
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            if (showShellChrome) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallFloatingActionButton(onClick = { nav.navigate(Routes.RECORDING) }) {
                        Icon(
                            Icons.Outlined.Mic,
                            contentDescription = stringResource(R.string.cd_fab_record_audio),
                        )
                    }
                    SmallFloatingActionButton(onClick = { nav.navigate(Routes.photoCapture()) }) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = stringResource(R.string.cd_fab_take_photos),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Sound2iNatNavHost(nav = nav, padding = padding)
    }
}

private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) {
            saveState = true
            inclusive = false
        }
        launchSingleTop = true
        restoreState = true
    }
}
