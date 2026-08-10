package com.mandro.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY_USER_ID = stringPreferencesKey("user_id")

    val userId: Flow<String?> = context.dataStore.data.map { it[KEY_USER_ID] }

    suspend fun getUserId(): String? = userId.firstOrNull()

    suspend fun saveUserId(id: String) {
        context.dataStore.edit { it[KEY_USER_ID] = id }
    }

    suspend fun clear() {
        context.dataStore.edit {
            it.remove(KEY_USER_ID)
        }
    }
}
