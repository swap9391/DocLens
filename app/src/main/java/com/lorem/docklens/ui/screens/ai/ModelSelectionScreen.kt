package com.lorem.docklens.ui.screens.ai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
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
import com.lorem.docklens.data.ModelFormat

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
                
                if (uiState.selectedGroup == null) {
                    // 1. Recommended Section (Only on main screen)
                    AnimatedVisibility(
                        visible = uiState.searchQuery.isEmpty(),
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column {
                            Text(
                                text = "Recommended for DocLens",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                items(uiState.recommendedModels) { model ->
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
                                        modifier = Modifier.width(280.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 2. Search Bar
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.onSearchQueryChanged(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search repositories...") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = if (uiState.searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear")
                                }
                            }
                        } else null,
                        shape = MaterialTheme.shapes.large,
                        singleLine = true
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (uiState.selectedGroup != null) {
                // SIBLINGS SCREEN: Show versions of the selected repository as cards
                val groupModels = viewModel.getModelsForGroup(uiState.selectedGroup!!.id)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            text = "Available versions in this repository:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(groupModels) { model ->
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
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                // MAIN LIST SCREEN: Show Hardware settings and Repository groups
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // 3. Hardware Settings
                    item {
                        HardwareSettingsCard(
                            useGpu = uiState.useGpu,
                            onToggleGpu = { viewModel.onToggleGpu(it) }
                        )
                    }

                    item {
                        Text(
                            text = if (uiState.searchQuery.isEmpty()) "Hugging Face Repositories" else "Search Results",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp)
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
fun HardwareSettingsCard(useGpu: Boolean, onToggleGpu: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.padding(16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Inference Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (useGpu) "GPU Accelerated" else "CPU Only", style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(
                checked = useGpu,
                onCheckedChange = onToggleGpu,
                thumbContent = if (useGpu) {
                    { Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(16.dp)) }
                } else null
            )
        }
    }
}

@Composable
fun RepositoryListItem(
    group: HFRemoteModelGroup,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(group.displayName, fontWeight = FontWeight.SemiBold) },
        supportingContent = { 
            Text("${group.id.substringBefore("/")} • ${group.versionFiles.size} versions • ${group.downloads} downloads") 
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.FolderZip, 
                        contentDescription = null, 
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    )
}

@Composable
fun ModelCard(
    model: LlmModel,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onDetail: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientBrush = Brush.linearGradient(
        colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    )

    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
        border = BorderStroke(0.5.dp, gradientBrush)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            model.name.take(1).uppercase(),
                            color = MaterialTheme.colorScheme.onPrimary,
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
                            Text("Select")
                        }
                    }
                    is DownloadStatus.Downloading, is DownloadStatus.Paused -> {
                        val progress = when(status) {
                            is DownloadStatus.Downloading -> status.progress
                            is DownloadStatus.Paused -> status.progress
                            else -> 0
                        }
                        
                        Column(modifier = Modifier.weight(1f)) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                strokeCap = StrokeCap.Round
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${progress}%", style = MaterialTheme.typography.labelSmall)
                                Row {
                                    IconButton(
                                        onClick = if (status is DownloadStatus.Downloading) onPause else onDownload,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            if (status is DownloadStatus.Downloading) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        GradientButton(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f),
                            gradient = gradientBrush
                        ) {
                            Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download", fontSize = 12.sp)
                        }
                    }
                }
                
                OutlinedButton(
                    onClick = onDetail,
                    modifier = Modifier.weight(0.6f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Details", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: Brush,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        contentPadding = PaddingValues(),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        shape = MaterialTheme.shapes.medium
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                content()
            }
        }
    }
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
        title = { Text(model.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(model.description)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                DetailRow("Provider", model.provider)
                DetailRow("Size", model.size)
                DetailRow("Format", model.format.name)
                DetailRow("Type", if (model.isReasoningModel) "Reasoning" else "General Chat")
            }
        },
        confirmButton = {
            if (model.downloadStatus is DownloadStatus.Downloaded) {
                Button(onClick = onSelect) { Text("Select Model") }
            } else if (model.downloadStatus is DownloadStatus.NotDownloaded) {
                TextButton(onClick = onDownload) { Text("Download") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
