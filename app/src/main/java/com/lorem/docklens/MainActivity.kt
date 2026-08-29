package com.lorem.docklens

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lorem.docklens.ai.MediaPipeInferenceManager
import com.lorem.docklens.ai.OcrHelper
import com.lorem.docklens.data.*
import com.lorem.docklens.ui.navigation.Screen
import com.lorem.docklens.ui.screens.ai.ModelSelectionScreen
import com.lorem.docklens.ui.screens.ai.ModelSelectionViewModel
import com.lorem.docklens.ui.screens.ai.ModelSelectionViewModelFactory
import com.lorem.docklens.ui.screens.camera.CameraScannerScreen
import com.lorem.docklens.ui.screens.chat.ChatScreen
import com.lorem.docklens.ui.screens.chat.ChatViewModel
import com.lorem.docklens.ui.screens.chat.ChatViewModelFactory
import com.lorem.docklens.ui.screens.compare.CompareDocumentsScreen
import com.lorem.docklens.ui.screens.compare.CompareDocumentsViewModel
import com.lorem.docklens.ui.screens.compare.CompareDocumentsViewModelFactory
import com.lorem.docklens.ui.screens.home.HomeScreen
import com.lorem.docklens.ui.screens.home.HomeViewModel
import com.lorem.docklens.ui.screens.home.HomeViewModelFactory
import com.lorem.docklens.ui.screens.onboarding.AskAnythingScreen
import com.lorem.docklens.ui.screens.onboarding.PrivacyScreen
import com.lorem.docklens.ui.screens.onboarding.WelcomeScreen
import com.lorem.docklens.ui.screens.specialized.SpecializedAnalysisScreen
import com.lorem.docklens.ui.screens.specialized.SpecializedAnalysisViewModel
import com.lorem.docklens.ui.screens.specialized.SpecializedAnalysisViewModelFactory
import com.lorem.docklens.ui.theme.DockLensTheme
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DockLensTheme {
                DocLensApp()
            }
        }
    }
}

@Composable
fun DocLensApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Data Layer
    val database = remember { AppDatabase.getDatabase(context) }
    val documentRepository = remember { DocumentRepository(database.documentDao()) }
    val chatRepository = remember { ChatRepository(database.chatDao()) }
    val modelRepository = remember { ModelRepository(context) }
    val preferencesRepository = remember { UserPreferencesRepository(context) }
    
    // AI Layer
    val inferenceManager = remember { MediaPipeInferenceManager(context) }
    val ocrHelper = remember { OcrHelper(context) }
    
    // Global Home ViewModel
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(documentRepository)
    )

    val onboardingCompleted by preferencesRepository.onboardingCompleted.collectAsState(initial = null)
    val selectedModelId by preferencesRepository.selectedModelId.collectAsState(initial = null)
    val useGpu by preferencesRepository.useGpu.collectAsState(initial = false)
    val models by modelRepository.availableModels.collectAsState()
    
    // 1. Initial Routing Logic: Only runs once to determine where to start
    var hasRoutedInitially by remember { mutableStateOf(false) }
    LaunchedEffect(onboardingCompleted) {
        if (onboardingCompleted == true && !hasRoutedInitially) {
            val hasDownloadedModels = modelRepository.availableModels.value.any { it.downloadStatus is DownloadStatus.Downloaded }
            val currentRoute = navController.currentDestination?.route
            
            if (currentRoute == null || currentRoute.startsWith("onboarding")) {
                if (hasDownloadedModels) {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                } else {
                    navController.navigate(Screen.AiSetup.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            hasRoutedInitially = true
        }
    }

    // 2. Reactive Model Loading Logic: Loads AI model when config changes
    // Decoupled from navigation to avoid switching loops.
    // Progress % changes don't trigger this because we only depend on the set of Downloaded IDs.
    val downloadedModelIds = remember(models) { 
        models.filter { it.downloadStatus is DownloadStatus.Downloaded }.map { it.id }.toSet() 
    }
    
    LaunchedEffect(selectedModelId, useGpu, downloadedModelIds) {
        if (onboardingCompleted == true && downloadedModelIds.isNotEmpty()) {
            val currentModels = modelRepository.availableModels.value
            val modelToLoad = selectedModelId?.let { id -> 
                currentModels.find { it.id == id && it.id in downloadedModelIds } 
            } ?: currentModels.firstOrNull { it.id in downloadedModelIds }
            
            if (modelToLoad != null) {
                if (selectedModelId == null) {
                    preferencesRepository.setSelectedModelId(modelToLoad.id)
                }
                
                val modelFile = modelRepository.getModelFile(modelToLoad.id, modelToLoad.format)
                if (modelFile.exists() && modelFile.length() > 0) {
                    // IMPORTANT: unload previous session first to fix CalculatorGraph::Run errors
                    inferenceManager.unloadModel()
                    Log.d("DocLensApp", "Loading model: ${modelToLoad.name}")
                    val result = inferenceManager.loadModel(modelFile.absolutePath, modelToLoad.id, useGpu)
                    if (result.isFailure) {
                        Log.e("DocLensApp", "Failed to load model: ${result.exceptionOrNull()?.message}")
                    }
                } else {
                    modelRepository.refreshDownloadStatuses()
                }
            }
        }
    }

    if (onboardingCompleted == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(
        navController = navController,
        startDestination = if (onboardingCompleted == true) Screen.Home.route else Screen.OnboardingWelcome.route
    ) {
        composable(Screen.OnboardingWelcome.route) {
            WelcomeScreen(onNext = { navController.navigate(Screen.OnboardingPrivacy.route) })
        }
        composable(Screen.OnboardingPrivacy.route) {
            PrivacyScreen(onNext = { navController.navigate(Screen.OnboardingAskAnything.route) })
        }
        composable(Screen.OnboardingAskAnything.route) {
            AskAnythingScreen(
                onFinish = {
                    scope.launch {
                        preferencesRepository.setOnboardingCompleted(true)
                    }
                }
            )
        }
        composable(Screen.AiSetup.route) {
            val modelSelectionViewModel: ModelSelectionViewModel = viewModel(
                factory = ModelSelectionViewModelFactory(modelRepository, preferencesRepository, context)
            )
            ModelSelectionScreen(
                viewModel = modelSelectionViewModel,
                onModelSelected = { model ->
                    // Decide where to navigate forward
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.AiSetup.route) { inclusive = true }
                        }
                    }
                }
            )
        }
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = homeViewModel,
                onScanClick = { navController.navigate(Screen.CameraScanner.route) },
                onChatClick = { docId -> navController.navigate(Screen.Chat.createRoute(docId)) },
                onAnalysisClick = { type, docId -> navController.navigate(Screen.SpecializedAnalysis.createRoute(type, docId)) },
                onCompareClick = { docId1, docId2 -> navController.navigate(Screen.CompareDocuments.createRoute(docId1, docId2)) }
            )
        }
        composable(Screen.CameraScanner.route) {
            CameraScannerScreen(
                onImageCaptured = { path ->
                    homeViewModel.addCapturedImage(path)
                    navController.popBackStack()
                },
                onClose = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType })
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getLong("documentId") ?: -1L
            val chatViewModel: ChatViewModel = viewModel(
                factory = ChatViewModelFactory(
                    inferenceManager = inferenceManager,
                    chatRepository = chatRepository,
                    documentRepository = documentRepository,
                    ocrHelper = ocrHelper,
                    initialDocumentId = if (docId == -1L) null else docId
                )
            )
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateBack = { navController.popBackStack() },
                onSettingsClick = { navController.navigate(Screen.AiSetup.route) }
            )
        }
        composable(
            route = Screen.SpecializedAnalysis.route,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("documentId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val type = backStackEntry.arguments?.getString("type") ?: "MEDICAL"
            val docId = backStackEntry.arguments?.getLong("documentId") ?: -1L
            val specializedViewModel: SpecializedAnalysisViewModel = viewModel(
                factory = SpecializedAnalysisViewModelFactory(
                    inferenceManager = inferenceManager,
                    repository = documentRepository,
                    ocrHelper = ocrHelper,
                    type = type,
                    documentId = docId
                )
            )
            SpecializedAnalysisScreen(viewModel = specializedViewModel, onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.CompareDocuments.route,
            arguments = listOf(
                navArgument("docId1") { type = NavType.LongType },
                navArgument("docId2") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val docId1 = backStackEntry.arguments?.getLong("docId1") ?: -1L
            val docId2 = backStackEntry.arguments?.getLong("docId2") ?: -1L
            val compareViewModel: CompareDocumentsViewModel = viewModel(
                factory = CompareDocumentsViewModelFactory(
                    inferenceManager = inferenceManager,
                    repository = documentRepository,
                    ocrHelper = ocrHelper,
                    docId1 = docId1,
                    docId2 = docId2
                )
            )
            CompareDocumentsScreen(viewModel = compareViewModel, onNavigateBack = { navController.popBackStack() })
        }
    }
}
