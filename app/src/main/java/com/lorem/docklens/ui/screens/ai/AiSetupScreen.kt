package com.lorem.docklens.ui.screens.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lorem.docklens.ui.theme.DockLensTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSetupScreen(
    viewModel: AiSetupViewModel,
    onReady: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    
    val modelUrl = "https://huggingface.co/t-ghosh/gemma-tflite/resolve/main/gemma-1.1-2b-it-cpu-int4.bin?download=true"

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("AI Engine Setup", fontWeight = FontWeight.Bold) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val state = uiState) {
                is AiSetupState.Idle -> {
                    SetupStep(
                        icon = Icons.Rounded.Download,
                        title = "Download AI Model",
                        description = "DocLens requires a small local AI model (approx 1.5GB) to process documents privately on your device.",
                        buttonText = "Download Model",
                        onButtonClick = { viewModel.startDownload(modelUrl) }
                    )
                }
                is AiSetupState.Downloading -> {
                    val progress = state.progress
                    Text("Downloading Model...", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        strokeCap = ProgressIndicatorDefaults.LinearStrokeCap
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("$progress%", style = MaterialTheme.typography.bodyMedium)
                }
                is AiSetupState.Initializing -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Initializing AI Engine...", style = MaterialTheme.typography.titleLarge)
                }
                is AiSetupState.Ready -> {
                    SetupStep(
                        icon = Icons.Rounded.TaskAlt,
                        title = "AI Engine Ready",
                        description = "The local AI engine is initialized and ready to analyze your documents.",
                        buttonText = "Continue to App",
                        onButtonClick = onReady,
                        secondaryContent = {
                            Spacer(modifier = Modifier.height(24.dp))
                            OutlinedButton(
                                onClick = { viewModel.runTestInference() }
                            ) {
                                Text("Test AI Inference")
                            }
                            testResult?.let {
                                Text(
                                    text = "Test Result: $it",
                                    modifier = Modifier.padding(top = 16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    )
                }
                is AiSetupState.Error -> {
                    SetupStep(
                        icon = Icons.Rounded.Error,
                        title = "Setup Failed",
                        description = state.message,
                        buttonText = "Try Again",
                        onButtonClick = { viewModel.startDownload(modelUrl) }
                    )
                }
            }
        }
    }
}

@Composable
fun SetupStep(
    icon: ImageVector,
    title: String,
    description: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    secondaryContent: @Composable () -> Unit = {}
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(80.dp),
        tint = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = description,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(
        onClick = onButtonClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Text(buttonText)
    }
    secondaryContent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun AiSetupScreenPreview() {
    DockLensTheme {
        Scaffold(
            topBar = { CenterAlignedTopAppBar(title = { Text("AI Engine Setup") }) }
        ) { innerPadding ->
            Column(
                modifier = Modifier.padding(innerPadding).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                SetupStep(
                    icon = Icons.Rounded.Download,
                    title = "Download AI Model",
                    description = "DocLens requires a small local AI model (approx 1.5GB) to process documents privately.",
                    buttonText = "Download Model",
                    onButtonClick = {}
                )
            }
        }
    }
}
