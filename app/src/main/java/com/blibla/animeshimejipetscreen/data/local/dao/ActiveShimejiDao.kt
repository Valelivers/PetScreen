package com.blibla.animeshimejipetscreen.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.blibla.animeshimejipetscreen.data.local.entity.ActiveShimejiEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActiveShimejiDao {

    @Query("SELECT * FROM active_shimeji ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<ActiveShimejiEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ActiveShimejiEntity)

    @Query("DELETE FROM active_shimeji WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM active_shimeji")
    suspend fun clear()

    @Query("SELECT * FROM active_shimeji")
    suspend fun getAllOnce(): List<ActiveShimejiEntity>

    @Query("SELECT COUNT(*) FROM active_shimeji")
    suspend fun countAll(): Int


}
