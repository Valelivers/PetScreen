package com.blibla.animeshimejipetscreen.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.blibla.animeshimejipetscreen.data.local.dao.ActiveShimejiDao
import com.blibla.animeshimejipetscreen.data.local.dao.ShimejiDao
import com.blibla.animeshimejipetscreen.data.local.entity.ActiveShimejiEntity
import com.blibla.animeshimejipetscreen.data.local.entity.ShimejiEntity

@Database(
    entities = [
        ShimejiEntity::class,
        ActiveShimejiEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shimejiDao(): ShimejiDao
    abstract fun activeShimejiDao(): ActiveShimejiDao
}
