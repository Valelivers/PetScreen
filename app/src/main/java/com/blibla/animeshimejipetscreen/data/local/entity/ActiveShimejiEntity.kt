package com.blibla.animeshimejipetscreen.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "active_shimeji")
data class ActiveShimejiEntity(
    @PrimaryKey val id: Long,      // sama dengan ShimejiEntity.id
    val name: String,
    val iconUrl: String,
    val startedAt: Long = System.currentTimeMillis()
)
