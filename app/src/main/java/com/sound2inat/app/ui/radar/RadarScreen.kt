package com.sound2inat.app.ui.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private const val MAP_HEIGHT_DP = 280

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(onOpenSettings: () -> Unit) {
    val vm: RadarViewModelHilt = hiltViewModel()
    val state by vm.delegate.state.collectAsState()
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nearby") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            RadarMap(
                pins = state.pins,
                userLocation = state.filter.userLocation,
                onPinTap = { url -> ctx.openCustomTab(url) },
                modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT_DP.dp),
            )
            RadarFilterBar(
                filter = state.filter,
                onRadiusChange = vm::setRadius,
                onPeriodChange = vm::setPeriod,
                onTaxaToggle = vm::toggleTaxon,
            )
            PullToRefreshBox(
                isRefreshing = state.loading && state.species.isNotEmpty(),
                onRefresh = vm.delegate::pullRefresh,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                RadarBody(
                    state = state,
                    onRowTap = { row -> ctx.openCustomTab(row.nearestObservationUrl) },
                )
            }
        }
    }
}

@Composable
private fun RadarBody(
    state: RadarUiState,
    onRowTap: (SpeciesAggregate) -> Unit,
) {
    when {
        state.locationStatus == LocationStatus.NoLocation && state.species.isEmpty() -> {
            EmptyState(
                title = "Grant location to see what's around",
                detail = "WildEar uses your phone's GPS to query iNaturalist for " +
                    "observations within your chosen radius.",
            )
        }
        state.error != null -> {
            EmptyState(title = "Something went wrong", detail = state.error)
        }
        state.species.isEmpty() && state.loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        state.species.isEmpty() -> {
            EmptyState(
                title = "No observations found",
                detail = "Try a wider radius or a longer period.",
            )
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.species, key = { it.taxonId }) { row ->
                    SpeciesCountRow(row = row, onTap = onRowTap)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
