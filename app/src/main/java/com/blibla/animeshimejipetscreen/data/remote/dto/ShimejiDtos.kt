package com.blibla.animeshimejipetscreen.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ShimejiListResponse(
    @Json(name = "base_url") val baseUrl: String,
    val count: Int,
    val data: List<ShimejiDto>
)

@JsonClass(generateAdapter = true)
data class ShimejiDto(
    val id: Long,
    val name: String,
    val status: String,
    val assets: AssetsDto
)

@JsonClass(generateAdapter = true)
data class AssetsDto(
    val icon: IconDto,
    val zip: ZipDto
)

@JsonClass(generateAdapter = true)
data class IconDto(
    val url: String,
    @Json(name = "updated_at") val updatedAt: Long,
    val ext: String? = null
)

@JsonClass(generateAdapter = true)
data class ZipDto(
    val url: String,
    @Json(name = "updated_at") val updatedAt: Long,
    @Json(name = "size_bytes") val sizeBytes: Long? = null
)
