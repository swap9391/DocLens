package com.lorem.docklens.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lorem.docklens.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val USE_GPU = booleanPreferencesKey("use_gpu")
        val HF_TOKEN = stringPreferencesKey("hugging_face_token")
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.ONBOARDING_COMPLETED] ?: false }

    val selectedModelId: Flow<String?> = context.dataStore.data
        .map { it[PreferencesKeys.SELECTED_MODEL_ID] }

    val useGpu: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.USE_GPU] ?: false }

    /**
     * Hugging Face access token used for gated repositories (Gemma, Llama, ...).
     * Falls back to the `HF_TOKEN` entry in `local.properties` so developer builds
     * work without typing it on every device.
     */
    val huggingFaceToken: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.HF_TOKEN]?.takeIf { it.isNotBlank() }
                ?: BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() }
        }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setSelectedModelId(modelId: String) {
        context.dataStore.edit { it[PreferencesKeys.SELECTED_MODEL_ID] = modelId }
    }

    suspend fun clearSelectedModelId() {
        context.dataStore.edit { it.remove(PreferencesKeys.SELECTED_MODEL_ID) }
    }

    suspend fun setUseGpu(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.USE_GPU] = enabled }
    }

    suspend fun setHuggingFaceToken(token: String?) {
        context.dataStore.edit { preferences ->
            val trimmed = token?.trim()
            if (trimmed.isNullOrEmpty()) {
                preferences.remove(PreferencesKeys.HF_TOKEN)
            } else {
                preferences[PreferencesKeys.HF_TOKEN] = trimmed
            }
        }
    }
}
