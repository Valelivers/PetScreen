package com.blibla.animeshimejipetscreen.data.repo

import com.blibla.animeshimejipetscreen.data.prefs.UserPrefs

class UserRepository(private val prefs: UserPrefs) {
    val lastShimejiId = prefs.lastShimejiId
    suspend fun setLastShimejiId(id: Long) = prefs.setLastShimejiId(id)
}
