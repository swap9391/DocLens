package com.lorem.docklens.ui.screens.compare

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lorem.docklens.data.DocumentEntity
import com.lorem.docklens.ui.screens.specialized.AnalysisResultCard
import com.lorem.docklens.ui.theme.DockLensTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareDocumentsScreen(
    viewModel: CompareDocumentsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val docs by viewModel.docs.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Compare Documents") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DocSummaryCard(modifier = Modifier.weight(1f), name = docs.first?.name ?: "Loading...", type = docs.first?.type ?: "")
                Icon(Icons.Rounded.Compare, contentDescription = null, modifier = Modifier.align(Alignment.CenterVertically))
                DocSummaryCard(modifier = Modifier.weight(1f), name = docs.second?.name ?: "Loading...", type = docs.second?.type ?: "")
            }

            Spacer(modifier = Modifier.height(24.dp))

            when (val state = uiState) {
                is CompareState.Idle, is CompareState.Processing -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 64.dp)
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("DocLens is comparing...", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                is CompareState.Success -> {
                    AnalysisResultCard(state.result)
                }
                is CompareState.Error -> {
                    Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun DocSummaryCard(modifier: Modifier, name: String, type: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(type, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun CompareDocumentsPreview() {
    DockLensTheme {
        Scaffold(
            topBar = { CenterAlignedTopAppBar(title = { Text("Compare Documents") }) }
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DocSummaryCard(Modifier.weight(1f), "Agreement_V1.pdf", "PDF")
                    Icon(Icons.Rounded.Compare, null, Modifier.align(Alignment.CenterVertically))
                    DocSummaryCard(Modifier.weight(1f), "Agreement_V2.pdf", "PDF")
                }
                Spacer(modifier = Modifier.height(24.dp))
                AnalysisResultCard(
                    "1. Clause 4.2: Termination notice increased from 30 to 60 days.\n" +
                    "2. Pricing: Service fee increased by 5% in V2.\n" +
                    "3. Liability: Added indemnification for third-party claims."
                )
            }
        }
    }
}
