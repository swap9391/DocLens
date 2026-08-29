package com.lorem.docklens.ui.screens.ai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lorem.docklens.data.DownloadStatus
import com.lorem.docklens.data.HFRemoteModelGroup
import com.lorem.docklens.data.LlmModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionScreen(
    viewModel: ModelSelectionViewModel,
    onModelSelected: (LlmModel) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Handle back navigation when in repository detail view
    BackHandler(enabled = uiState.selectedGroup != null) {
        viewModel.selectGroup(null)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                CenterAlignedTopAppBar(
                    title = { 
                        Text(
                            text = uiState.selectedGroup?.displayName ?: "AI Model Selection", 
                            fontWeight = FontWeight.Bold 
                        ) 
                    },
                    navigationIcon = {
                        if (uiState.selectedGroup != null) {
                            IconButton(onClick = { viewModel.selectGroup(null) }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (uiState.selectedGroup == null) {
                // 1. Downloaded Models Section (Horizontal Row)
                if (uiState.downloadedModels.isNotEmpty() && uiState.searchQuery.isEmpty()) {
                    item {
                        Text(
                            text = "Downloaded Models",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            items(uiState.downloadedModels) { model ->
                                val isSelected = model.id == uiState.selectedModelId
                                ModelCard(
                                    model = model,
                                    onDownload = {},
                                    onPause = {},
                                    onStop = {},
                                    onDetail = { viewModel.showModelDetails(model) },
                                    onClick = { 
                                        viewModel.selectModel(model)
                                        onModelSelected(model)
                                    },
                                    isSelected = isSelected,
                                    modifier = Modifier.width(280.dp)
                                )
                            }
                        }
                    }
                }

                // 2. Hardware Settings
                item {
                    HardwareSettingsCard(
                        useGpu = uiState.useGpu,
                        onToggleGpu = { viewModel.onToggleGpu(it) }
                    )
                }

                // 3. Recommended / Remote Search Section
                item {
                    Text(
                        text = if (uiState.searchQuery.isEmpty()) "Available Repositories" else "Search Results",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp)
                    )
                }

                item {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.onSearchQueryChanged(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search repositories...") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        shape = MaterialTheme.shapes.large,
                        singleLine = true
                    )
                }

                if (uiState.isRefreshing && uiState.remoteGroups.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }

                items(uiState.remoteGroups) { group ->
                    RepositoryListItem(
                        group = group,
                        onClick = { viewModel.selectGroup(group) }
                    )
                }
            } else {
                // SIBLINGS SCREEN: Show versions of the selected repository
                val groupModels = viewModel.getModelsForGroup(uiState.selectedGroup!!.id)
                item {
                    Text(
                        text = "Available versions in this repository:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
                items(groupModels) { model ->
                    val isSelected = model.id == uiState.selectedModelId
                    ModelCard(
                        model = model,
                        onDownload = { viewModel.downloadModel(model) },
                        onPause = { viewModel.pauseDownload(model) },
                        onStop = { viewModel.stopDownload(model) },
                        onDetail = { viewModel.showModelDetails(model) },
                        onClick = { 
                            if (model.downloadStatus is DownloadStatus.Downloaded) {
                                viewModel.selectModel(model)
                                onModelSelected(model)
                            } 
                        },
                        isSelected = isSelected,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                    )
                }
            }
        }
    }

    // Details Dialog
    uiState.selectedModelDetails?.let { model ->
        ModelDetailsDialog(
            model = model,
            onDismiss = { viewModel.showModelDetails(null) },
            onDownload = { 
                viewModel.downloadModel(model)
                viewModel.showModelDetails(null)
            },
            onSelect = {
                viewModel.selectModel(model)
                onModelSelected(model)
                viewModel.showModelDetails(null)
            }
        )
    }
}

@Composable
fun ModelCard(
    model: LlmModel,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onDetail: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false
) {
    Card(
        // Card is clickable only if the model is downloaded
        modifier = modifier.clickable(
            enabled = model.downloadStatus is DownloadStatus.Downloaded,
            onClick = onClick
        ),
        shape = MaterialTheme.shapes.large,
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f) 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            model.name.take(1).uppercase(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(model.provider, style = MaterialTheme.typography.labelSmall)
                }
                if (model.downloadStatus is DownloadStatus.Downloaded) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF4CAF50))
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            Text(model.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (val status = model.downloadStatus) {
                    is DownloadStatus.Downloaded -> {
                        Button(onClick = onClick, modifier = Modifier.weight(1f)) {
                            Text(if (isSelected) "Current" else "Select")
                        }
                    }
                    is DownloadStatus.Downloading -> {
                        Column(modifier = Modifier.weight(1f)) {
                            LinearProgressIndicator(
                                progress = { status.progress / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                strokeCap = StrokeCap.Round
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${status.progress}%", style = MaterialTheme.typography.labelSmall)
                                Row {
                                    IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.Pause, null, modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                    is DownloadStatus.Paused -> {
                        Column(modifier = Modifier.weight(1f)) {
                            LinearProgressIndicator(
                                progress = { status.progress / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                strokeCap = StrokeCap.Round,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${status.progress}% (Paused)", style = MaterialTheme.typography.labelSmall)
                                Row {
                                    IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download")
                        }
                    }
                }
                
                OutlinedIconButton(onClick = onDetail) {
                    Icon(Icons.Rounded.Info, null)
                }
            }
        }
    }
}

@Composable
fun HardwareSettingsCard(useGpu: Boolean, onToggleGpu: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.padding(16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("GPU Acceleration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Faster, but uses more battery", style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(checked = useGpu, onCheckedChange = onToggleGpu)
        }
    }
}

@Composable
fun RepositoryListItem(group: HFRemoteModelGroup, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(group.displayName, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text("${group.versionFiles.size} versions available") },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }
    )
}

@Composable
fun ModelDetailsDialog(
    model: LlmModel,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onSelect: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.name) },
        text = {
            Column {
                Text(model.description)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Size: ${model.size}", fontWeight = FontWeight.Bold)
                Text("Provider: ${model.provider}")
                Text("Format: ${model.format}")
            }
        },
        confirmButton = {
            if (model.downloadStatus is DownloadStatus.Downloaded) {
                Button(onClick = onSelect) { Text("Select Model") }
            } else {
                Button(onClick = onDownload) { Text("Download") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
