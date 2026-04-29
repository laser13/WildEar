package com.sound2inat.app.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sound2inat.app.ui.home.HomeScreen
import com.sound2inat.app.ui.recording.RecordingScreen

@Suppress("FunctionNaming")
@Composable
fun Sound2iNatNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onRecord = { nav.navigate(Routes.RECORDING) },
                onOpenDraft = { id -> nav.navigate(Routes.review(id)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
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
        ) { Placeholder("Review — Task 13") }
        composable(Routes.SETTINGS) { Placeholder("Settings — Task 16") }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun Placeholder(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}
