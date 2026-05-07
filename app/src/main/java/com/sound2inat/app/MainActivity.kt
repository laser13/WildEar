package com.sound2inat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sound2inat.app.data.Settings
import com.sound2inat.app.data.ThemeMode
import com.sound2inat.app.nav.RootScaffold
import com.sound2inat.app.permissions.AndroidPermissionsController
import com.sound2inat.app.permissions.LocalPermissionsController
import com.sound2inat.app.ui.theme.Sound2iNatTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val perms = AndroidPermissionsController(this)
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            Sound2iNatTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPermissionsController provides perms) {
                        RootScaffold()
                    }
                }
            }
        }
    }
}
