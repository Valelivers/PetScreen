package com.blibla.animeshimejipetscreen.data.local.dao

import androidx.room.*
import com.blibla.animeshimejipetscreen.data.local.entity.ShimejiEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShimejiDao {

    @Query("SELECT * FROM shimeji ORDER BY id DESC")
    fun observeAll(): Flow<List<ShimejiEntity>>

    @Query("SELECT * FROM shimeji WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ShimejiEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ShimejiEntity>)

    @Query("DELETE FROM shimeji")
    suspend fun clearAll()

    @Update
    suspend fun update(item: ShimejiEntity)

    @Query("SELECT * FROM shimeji WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<ShimejiEntity?>

    @Query("""
    UPDATE shimeji 
    SET 
        localZipUpdatedAt = :localUpdatedAt,
        isReady = 1,
        needsUpdate = 0
    WHERE id = :id
""")
    suspend fun markReady(
        id: Long,
        localUpdatedAt: Long
    )


}
