package com.sound2inat.app.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * Top-level scaffold owning the bottom navigation bar. The bar shows only
 * for [Routes.HOME] and [Routes.RADAR]; full-screen destinations hide it.
 */
@Suppress("FunctionNaming")
@Composable
fun RootScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in setOf(Routes.HOME, Routes.RADAR)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick = { nav.navigateToTab(Routes.HOME) },
                        icon = { Icon(Icons.Outlined.Mic, contentDescription = "Recordings") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.RADAR,
                        onClick = { nav.navigateToTab(Routes.RADAR) },
                        icon = { Icon(Icons.Outlined.Public, contentDescription = "Radar") },
                    )
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
