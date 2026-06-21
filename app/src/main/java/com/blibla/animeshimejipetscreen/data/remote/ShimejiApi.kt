package com.blibla.animeshimejipetscreen.data.remote

import com.blibla.animeshimejipetscreen.data.remote.dto.ShimejiListResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface ShimejiApi {
    @GET("api/shimeji.php")
    suspend fun getShimeji(
        @Query("active") active: Int? = null,
        @Query("id") id: Long? = null
    ): ShimejiListResponse
}
