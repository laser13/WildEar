package com.sound2inat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.sound2inat.app.nav.Sound2iNatNavHost
import com.sound2inat.app.permissions.AndroidPermissionsController
import com.sound2inat.app.permissions.LocalPermissionsController
import com.sound2inat.app.ui.theme.Sound2iNatTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val perms = AndroidPermissionsController(this)
        setContent {
            Sound2iNatTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPermissionsController provides perms) {
                        Sound2iNatNavHost()
                    }
                }
            }
        }
    }
}
