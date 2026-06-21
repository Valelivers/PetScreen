package com.blibla.animeshimejipetscreen.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

class UserPrefs(private val context: Context) {

    companion object {
        private val KEY_LAST_SHIMEJI_ID = longPreferencesKey("last_shimeji_id")
        private val KEY_ANIM_SPEED = floatPreferencesKey("anim_speed") // default 1.0
        private val KEY_ANIM_SCALE = floatPreferencesKey("anim_scale") // default 1.0
    }

    val lastShimejiId: Flow<Long?> =
        context.dataStore.data.map { prefs -> prefs[KEY_LAST_SHIMEJI_ID] }

    suspend fun setLastShimejiId(id: Long) {
        context.dataStore.edit { prefs -> prefs[KEY_LAST_SHIMEJI_ID] = id }
    }

    // --- NEW: realtime settings ---
    val animSpeed: Flow<Float> =
        context.dataStore.data.map { prefs -> prefs[KEY_ANIM_SPEED] ?: 1.0f }

    val animScale: Flow<Float> =
        context.dataStore.data.map { prefs -> prefs[KEY_ANIM_SCALE] ?: 1.0f }

    suspend fun setAnimSpeed(v: Float) {
        context.dataStore.edit { prefs -> prefs[KEY_ANIM_SPEED] = v }
    }

    suspend fun setAnimScale(v: Float) {
        context.dataStore.edit { prefs -> prefs[KEY_ANIM_SCALE] = v }
    }
}
