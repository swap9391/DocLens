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
    
    // Centralized routing and model loading logic
    LaunchedEffect(onboardingCompleted, selectedModelId, models, useGpu) {
        if (onboardingCompleted == true) {
            val downloadedModels = models.filter { it.downloadStatus is DownloadStatus.Downloaded }
            
            if (downloadedModels.isEmpty()) {
                val currentRoute = navController.currentBackStackEntry?.destination?.route
                if (currentRoute == null || currentRoute.startsWith("onboarding") || currentRoute == Screen.Home.route) {
                    navController.navigate(Screen.AiSetup.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            } else {
                val modelToLoad = selectedModelId?.let { id -> downloadedModels.find { it.id == id } } ?: downloadedModels.first()
                
                if (selectedModelId == null) {
                    scope.launch { preferencesRepository.setSelectedModelId(modelToLoad.id) }
                }
                
                val modelFile = File(context.filesDir, "${modelToLoad.id}.task")
                if (modelFile.exists() && modelFile.length() > 0) {
                    val result = inferenceManager.loadModel(modelFile.absolutePath, modelToLoad.id, useGpu)
                    if (result.isFailure) {
                        Log.e("DocLensApp", "Failed to load model: ${result.exceptionOrNull()?.message}")
                    }
                } else {
                    Log.e("DocLensApp", "Model file missing or empty despite repository status: ${modelFile.absolutePath}")
                    modelRepository.refreshDownloadStatuses()
                }
                
                val currentRoute = navController.currentBackStackEntry?.destination?.route
                if (currentRoute == null || currentRoute == Screen.OnboardingWelcome.route) {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                } else if (currentRoute == Screen.OnboardingAskAnything.route || currentRoute == Screen.AiSetup.route) {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
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
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.AiSetup.route) { inclusive = true }
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
