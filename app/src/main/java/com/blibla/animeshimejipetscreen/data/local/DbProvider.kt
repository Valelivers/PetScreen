package com.blibla.animeshimejipetscreen.data.local

import android.content.Context
import androidx.room.Room

object DbProvider {
    @Volatile private var INSTANCE: AppDatabase? = null

    fun get(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            val db = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "shimeji.db"
            )
                .fallbackToDestructiveMigration() // MVP: reset kalau schema berubah
                .build()
            INSTANCE = db
            db
        }
    }
}
