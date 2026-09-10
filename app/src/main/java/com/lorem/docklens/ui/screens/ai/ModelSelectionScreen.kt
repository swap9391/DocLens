package com.lorem.docklens.ui.screens.ai

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import com.lorem.docklens.ai.ModelLoadState
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
    val snackbarHostState = remember { SnackbarHostState() }
    var showTokenDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::importModel) }

    BackHandler(enabled = uiState.selectedGroup != null) { viewModel.selectGroup(null) }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeUserMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (uiState.selectedGroup == null) {
                ExtendedFloatingActionButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Rounded.DriveFolderUpload, contentDescription = null) },
                    text = { Text("Import .task") }
                )
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Text(
                        text = uiState.selectedGroup?.displayName ?: "AI Models",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (uiState.selectedGroup != null) {
                        IconButton(onClick = { viewModel.selectGroup(null) }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showTokenDialog = true }) {
                        Icon(
                            imageVector = if (uiState.hasToken) Icons.Rounded.Key else Icons.Rounded.VpnKeyOff,
                            contentDescription = "Hugging Face token",
                            tint = if (uiState.hasToken) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(onClick = viewModel::refreshModels, enabled = !uiState.isRefreshing) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh models")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (uiState.selectedGroup != null) {
                RepositoryVersionsList(uiState = uiState, viewModel = viewModel, onModelSelected = onModelSelected)
            } else {
                ModelBrowser(
                    uiState = uiState,
                    viewModel = viewModel,
                    onModelSelected = onModelSelected,
                    onAddToken = { showTokenDialog = true }
                )
            }
        }
    }

    uiState.selectedModelDetails?.let { model ->
        ModelDetailsDialog(
            model = model,
            isActive = uiState.activeModelId == model.id,
            onDismiss = { viewModel.showModelDetails(null) },
            onDownload = {
                viewModel.downloadModel(model)
                viewModel.showModelDetails(null)
            },
            onDelete = {
                viewModel.deleteModel(model)
                viewModel.showModelDetails(null)
            },
            onSelect = {
                viewModel.selectModel(model)
                onModelSelected(model)
                viewModel.showModelDetails(null)
            }
        )
    }

    if (showTokenDialog) {
        HuggingFaceTokenDialog(
            hasToken = uiState.hasToken,
            onDismiss = { showTokenDialog = false },
            onSave = { token ->
                viewModel.saveHuggingFaceToken(token)
                showTokenDialog = false
            }
        )
    }
}

@Composable
private fun ModelBrowser(
    uiState: ModelSelectionUiState,
    viewModel: ModelSelectionViewModel,
    onModelSelected: (LlmModel) -> Unit,
    onAddToken: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        item {
            ActiveModelBanner(
                state = uiState.modelLoadState,
                onRetry = viewModel::retryModelLoad
            )
        }

        item {
            ModelTabRow(selected = uiState.tab, onSelected = viewModel::onTabSelected)
        }

        item {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search models and repositories") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear")
                        }
                    }
                },
                shape = MaterialTheme.shapes.large,
                singleLine = true
            )
        }

        uiState.catalogError?.let { error ->
            item { CatalogErrorCard(error = error, onRetry = viewModel::refreshModels) }
        }

        // Models are multi-gigabyte downloads, so surface storage before the user
        // discovers the problem partway through a transfer.
        if (uiState.storage.isLow || uiState.storage.hasIncompleteDownloads) {
            item {
                StorageStatusCard(
                    storage = uiState.storage,
                    onClearIncomplete = viewModel::clearIncompleteDownloads
                )
            }
        }

        if (uiState.tab == ModelTab.MY_MODELS) {
            myModelsSection(uiState, viewModel, onModelSelected)
        } else {
            exploreSection(uiState, viewModel, onModelSelected, onAddToken)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.exploreSection(
    uiState: ModelSelectionUiState,
    viewModel: ModelSelectionViewModel,
    onModelSelected: (LlmModel) -> Unit,
    onAddToken: () -> Unit
) {
    item {
        HardwareSettingsCard(
            useGpu = uiState.useGpu,
            onToggleGpu = viewModel::onToggleGpu
        )
    }

    if (!uiState.hasToken) {
        item { GatedModelsHintCard(onAddToken = onAddToken) }
    }

    if (uiState.searchQuery.isNotBlank()) {
        item { SectionHeader("Matching models (${uiState.searchResults.size})") }

        if (uiState.searchResults.isEmpty() && uiState.remoteGroups.isEmpty()) {
            item { EmptyState("No models match \"${uiState.searchQuery}\".") }
        }

        items(uiState.searchResults, key = { it.id }) { model ->
            ModelCard(
                model = model,
                isActive = uiState.activeModelId == model.id,
                onDownload = { viewModel.downloadModel(model) },
                onPause = { viewModel.pauseDownload(model) },
                onStop = { viewModel.stopDownload(model) },
                onDelete = { viewModel.deleteModel(model) },
                onDetail = { viewModel.showModelDetails(model) },
                onSelect = {
                    viewModel.selectModel(model)
                    onModelSelected(model)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    } else if (uiState.recommendedModels.isNotEmpty()) {
        item { SectionHeader("Recommended for DocLens") }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                items(uiState.recommendedModels, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        isActive = uiState.activeModelId == model.id,
                        onDownload = { viewModel.downloadModel(model) },
                        onPause = { viewModel.pauseDownload(model) },
                        onStop = { viewModel.stopDownload(model) },
                        onDelete = { viewModel.deleteModel(model) },
                        onDetail = { viewModel.showModelDetails(model) },
                        onSelect = {
                            viewModel.selectModel(model)
                            onModelSelected(model)
                        },
                        modifier = Modifier.width(300.dp)
                    )
                }
            }
        }
    }

    item {
        SectionHeader(
            if (uiState.searchQuery.isBlank()) {
                "Hugging Face repositories (${uiState.remoteGroups.size})"
            } else {
                "Matching repositories (${uiState.remoteGroups.size})"
            }
        )
    }

    if (uiState.isRefreshing && uiState.remoteGroups.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }
    } else if (uiState.remoteGroups.isEmpty()) {
        item { EmptyState("No repositories loaded yet. Pull refresh to try again.") }
    }

    items(uiState.remoteGroups, key = { it.id }) { group ->
        RepositoryListItem(group = group, onClick = { viewModel.selectGroup(group) })
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.myModelsSection(
    uiState: ModelSelectionUiState,
    viewModel: ModelSelectionViewModel,
    onModelSelected: (LlmModel) -> Unit
) {
    val installed = if (uiState.searchQuery.isBlank()) {
        uiState.installedModels
    } else {
        uiState.installedModels.filter { it.name.contains(uiState.searchQuery, ignoreCase = true) }
    }

    item { SectionHeader("Downloaded models (${installed.size})") }

    if (installed.isEmpty()) {
        item {
            EmptyState(
                "Nothing downloaded yet. Pick a model from Explore, or import a '.task' bundle " +
                    "you already have on this device."
            )
        }
    }

    items(installed, key = { it.id }) { model ->
        ModelCard(
            model = model,
            isActive = uiState.activeModelId == model.id,
            onDownload = { viewModel.downloadModel(model) },
            onPause = { viewModel.pauseDownload(model) },
            onStop = { viewModel.stopDownload(model) },
            onDelete = { viewModel.deleteModel(model) },
            onDetail = { viewModel.showModelDetails(model) },
            onSelect = {
                viewModel.selectModel(model)
                onModelSelected(model)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun RepositoryVersionsList(
    uiState: ModelSelectionUiState,
    viewModel: ModelSelectionViewModel,
    onModelSelected: (LlmModel) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    text = uiState.selectedGroup?.id.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Every '.task' bundle published by this repository. Smaller " +
                        "quantisations (int4/q4) run fastest on phones.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (uiState.isLoadingGroup && uiState.groupModels.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        }

        items(uiState.groupModels, key = { it.id }) { model ->
            ModelCard(
                model = model,
                isActive = uiState.activeModelId == model.id,
                onDownload = { viewModel.downloadModel(model) },
                onPause = { viewModel.pauseDownload(model) },
                onStop = { viewModel.stopDownload(model) },
                onDelete = { viewModel.deleteModel(model) },
                onDetail = { viewModel.showModelDetails(model) },
                onSelect = {
                    viewModel.selectModel(model)
                    onModelSelected(model)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ------------------------------------------------------------------ components

@Composable
private fun ActiveModelBanner(state: ModelLoadState, onRetry: () -> Unit) {
    val (container, content) = when (state) {
        is ModelLoadState.Failed -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f) to
            MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = container
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Active model",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = content
                )
                when (state) {
                    ModelLoadState.NoModel -> Text(
                        "No model installed yet. Download one to enable chat and analysis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = content
                    )
                    is ModelLoadState.Loading -> Text(
                        "Loading ${state.model.name}...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = content
                    )
                    is ModelLoadState.Ready -> {
                        Text(
                            state.model.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = content
                        )
                        Text(
                            "${state.model.provider} • ${state.model.size} • " +
                                if (state.useGpu) "GPU" else "CPU",
                            style = MaterialTheme.typography.bodySmall,
                            color = content
                        )
                    }
                    is ModelLoadState.Failed -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = content
                    )
                }
            }

            when (state) {
                is ModelLoadState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                is ModelLoadState.Failed -> TextButton(onClick = onRetry) { Text("Retry") }
                is ModelLoadState.Ready -> Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50)
                )
                ModelLoadState.NoModel -> Unit
            }
        }
    }
}

@Composable
private fun ModelTabRow(selected: ModelTab, onSelected: (ModelTab) -> Unit) {
    TabRow(
        selectedTabIndex = selected.ordinal,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        ModelTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelected(tab) },
                text = {
                    Text(
                        text = tab.title,
                        fontWeight = if (tab == selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CatalogErrorCard(error: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun GatedModelsHintCard(onAddToken: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Gated models need a token",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Gemma and Llama return 401 without one. Everything else downloads without setup.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onAddToken) { Text("Add") }
        }
    }
}

@Composable
fun HardwareSettingsCard(useGpu: Boolean, onToggleGpu: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    Text(
                        "Inference engine",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (useGpu) "GPU accelerated (falls back to CPU)" else "CPU only",
                        style = MaterialTheme.typography.bodySmall
                    )
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
fun RepositoryListItem(group: HFRemoteModelGroup, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(group.displayName, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Text(
                "${group.owner} • ${group.versionFiles.size} version" +
                    (if (group.versionFiles.size == 1) "" else "s") +
                    " • ${group.downloads} downloads"
            )
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (group.isGated) Icons.Rounded.Lock else Icons.Rounded.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    )
}

@Composable
fun ModelCard(
    model: LlmModel,
    isActive: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onDetail: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientBrush = Brush.linearGradient(
        colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    )

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        ),
        border = BorderStroke(if (isActive) 1.5.dp else 0.5.dp, gradientBrush)
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
                    Text(
                        model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${model.provider} • ${model.size}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (model.isGated) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = "Gated model",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (model.isDownloaded) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF4CAF50))
                }
            }

            AnimatedVisibility(visible = isActive) {
                Text(
                    text = "Answering your chats",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                model.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            (model.downloadStatus as? DownloadStatus.Error)?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (val status = model.downloadStatus) {
                    is DownloadStatus.Downloaded -> {
                        Button(
                            onClick = onSelect,
                            modifier = Modifier.weight(1f),
                            enabled = !isActive
                        ) {
                            Text(if (isActive) "Active" else "Use this model")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete model",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    is DownloadStatus.Queued -> {
                        Column(modifier = Modifier.weight(1f)) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                strokeCap = StrokeCap.Round
                            )
                            Text("Waiting to start…", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = onStop) {
                            Icon(Icons.Rounded.Stop, contentDescription = "Cancel")
                        }
                    }

                    is DownloadStatus.Downloading, is DownloadStatus.Paused -> {
                        val progress = when (status) {
                            is DownloadStatus.Downloading -> status.progress
                            is DownloadStatus.Paused -> status.progress
                            else -> 0
                        }
                        val isPaused = status is DownloadStatus.Paused

                        Column(modifier = Modifier.weight(1f)) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                strokeCap = StrokeCap.Round
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isPaused) "Paused at $progress%" else "$progress%",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Row {
                                    IconButton(
                                        onClick = if (isPaused) onDownload else onPause,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPaused) {
                                                Icons.Rounded.PlayArrow
                                            } else {
                                                Icons.Rounded.Pause
                                            },
                                            contentDescription = if (isPaused) "Resume" else "Pause",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                                        Icon(
                                            Icons.Rounded.Stop,
                                            contentDescription = "Stop",
                                            modifier = Modifier.size(18.dp)
                                        )
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
                            Text(
                                text = if (status is DownloadStatus.Error) "Retry" else "Download",
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onDetail,
                    contentPadding = PaddingValues(horizontal = 12.dp)
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
            Row(verticalAlignment = Alignment.CenterVertically) { content() }
        }
    }
}

@Composable
fun ModelDetailsDialog(
    model: LlmModel,
    isActive: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(model.description)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                model.repoId?.let { DetailRow("Repository", it) }
                DetailRow("File", model.fileName)
                DetailRow("Provider", model.provider)
                DetailRow("Size", model.size)
                DetailRow("Format", model.format.name)
                DetailRow("Type", if (model.isReasoningModel) "Reasoning" else "General chat")
                DetailRow("Licence", if (model.isGated) "Gated (token required)" else "Open")
                DetailRow("Status", if (isActive) "Active" else if (model.isDownloaded) "Downloaded" else "Not downloaded")
            }
        },
        confirmButton = {
            when {
                model.isDownloaded && !isActive -> Button(onClick = onSelect) { Text("Use this model") }
                model.isDownloaded -> TextButton(onClick = onDelete) { Text("Delete") }
                else -> TextButton(onClick = onDownload) { Text("Download") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun HuggingFaceTokenDialog(
    hasToken: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hugging Face access token", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Gated repositories such as Gemma and Llama reject downloads with " +
                        "401 Unauthorized unless a token is supplied.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "1. Create a read token at huggingface.co/settings/tokens\n" +
                        "2. Open the model page and accept its licence\n" +
                        "3. Paste the token below",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    label = { Text(if (hasToken) "Replace token" else "hf_...") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(token) },
                enabled = token.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (hasToken) {
                    TextButton(onClick = { onSave(null) }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

/**
 * Shows free internal storage and, when present, how much is tied up in
 * incomplete downloads. Running out of space several GB into a multi-gigabyte
 * transfer is the most expensive failure in this screen, so it is surfaced
 * before the download starts rather than reported afterwards.
 */
@Composable
private fun StorageStatusCard(storage: StorageInfo, onClearIncomplete: () -> Unit) {
    val isCritical = storage.isLow
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = if (isCritical) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        val contentColor = if (isCritical) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Storage,
                contentDescription = null,
                tint = contentColor
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${storage.readableUsable} free",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = when {
                        storage.hasIncompleteDownloads ->
                            "${storage.readableIncomplete} is used by incomplete downloads."
                        else ->
                            "Low storage. Most models need 2-8 GB; prefer int4/int8 builds."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
            }
            if (storage.hasIncompleteDownloads) {
                TextButton(onClick = onClearIncomplete) { Text("Clear") }
            }
        }
    }
}

