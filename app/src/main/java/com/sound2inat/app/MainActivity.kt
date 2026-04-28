package com.sound2inat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import com.sound2inat.app.nav.Sound2iNatNavHost
import com.sound2inat.app.permissions.AndroidPermissionsController
import com.sound2inat.app.permissions.LocalPermissionsController
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = AndroidPermissionsController(this)
        setContent {
            MaterialTheme {
                Surface {
                    CompositionLocalProvider(LocalPermissionsController provides perms) {
                        Sound2iNatNavHost()
                    }
                }
            }
        }
    }
}
