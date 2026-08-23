package com.lorem.docklens.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val USE_GPU = booleanPreferencesKey("use_gpu")
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.ONBOARDING_COMPLETED] ?: false
        }

    val selectedModelId: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SELECTED_MODEL_ID]
        }
        
    val useGpu: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.USE_GPU] ?: false
        }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun setSelectedModelId(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_MODEL_ID] = modelId
        }
    }
    
    suspend fun setUseGpu(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_GPU] = enabled
        }
    }
}
