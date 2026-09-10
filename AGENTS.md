# DocLens agent guide

This file gives AI coding agents the repository-specific context needed to work productively in `DocLens` without rediscovering the app structure each time.

## Project overview
- `DocLens` is a single-module Android app (`:app`) built with Jetpack Compose + MVVM; the composition root is `app/src/main/java/com/lorem/docklens/MainActivity.kt`.
- The app is local-first: documents are copied into the app-private files directory, metadata lives in `app/src/main/java/com/lorem/docklens/data/AppDatabase.kt`, user flags live in `app/src/main/java/com/lorem/docklens/data/UserPreferencesRepository.kt`, and AI runs on-device through MediaPipe + ML Kit OCR.
- No repo README exists; this guide is based on code inspection only.

## Architecture and data flow
- Navigation uses a sealed route model in `app/src/main/java/com/lorem/docklens/ui/navigation/Screen.kt`; route params are encoded into strings rather than using typed destinations.
- Dependencies are constructed once in `AppContainer` inside `app/src/main/java/com/lorem/docklens/DocLensApplication.kt` and reached from composables via `context.appContainer`. There is no DI framework. **Do not** build repositories or the inference engine inside `remember {}`: an activity recreation would reload a multi-gigabyte model.
- `MainActivity.kt` only does routing. Deciding which model is loaded is owned by `app/src/main/java/com/lorem/docklens/ai/ModelLoadCoordinator.kt`.
- The standard flow is Composable -> ViewModel -> Repository or manager -> Flow/StateFlow -> UI; see `ui/screens/home/HomeViewModel.kt`, `ui/screens/chat/ChatViewModel.kt`, and `ui/screens/ai/ModelSelectionViewModel.kt`.
- `AppDatabase` is at version 3 with a real `MIGRATION_2_3`. Add real migrations for future schema changes; the destructive fallback is a last resort only.

## Model download, storage and loading
- `app/src/main/java/com/lorem/docklens/data/ModelRepository.kt` is the single source of truth. It merges three inputs: the persistent install registry (`InstalledModelEntity` / `InstalledModelDao`), the Hugging Face catalog, and transient WorkManager progress.
- **Installed models are persisted in Room (`installed_models`)**, not derived from the network list. This is what makes downloads survive restarts, offline use and Hugging Face outages. Register a finished download with `registerInstalledModel`.
- Files are stored in `filesDir` under a repo-scoped name (`LlmModel.storageFileName`, e.g. `litert-community_Gemma3-1B-IT__gemma3-1b-it-int4.task`) because different repos publish identical file names. Partial downloads use `<storageFileName>.part` and are resumed with an HTTP `Range` header.
- `app/src/main/java/com/lorem/docklens/data/ModelCatalog.kt` hard-codes only *repository ids*. Concrete file names and download URLs are resolved at runtime from the Hugging Face API. **Never hard-code a `resolve/main/<file>.task` URL** - that is what produced dead links and 401s.
- Gated repos (Gemma, Llama) require a Hugging Face token. It is held process-wide in `HuggingFaceAuth`, persisted in `UserPreferencesRepository.huggingFaceToken`, seeded from `BuildConfig.HF_TOKEN` (`HF_TOKEN=` in `local.properties`), and also passed through worker input data so a restarted worker still authenticates.
- Only `.task` bundles are listed and loadable; `.litertlm` is filtered out because the bundled MediaPipe runtime cannot execute it. `ModelFileValidator` rejects truncated files and HTML/JSON error pages before they reach native code.
- Downloads are enqueued as unique work named `download_<modelId>` and tagged `model_download` + `model:<modelId>`. The ViewModel re-attaches to running work through those tags, so progress survives screen re-entry and process death. Preserve that scheme.

## AI/OCR specifics
- `ai/InferenceManager.kt` is the abstraction; `ai/MediaPipeInferenceManager.kt` is the only implementation. Extend the interface rather than coupling screens to MediaPipe.
- `MediaPipeInferenceManager` uses `setPreferredBackend` (GPU with automatic CPU fallback) and the per-call `ProgressListener` overload of `generateResponseAsync`. This requires `com.google.mediapipe:tasks-genai` 0.10.29+.
- `<think>...</think>` blocks are parsed into separate reasoning events; `ChatViewModel` displays reasoning separately from the final answer.
- Screens must observe `ModelLoadCoordinator.state` (`NoModel` / `Loading` / `Ready` / `Failed`) rather than assuming a model is loaded. `ChatViewModel.canSend` gates the send button on it.
- OCR runs against local files before prompt construction; see `ai/OcrHelper.kt`, `ui/screens/chat/ChatViewModel.kt`, `ui/screens/specialized/SpecializedAnalysisViewModel.kt`, and `ui/screens/compare/CompareDocumentsViewModel.kt`.

## Project conventions to follow
- Optional document navigation uses the sentinel `-1L` instead of nullable nav args; match `ui/navigation/Screen.kt` and `MainActivity.kt` when adding routes.
- `data/DocumentEntity.kt` stores an app-private file path, not a `content://` URI. `HomeViewModel` and `CameraScannerScreen` copy inputs into internal storage first.
- ViewModels are small and explicit; each screen has a paired ViewModelFactory in the same file instead of a shared injection layer.
- UI state is exposed as StateFlow. When more than five flows are needed, combine them into intermediate slice data classes as `ModelSelectionViewModel` does, rather than using the untyped `combine(vararg)` array form.
- Some flows start work immediately in `init`; see `SpecializedAnalysisViewModel` and `CompareDocumentsViewModel`.
- Existing Compose screen files keep `@Preview` fixtures in the same file as the screen component.

## Useful developer workflows
- Gradle is wrapper-driven from the repo root:
  - `./gradlew :app:assembleDebug`
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:connectedDebugAndroidTest`
- KSP is enabled for Room and Moshi in `app/build.gradle.kts`; rebuild/sync after changing entities, DAOs, `@Database`, or generated JSON models.
- `buildConfig = true` is enabled so `BuildConfig.HF_TOKEN` exists. A Gradle sync is required after touching `app/build.gradle.kts`.
- If model setup breaks, inspect in this order: the `installed_models` table, files in `filesDir`, WorkManager state for tag `model_download`, then `ModelLoadCoordinator` logs (`ModelLoadCoordinator` / `InferenceManager` tags).
- `ai/AiModelManager.kt`, `ui/screens/ai/AiSetupScreen.kt` and `ui/screens/ai/AiSetupViewModel.kt` are deprecated prototype leftovers and are not in the navigation graph. Do not extend them.
- Current tests are scaffold examples in `app/src/test/java/com/lorem/docklens/ExampleUnitTest.kt` and `app/src/androidTest/java/com/lorem/docklens/ExampleInstrumentedTest.kt`.

## Key reference files
- `app/src/main/java/com/lorem/docklens/DocLensApplication.kt`
- `app/src/main/java/com/lorem/docklens/MainActivity.kt`
- `app/src/main/java/com/lorem/docklens/ai/ModelLoadCoordinator.kt`
- `app/src/main/java/com/lorem/docklens/ai/MediaPipeInferenceManager.kt`
- `app/src/main/java/com/lorem/docklens/data/ModelRepository.kt`
- `app/src/main/java/com/lorem/docklens/data/HuggingFaceApi.kt`
- `app/src/main/java/com/lorem/docklens/data/ModelDownloadWorker.kt`
- `app/src/main/java/com/lorem/docklens/data/InstalledModelEntity.kt`
- `app/src/main/java/com/lorem/docklens/ui/screens/ai/ModelSelectionViewModel.kt`
- `app/src/main/java/com/lorem/docklens/ui/screens/chat/ChatViewModel.kt`
