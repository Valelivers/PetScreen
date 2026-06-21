package com.blibla.animeshimejipetscreen.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "service_heartbeat")

class ServiceHeartbeatPrefs(private val context: Context) {

    companion object {
        private val KEY_LAST_ALIVE = longPreferencesKey("last_alive_ms")
    }

    val lastAliveFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_ALIVE] ?: 0L
    }

    suspend fun setLastAlive(ms: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_ALIVE] = ms
        }
    }

    suspend fun reset() {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_ALIVE] = 0L
        }
    }
}
