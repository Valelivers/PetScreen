package com.blibla.animeshimejipetscreen.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore(name = "service_toggle_prefs")

class ServiceTogglePrefs(private val context: Context) {

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("overlay_enabled")
    }

    val enabledFlow: Flow<Boolean> =
        context.ds.data.map { it[KEY_ENABLED] ?: false }

    suspend fun setEnabled(value: Boolean) {
        context.ds.edit { prefs ->
            prefs[KEY_ENABLED] = value
        }
    }
}
