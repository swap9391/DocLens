package com.lorem.docklens.ui.screens.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lorem.docklens.ai.ModelLoadState
import com.lorem.docklens.data.ChatMessageEntity
import com.lorem.docklens.ui.theme.DockLensTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
    val attachedDoc by viewModel.attachedDocument.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingResponse.collectAsStateWithLifecycle()
    val currentReasoning by viewModel.currentReasoning.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val canSend by viewModel.canSend.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        // Handle ad-hoc image attachment
    }

    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty() || streamingText != null) {
            listState.animateScrollToItem(if (streamingText != null) messages.size else messages.size - 1)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DocLens AI", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = when (val state = modelState) {
                                ModelLoadState.NoModel -> "No model installed"
                                is ModelLoadState.Loading -> "Loading ${state.model.name}…"
                                is ModelLoadState.Ready -> state.model.name
                                is ModelLoadState.Failed -> "Model failed to load"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (modelState is ModelLoadState.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (attachedDoc != null) {
                            Text(
                                text = "Attached: ${attachedDoc?.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.Close, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Model Settings")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                ModelStatusBar(
                    state = modelState,
                    onOpenSettings = onSettingsClick,
                    onRetry = viewModel::retryModelLoad
                )
                ChatInputBar(
                    inputText = inputText,
                    onTextChange = { inputText = it },
                    onSend = {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    },
                    canSend = canSend,
                    onAttachClick = { imagePickerLauncher.launch("image/*") },
                    attachedDocName = attachedDoc?.name,
                    onDetachDoc = { viewModel.detachDocument() }
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(messages) { message ->
                MessageBubble(
                    content = message.content,
                    reasoning = message.reasoning,
                    isUser = message.isUser,
                    timestamp = message.timestamp,
                    imagePath = message.imagePath
                )
            }
            
            if (isTyping || streamingText != null) {
                item {
                    MessageBubble(
                        content = streamingText ?: "",
                        reasoning = currentReasoning,
                        isUser = false,
                        timestamp = System.currentTimeMillis(),
                        isStreaming = true
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelStatusBar(
    state: ModelLoadState,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit
) {
    if (state is ModelLoadState.Ready) return

    val container = when (state) {
        is ModelLoadState.Failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (state) {
        is ModelLoadState.Failed -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(color = container, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                is ModelLoadState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
                else -> Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = when (state) {
                    ModelLoadState.NoModel ->
                        "Download an AI model to start chatting."
                    is ModelLoadState.Loading ->
                        "Starting ${state.model.name}. Chat unlocks in a moment."
                    is ModelLoadState.Failed -> state.message
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            when (state) {
                ModelLoadState.NoModel -> TextButton(onClick = onOpenSettings) { Text("Get a model") }
                is ModelLoadState.Failed -> TextButton(onClick = onRetry) { Text("Retry") }
                else -> Unit
            }
        }
    }
}

@Composable
fun MessageBubble(
    content: String,
    reasoning: String?,
    isUser: Boolean,
    timestamp: Long,
    imagePath: String? = null,
    isStreaming: Boolean = false
) {
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (imagePath != null) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .heightIn(max = 240.dp)
                    .padding(bottom = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                AsyncImage(
                    model = imagePath,
                    contentDescription = "Attached image",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        if (!isUser && reasoning != null && reasoning.isNotBlank()) {
            ReasoningBlock(reasoning)
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        Surface(
            color = bubbleColor,
            shape = shape,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = content.ifEmpty { if (isStreaming) "..." else "" },
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = formatTime(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ReasoningBlock(reasoning: String) {
    var expanded by remember { mutableStateOf(true) }
    
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(max = 300.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded }
            ) {
                Icon(Icons.Rounded.Psychology, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reasoning", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
            
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun ChatInputBar(
    inputText: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    onAttachClick: () -> Unit,
    attachedDocName: String?,
    onDetachDoc: () -> Unit
) {
    val sendEnabled = canSend && inputText.isNotBlank()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (attachedDocName != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Description, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(attachedDocName, style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onDetachDoc, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Rounded.Close, null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onAttachClick) {
                    Icon(
                        Icons.Rounded.AddPhotoAlternate,
                        contentDescription = "Attach",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                TextField(
                    value = inputText,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    placeholder = { Text("Ask about the document...") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    maxLines = 4
                )
                
                IconButton(
                    onClick = onSend,
                    enabled = sendEnabled,
                    modifier = Modifier
                        .background(
                            if (sendEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Send",
                        tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
