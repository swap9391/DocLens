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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
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
            ChatInputBar(
                inputText = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                onAttachClick = { imagePickerLauncher.launch("image/*") },
                attachedDocName = attachedDoc?.name,
                onDetachDoc = { viewModel.detachDocument() }
            )
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
    onAttachClick: () -> Unit,
    attachedDocName: String?,
    onDetachDoc: () -> Unit
) {
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
                    enabled = inputText.isNotBlank(),
                    modifier = Modifier
                        .background(
                            if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Send",
                        tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
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
