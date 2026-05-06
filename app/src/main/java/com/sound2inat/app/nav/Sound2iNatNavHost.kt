package com.sound2inat.app.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.sound2inat.app.ui.home.HomeScreen
import com.sound2inat.app.ui.radar.RadarScreen
import com.sound2inat.app.ui.recording.RecordingScreen
import com.sound2inat.app.ui.review.ReviewScreen
import com.sound2inat.app.ui.settings.SettingsScreen

@Suppress("FunctionNaming")
@Composable
fun Sound2iNatNavHost(
    nav: NavHostController,
    padding: PaddingValues,
) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.padding(padding),
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onRecord = { nav.navigate(Routes.RECORDING) },
                onOpenDraft = { id -> nav.navigate(Routes.review(id)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.RADAR) {
            RadarScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.RECORDING) {
            RecordingScreen(
                onDone = { id -> nav.navigate(Routes.review(id)) { popUpTo(Routes.HOME) } },
                onCancel = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.REVIEW,
            arguments = listOf(navArgument("draftId") { type = NavType.StringType }),
        ) {
            ReviewScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
