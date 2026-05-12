package com.sound2inat.app.ui.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Suppress("FunctionNaming")
@Composable
fun PhotosScreen(
    onOpenPhotoDraft: (String) -> Unit,
    onStartCapture: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Photos", style = MaterialTheme.typography.headlineMedium)
        Text("Photo observations will appear here.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onStartCapture) {
            Text("Take photos")
        }
    }
}
