package com.blibla.animeshimejipetscreen.data.remote

import com.blibla.animeshimejipetscreen.BuildConfig
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object ApiProvider {
    private val moshi = Moshi.Builder().build()

    private val okHttp: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
        if (BuildConfig.DEBUG) {
            b.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        b.build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL) // https://wallserver.xyz/blibla/shimeji/
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val shimejiApi: ShimejiApi by lazy { retrofit.create(ShimejiApi::class.java) }
}
