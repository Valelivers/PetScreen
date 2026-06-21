package com.blibla.animeshimejipetscreen.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

private val Context.dataStore by preferencesDataStore("slot_prefs")

class SlotPrefs(private val context: Context) {

    companion object {
        private val KEY_DAILY_BONUS = intPreferencesKey("daily_bonus_slots")
        private val KEY_BONUS_DAY = intPreferencesKey("bonus_day_yyyymmdd")

        const val BASE_SLOTS = 3
        const val MAX_SLOTS = 9

        private val ZONE = ZoneId.of("Asia/Jakarta")
    }

    private fun todayKey(): Int {
        val d = LocalDate.now(ZONE)
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth // yyyymmdd
    }

    val openSlotsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        val savedDay = prefs[KEY_BONUS_DAY] ?: 0
        val bonus = prefs[KEY_DAILY_BONUS] ?: 0
        val today = todayKey()

        val effectiveBonus = if (savedDay == today) bonus else 0
        (BASE_SLOTS + effectiveBonus).coerceIn(BASE_SLOTS, MAX_SLOTS)
    }

    suspend fun resetDailyBonusIfNewDay() {
        val today = todayKey()
        context.dataStore.edit { prefs ->
            val savedDay = prefs[KEY_BONUS_DAY] ?: 0
            if (savedDay != today) {
                prefs[KEY_BONUS_DAY] = today
                prefs[KEY_DAILY_BONUS] = 0
            }
        }
    }

    /**
     * 1 iklan = +1 slot (berlaku hari ini saja)
     */
    suspend fun addDailyBonusSlot(by: Int = 1) {
        val today = todayKey()
        context.dataStore.edit { prefs ->
            val savedDay = prefs[KEY_BONUS_DAY] ?: 0
            val oldBonus = prefs[KEY_DAILY_BONUS] ?: 0

            val bonusNow = if (savedDay == today) oldBonus else 0
            val maxBonus = MAX_SLOTS - BASE_SLOTS
            val newBonus = (bonusNow + by).coerceAtMost(maxBonus)

            prefs[KEY_BONUS_DAY] = today
            prefs[KEY_DAILY_BONUS] = newBonus
        }
    }
}
