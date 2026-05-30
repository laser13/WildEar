package com.sound2inat.app.ui.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sound2inat.app.ui.common.datedSections
import com.sound2inat.app.ui.common.groupDatedItems

@Suppress("FunctionNaming")
@Composable
fun PhotosScreen(
    onOpenPhotoDraft: (String) -> Unit,
    onStartCapture: () -> Unit,
) {
    val vm: PhotosViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                Text("Loading photos...", modifier = Modifier.align(Alignment.Center))
            }
            state.drafts.isEmpty() -> {
                EmptyPhotosState(
                    onStartCapture = onStartCapture,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> {
                val groups = remember(state.drafts) {
                    groupDatedItems(state.drafts) { it.observedAtUtcMs }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                ) {
                    datedSections(
                        groups = groups,
                        itemKey = { it.id },
                    ) { draft ->
                        PhotoDraftCard(
                            draft = draft,
                            onClick = { onOpenPhotoDraft(draft.id) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun EmptyPhotosState(
    onStartCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Observations", style = MaterialTheme.typography.headlineMedium)
        Text("Photo observations will appear here.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onStartCapture) {
            Text("Take photos")
        }
    }
}

