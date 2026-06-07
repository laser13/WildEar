package com.sound2inat.app.ui.photos

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sound2inat.app.R
import com.sound2inat.app.ui.common.EmptyState
import com.sound2inat.app.ui.common.Sound2iNatTopBar
import com.sound2inat.app.ui.common.datedSections
import com.sound2inat.app.ui.common.groupDatedItems

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
fun PhotosScreen(
    onOpenPhotoDraft: (String) -> Unit,
    onStartCapture: () -> Unit,
) {
    val vm: PhotosViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Sound2iNatTopBar(title = stringResource(R.string.title_photos))
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> {
                    Text(stringResource(R.string.photos_loading), modifier = Modifier.align(Alignment.Center))
                }
                state.drafts.isEmpty() -> {
                    EmptyState(
                        modifier = Modifier.align(Alignment.Center),
                        icon = Icons.Outlined.PhotoLibrary,
                        title = stringResource(R.string.photos_empty_title),
                        detail = stringResource(R.string.photos_empty_subtitle),
                        action = {
                            Button(onClick = onStartCapture) {
                                Text(stringResource(R.string.photos_action_take_photos))
                            }
                        },
                    )
                }
                else -> {
                    val context = LocalContext.current
                    val syncMessage = state.lastSyncResult?.let { r ->
                        when {
                            r.failed > 0 -> stringResource(R.string.photos_sync_done_with_errors, r.synced, r.failed)
                            r.synced > 0 -> stringResource(R.string.photos_sync_done, r.synced)
                            else -> stringResource(R.string.photos_sync_nothing)
                        }
                    }
                    LaunchedEffect(state.lastSyncResult) {
                        if (syncMessage == null) return@LaunchedEffect
                        Toast.makeText(context, syncMessage, Toast.LENGTH_SHORT).show()
                        vm.clearSyncResult()
                    }
                    val groups = remember(state.drafts) {
                        groupDatedItems(state.drafts) { it.observedAtUtcMs }
                    }
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = vm::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
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
    }
}
