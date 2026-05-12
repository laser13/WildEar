package com.sound2inat.app.ui.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Suppress("FunctionNaming")
@Composable
fun PhotoCaptureScreen(
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Photo capture", style = MaterialTheme.typography.headlineMedium)
        Text("CameraX capture will be implemented in the capture task.")
        Button(onClick = { onDone("placeholder-photo-draft") }) {
            Text("Done")
        }
        OutlinedButton(onClick = onCancel) {
            Text("Cancel")
        }
    }
}
