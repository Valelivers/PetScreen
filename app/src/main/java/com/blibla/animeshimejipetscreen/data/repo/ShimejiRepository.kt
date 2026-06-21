package com.blibla.animeshimejipetscreen.data.repo

import com.blibla.animeshimejipetscreen.data.local.dao.ShimejiDao
import com.blibla.animeshimejipetscreen.data.local.entity.ShimejiEntity
import com.blibla.animeshimejipetscreen.data.remote.ShimejiApi
import kotlinx.coroutines.flow.first

class ShimejiRepository(
    private val api: ShimejiApi,
    private val dao: ShimejiDao
) {
    fun observeAll() = dao.observeAll()

    fun observeById(id: Long) = dao.observeById(id)

    suspend fun syncActive() {
        val now = System.currentTimeMillis()

        val remote = api.getShimeji() // default: active
        val current = dao.observeAll().first().associateBy { it.id }

        val mapped = remote.data.map { dto ->
            val old = current[dto.id]

            val localZipUpdatedAt = old?.localZipUpdatedAt ?: 0L
            val needsUpdate =
                localZipUpdatedAt != 0L && dto.assets.zip.updatedAt > localZipUpdatedAt

            ShimejiEntity(
                id = dto.id,
                name = dto.name,
                status = dto.status,

                iconUrl = dto.assets.icon.url,
                iconUpdatedAt = dto.assets.icon.updatedAt,

                zipUrl = dto.assets.zip.url,
                zipUpdatedAt = dto.assets.zip.updatedAt,
                zipSizeBytes = dto.assets.zip.sizeBytes,

                // local state (dipertahankan)
                localZipUpdatedAt = localZipUpdatedAt,
                isReady = old?.isReady ?: false,
                needsUpdate = needsUpdate,

                // retention: NEW jika pertama kali muncul
                firstSeenAt = old?.firstSeenAt ?: now
            )
        }

        dao.upsertAll(mapped)
    }
}
