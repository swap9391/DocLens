package com.lorem.docklens

import android.os.Bundle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

    // Application-scoped graph: the inference engine and model registry survive
    // activity recreation, so rotating the device does not reload the model.
    val container = remember(context) { context.appContainer }

    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(container.documentRepository)
    )

    val onboardingCompleted by container.preferencesRepository.onboardingCompleted
        .collectAsState(initial = null)
    val installedModels by container.modelRepository.installedModels.collectAsStateWithLifecycle()
    val initialScanComplete by container.modelRepository.initialScanComplete.collectAsStateWithLifecycle()

    val hasModel = installedModels.isNotEmpty()

    // Routing only. Loading the selected model into the engine is owned by
    // ModelLoadCoordinator so every screen observes the same state.
    LaunchedEffect(onboardingCompleted, hasModel, initialScanComplete) {
        if (onboardingCompleted != true || !initialScanComplete) return@LaunchedEffect

        val currentRoute = navController.currentBackStackEntry?.destination?.route
        val onOnboarding = currentRoute == null || currentRoute.startsWith("onboarding")

        when {
            !hasModel && (onOnboarding || currentRoute == Screen.Home.route) ->
                navController.navigate(Screen.AiSetup.route) { popUpTo(0) { inclusive = true } }

            hasModel && onOnboarding ->
                navController.navigate(Screen.Home.route) { popUpTo(0) { inclusive = true } }
        }
    }

    if (onboardingCompleted == null || !initialScanComplete) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(
        navController = navController,
        startDestination = when {
            onboardingCompleted != true -> Screen.OnboardingWelcome.route
            !hasModel -> Screen.AiSetup.route
            else -> Screen.Home.route
        }
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
                    scope.launch { container.preferencesRepository.setOnboardingCompleted(true) }
                }
            )
        }
        composable(Screen.AiSetup.route) {
            val modelSelectionViewModel: ModelSelectionViewModel = viewModel(
                factory = ModelSelectionViewModelFactory(
                    repository = container.modelRepository,
                    preferencesRepository = container.preferencesRepository,
                    modelLoadCoordinator = container.modelLoadCoordinator,
                    context = context
                )
            )
            ModelSelectionScreen(
                viewModel = modelSelectionViewModel,
                onModelSelected = {
                    // Came from chat/home settings: go back where the user was.
                    if (!navController.popBackStack()) {
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
                onAnalysisClick = { type, docId ->
                    navController.navigate(Screen.SpecializedAnalysis.createRoute(type, docId))
                },
                onCompareClick = { docId1, docId2 ->
                    navController.navigate(Screen.CompareDocuments.createRoute(docId1, docId2))
                }
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
                    inferenceManager = container.inferenceManager,
                    chatRepository = container.chatRepository,
                    documentRepository = container.documentRepository,
                    ocrHelper = container.ocrHelper,
                    modelLoadCoordinator = container.modelLoadCoordinator,
                    initialDocumentId = if (docId == -1L) null else docId
                )
            )
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateBack = { navController.popBackStack() },
                onSettingsClick = { navController.navigate(Screen.AiSetup.route) },
                onScanClick = { navController.navigate(Screen.CameraScanner.route) }
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
                    inferenceManager = container.inferenceManager,
                    repository = container.documentRepository,
                    ocrHelper = container.ocrHelper,
                    type = type,
                    documentId = docId
                )
            )
            SpecializedAnalysisScreen(
                viewModel = specializedViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
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
                    inferenceManager = container.inferenceManager,
                    repository = container.documentRepository,
                    ocrHelper = container.ocrHelper,
                    docId1 = docId1,
                    docId2 = docId2
                )
            )
            CompareDocumentsScreen(
                viewModel = compareViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
