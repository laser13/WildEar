package com.sound2inat.app.ui.photos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

@Suppress("FunctionNaming")
@Composable
fun PhotoReviewScreen(
    onBack: () -> Unit,
    onAddMorePhotos: (String) -> Unit,
) {
    val vm: PhotoReviewViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var scientificName by remember(state.draftId) { mutableStateOf("") }
    var commonName by remember(state.draftId) { mutableStateOf("") }
    var notes by remember(state.draftId) { mutableStateOf("") }

    LaunchedEffect(state.taxonScientificName, state.taxonCommonName, state.description) {
        scientificName = state.taxonScientificName.orEmpty()
        commonName = state.taxonCommonName.orEmpty()
        notes = state.description.orEmpty()
    }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading photo review...")
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Photo review", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            state.selectedImagePath?.let { path ->
                AsyncImage(
                    model = File(path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            } ?: Text("No photos in this album.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(state.images, key = { it.id }) { image ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(File(image.photoPath).name, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { scope.launch { vm.deleteImage(image.id) } }) {
                    Text("Delete")
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = scientificName,
                    onValueChange = { scientificName = it },
                    label = { Text("Scientific name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = commonName,
                    onValueChange = { commonName = it },
                    label = { Text("Common name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onAddMorePhotos(state.draftId) }) {
                    Text("Add more photos")
                }
                Button(
                    onClick = {
                        scope.launch {
                            vm.saveDetails(scientificName, commonName, state.taxonInatId, notes)
                        }
                    },
                ) {
                    Text("Save")
                }
                Button(
                    enabled = !state.isSubmitting && state.images.isNotEmpty(),
                    onClick = { vm.submit() },
                ) {
                    Text(if (state.isSubmitting) "Uploading..." else "Upload")
                }
            }
        }
        state.submitError?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
        state.uploadedUrl?.let { url ->
            item {
                Text("Uploaded: $url", color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            vm.deleteAlbum()
                            onBack()
                        }
                    },
                ) {
                    Text("Delete album")
                }
            }
        }
    }
}
