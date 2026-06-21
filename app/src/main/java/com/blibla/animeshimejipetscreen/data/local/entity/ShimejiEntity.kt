package com.blibla.animeshimejipetscreen.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shimeji")
data class ShimejiEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val status: String,

    val iconUrl: String,
    val iconUpdatedAt: Long,

    val zipUrl: String,
    val zipUpdatedAt: Long,
    val zipSizeBytes: Long?,

    // local state
    val localZipUpdatedAt: Long = 0L,
    val isReady: Boolean = false,
    val needsUpdate: Boolean = false,

    // retention "NEW"
    val firstSeenAt: Long

)
