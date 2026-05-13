package com.sound2inat.app.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Photo review", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Save draft keeps this album in the app. Upload to iNaturalist sends it to your account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PhotoGallerySection(
            images = state.images,
            onDeleteImage = { imageId -> scope.launch { vm.deleteImage(imageId) } },
        )

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

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 2,
        ) {
            FilledTonalButton(onClick = { onAddMorePhotos(state.draftId) }) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                Text("Take more photos")
            }
            Button(
                onClick = {
                    scope.launch {
                        vm.saveDetails(scientificName, commonName, state.taxonInatId, notes)
                    }
                },
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Text("Save draft")
            }
            Button(
                enabled = !state.isSubmitting && state.images.isNotEmpty(),
                onClick = { vm.submit() },
            ) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                Text(if (state.isSubmitting) "Uploading..." else "Upload to iNaturalist")
            }
        }

        state.submitError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        state.uploadedUrl?.let {
            Text("Uploaded to iNaturalist.", color = MaterialTheme.colorScheme.primary)
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 2,
        ) {
            OutlinedButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
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
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Text("Delete draft")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoGallerySection(
    images: List<com.sound2inat.storage.PhotoDraftImageEntity>,
    onDeleteImage: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (images.isEmpty()) "No photos yet" else "Photos (${images.size})",
            style = MaterialTheme.typography.titleMedium,
        )
        if (images.isEmpty()) {
            Text(
                "Take a few photos to build the album. You can delete any shot before upload.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 3,
            ) {
                images.forEachIndexed { index, image ->
                    PhotoThumbnail(
                        index = index + 1,
                        image = image,
                        onDelete = { onDeleteImage(image.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoThumbnail(
    index: Int,
    image: com.sound2inat.storage.PhotoDraftImageEntity,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(112.dp)
            .semantics { contentDescription = "Photo $index" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            AsyncImage(
                model = File(image.photoPath),
                contentDescription = "Photo $index",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(0.dp)),
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(999.dp),
                    ),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete photo $index")
            }
        }
    }
}
