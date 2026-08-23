package com.lorem.docklens.ui.screens.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lorem.docklens.data.DownloadStatus
import com.lorem.docklens.data.LlmModel
import com.lorem.docklens.data.ModelFormat
import com.lorem.docklens.ui.theme.DockLensTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionScreen(
    viewModel: ModelSelectionViewModel,
    onModelSelected: (LlmModel) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                CenterAlignedTopAppBar(
                    title = { Text("AI Model Selection", fontWeight = FontWeight.Bold) }
                )
                
                // 1. Recommended Section
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
                                RecommendedModelCard(
                                    model = model,
                                    onDownload = { viewModel.downloadModel(model) },
                                    onDetail = { /* Show details */ },
                                    onClick = { if (model.downloadStatus is DownloadStatus.Downloaded) onModelSelected(model) }
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
                    placeholder = { Text("Search models (GLM, Qwen, Phi...)") },
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
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 3. Hardware Settings
            item {
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
                                Text(if (uiState.useGpu) "GPU Accelerated" else "CPU Only", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Switch(
                            checked = uiState.useGpu,
                            onCheckedChange = { viewModel.onToggleGpu(it) },
                            thumbContent = if (uiState.useGpu) {
                                { Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
            }

            item {
                Text(
                    text = if (uiState.searchQuery.isEmpty()) "All Available Models" else "Search Results",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp)
                )
            }

            items(uiState.filteredModels) { model ->
                ModelListItem(
                    model = model,
                    onDownload = { viewModel.downloadModel(model) },
                    onClick = { if (model.downloadStatus is DownloadStatus.Downloaded) onModelSelected(model) }
                )
            }
        }
    }
}

@Composable
fun RecommendedModelCard(
    model: LlmModel,
    onDownload: () -> Unit,
    onDetail: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(280.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
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
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            Text(model.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (model.downloadStatus !is DownloadStatus.Downloaded) {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                        enabled = model.downloadStatus !is DownloadStatus.Downloading,
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        if (model.downloadStatus is DownloadStatus.Downloading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download", fontSize = 12.sp)
                        }
                    }
                } else {
                    Button(onClick = onClick, modifier = Modifier.weight(1f)) {
                        Text("Select")
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
fun ModelListItem(
    model: LlmModel,
    onDownload: () -> Unit,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(model.name, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text("${model.provider} • ${model.size} • ${if (model.isReasoningModel) "Reasoning" else "Chat"}") },
        leadingContent = {
            Icon(
                imageVector = if (model.isReasoningModel) Icons.Rounded.Psychology else Icons.Rounded.ChatBubble,
                contentDescription = null,
                tint = if (model.isRecommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        },
        trailingContent = {
            when (val status = model.downloadStatus) {
                is DownloadStatus.NotDownloaded -> {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                is DownloadStatus.Downloading -> {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { status.progress / 100f },
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                        Text("${status.progress}%", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp)
                    }
                }
                is DownloadStatus.Downloaded -> {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF4CAF50))
                }
                is DownloadStatus.Error -> {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun ModelSelectionScreenPreview() {
    DockLensTheme {
        val mockModels = listOf(
            LlmModel(
                id = "1",
                name = "DeepSeek R1 Qwen 1.5B",
                provider = "DeepSeek",
                size = "1.1 GB",
                description = "Specialized reasoning model with <think> tag support.",
                downloadUrl = "",
                format = ModelFormat.MEDIAPIPE_TASK,
                isRecommended = true,
                isReasoningModel = true
            ),
            LlmModel(
                id = "2",
                name = "Gemma 2B IT",
                provider = "Google",
                size = "1.35 GB",
                description = "Google's lightweight open model.",
                downloadUrl = "",
                format = ModelFormat.MEDIAPIPE_TASK,
                isRecommended = true
            )
        )
        
        Scaffold(
            topBar = {
                Column {
                    CenterAlignedTopAppBar(title = { Text("AI Model Selection") })
                    Text(
                        text = "Recommended for DocLens",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(mockModels) { RecommendedModelCard(it, {}, {}, {}) }
                    }
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        placeholder = { Text("Search models...") },
                        shape = MaterialTheme.shapes.large
                    )
                }
            }
        ) { innerPadding ->
            LazyColumn(modifier = Modifier.padding(innerPadding)) {
                item {
                    Surface(modifier = Modifier.padding(16.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("GPU Accelerated", modifier = Modifier.weight(1f))
                            Switch(checked = true, onCheckedChange = {})
                        }
                    }
                }
                items(mockModels) { ModelListItem(it, {}, {}) }
            }
        }
    }
}
