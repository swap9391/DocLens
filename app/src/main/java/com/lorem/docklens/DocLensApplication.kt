package com.lorem.docklens

import android.app.Application
import android.content.Context
import com.lorem.docklens.ai.MediaPipeInferenceManager
import com.lorem.docklens.ai.ModelLoadCoordinator
import com.lorem.docklens.ai.OcrHelper
import com.lorem.docklens.data.AppDatabase
import com.lorem.docklens.data.ChatRepository
import com.lorem.docklens.data.DocumentRepository
import com.lorem.docklens.data.HuggingFaceAuth
import com.lorem.docklens.data.ModelRepository
import com.lorem.docklens.data.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manually wired dependency graph.
 *
 * These objects deliberately live on the Application rather than inside a
 * composable `remember`: an activity recreation (rotation, theme change) would
 * otherwise throw away the inference engine and re-load a multi-gigabyte model.
 */
class AppContainer(context: Context) {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database = AppDatabase.getDatabase(context)

    val documentRepository = DocumentRepository(database.documentDao())
    val chatRepository = ChatRepository(database.chatDao())
    val preferencesRepository = UserPreferencesRepository(context)

    val modelRepository = ModelRepository(
        context = context,
        installedModelDao = database.installedModelDao(),
        scope = applicationScope
    )

    val inferenceManager = MediaPipeInferenceManager(context)
    val ocrHelper = OcrHelper(context)

    val modelLoadCoordinator = ModelLoadCoordinator(
        modelRepository = modelRepository,
        preferencesRepository = preferencesRepository,
        inferenceManager = inferenceManager,
        scope = applicationScope
    )

    init {
        // Seed the token before any network call so gated repositories authenticate
        // on the very first request instead of failing with 401.
        HuggingFaceAuth.update(BuildConfig.HF_TOKEN)
        applicationScope.launch {
            preferencesRepository.huggingFaceToken.collect { token ->
                HuggingFaceAuth.update(token)
            }
        }
    }
}

class DocLensApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Convenience accessor used from composables. */
val Context.appContainer: AppContainer
    get() = (applicationContext as DocLensApplication).container

