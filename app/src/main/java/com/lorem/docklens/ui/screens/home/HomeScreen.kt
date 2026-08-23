package com.lorem.docklens.ui.screens.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lorem.docklens.data.DocumentEntity
import com.lorem.docklens.ui.theme.DockLensTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onScanClick: () -> Unit,
    onChatClick: (Long?) -> Unit,
    onAnalysisClick: (String, Long) -> Unit,
    onCompareClick: (Long, Long) -> Unit
) {
    val context = LocalContext.current
    val documents by viewModel.allDocuments.collectAsStateWithLifecycle()
    var showAddOptions by remember { mutableStateOf(false) }
    var selectedDocsForCompare by remember { mutableStateOf<List<Long>>(emptyList()) }
    var isCompareMode by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val mimeType = context.contentResolver.getType(it)
            val type = if (mimeType?.contains("pdf", ignoreCase = true) == true) "PDF" else "IMAGE"
            viewModel.importDocument(context, it, type)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onScanClick()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("DocLens", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { onChatClick(null) }) {
                        Icon(Icons.Rounded.Chat, contentDescription = "AI Chat")
                    }
                    if (documents.size >= 2) {
                        IconButton(onClick = { 
                            isCompareMode = !isCompareMode
                            if (!isCompareMode) selectedDocsForCompare = emptyList()
                        }) {
                            Icon(
                                Icons.Rounded.CompareArrows, 
                                contentDescription = "Compare",
                                tint = if (isCompareMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (isCompareMode) {
                if (selectedDocsForCompare.size == 2) {
                    ExtendedFloatingActionButton(
                        onClick = { onCompareClick(selectedDocsForCompare[0], selectedDocsForCompare[1]) },
                        icon = { Icon(Icons.Rounded.AutoAwesome, null) },
                        text = { Text("Compare Selected") },
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }
            } else {
                FloatingActionButton(
                    onClick = { showAddOptions = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add Document")
                }
            }
        }
    ) { innerPadding ->
        if (documents.isEmpty()) {
            EmptyState(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(documents, key = { it.id }) { doc ->
                    val isSelected = selectedDocsForCompare.contains(doc.id)
                    DocumentItem(
                        document = doc,
                        isCompareMode = isCompareMode,
                        isSelected = isSelected,
                        onDelete = { viewModel.deleteDocument(doc) },
                        onChat = { onChatClick(doc.id) },
                        onAnalyzeMedical = { onAnalysisClick("MEDICAL", doc.id) },
                        onAnalyzeXRay = { onAnalysisClick("XRAY", doc.id) },
                        onToggleSelect = {
                            if (isSelected) {
                                selectedDocsForCompare = selectedDocsForCompare - doc.id
                            } else if (selectedDocsForCompare.size < 2) {
                                selectedDocsForCompare = selectedDocsForCompare + doc.id
                            }
                        }
                    )
                }
            }
        }

        if (showAddOptions) {
            ModalBottomSheet(
                onDismissRequest = { showAddOptions = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
                ) {
                    Text(
                        text = "Add Document",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                    
                    OptionItem(
                        icon = Icons.Rounded.CameraAlt,
                        label = "Scan with Camera",
                        description = "Capture a physical document",
                        onClick = {
                            showAddOptions = false
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OptionItem(
                        icon = Icons.Rounded.FileOpen,
                        label = "Import File",
                        description = "Choose a PDF or Image from your device",
                        onClick = {
                            showAddOptions = false
                            filePickerLauncher.launch(arrayOf("image/*", "application/pdf"))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DocumentItem(
    document: DocumentEntity,
    isCompareMode: Boolean,
    isSelected: Boolean,
    onDelete: () -> Unit,
    onChat: () -> Unit,
    onAnalyzeMedical: () -> Unit,
    onAnalyzeXRay: () -> Unit,
    onToggleSelect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        onClick = if (isCompareMode) onToggleSelect else ({})
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    if (document.type == "IMAGE") {
                        AsyncImage(
                            model = document.uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(document.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${document.type} • ${formatDate(document.timestamp)}", style = MaterialTheme.typography.bodySmall)
                }

                if (!isCompareMode) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                }
            }

            if (!isCompareMode) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = onChat,
                        label = { Text("AI Chat") },
                        leadingIcon = { Icon(Icons.Rounded.Chat, null, modifier = Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = onAnalyzeMedical,
                        label = { Text("Medical") },
                        leadingIcon = { Icon(Icons.Rounded.MedicalServices, null, modifier = Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = onAnalyzeXRay,
                        label = { Text("X-Ray") },
                        leadingIcon = { Icon(Icons.Rounded.Visibility, null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }
    }
}

@Composable
fun OptionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Your library is empty", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(text = "Tap the + button to add your first document", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 48.dp))
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    DockLensTheme {
        val mockDocs = listOf(
            DocumentEntity(id = 1, name = "Medical Report.pdf", type = "PDF", uri = "", timestamp = System.currentTimeMillis()),
            DocumentEntity(id = 2, name = "X-Ray Chest.jpg", type = "IMAGE", uri = "", timestamp = System.currentTimeMillis())
        )
        
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("DocLens", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = {}) { Icon(Icons.Rounded.Chat, null) }
                        IconButton(onClick = {}) { Icon(Icons.Rounded.CompareArrows, null) }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {}, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Rounded.Add, null)
                }
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(mockDocs) { doc ->
                    DocumentItem(
                        document = doc,
                        isCompareMode = false,
                        isSelected = false,
                        onDelete = {},
                        onChat = {},
                        onAnalyzeMedical = {},
                        onAnalyzeXRay = {},
                        onToggleSelect = {}
                    )
                }
            }
        }
    }
}
