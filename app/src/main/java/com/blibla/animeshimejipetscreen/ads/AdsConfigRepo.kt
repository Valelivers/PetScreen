package com.blibla.animeshimejipetscreen.ads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AdsConfigRepo(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetchRewardedUnitId(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string() ?: return@withContext null

                val json = JSONObject(body)
                val admob = json.getJSONObject("admob")
                val enabled = admob.optBoolean("enabled", false)
                if (!enabled) return@withContext null

                admob.optString("rewarded_unit_id", null)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ✅ NEW
    suspend fun fetchInterstitialUnitId(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string() ?: return@withContext null

                val json = JSONObject(body)
                val admob = json.getJSONObject("admob")
                val enabled = admob.optBoolean("enabled", false)
                if (!enabled) return@withContext null

                // di server kamu pastikan field ini ada:
                // "interstitial_unit_id": "ca-app-pub-xxx/yyy"
                admob.optString("interstitial_unit_id", null)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchBannerUnitId(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string() ?: return@withContext null

                val json = JSONObject(body)
                val admob = json.getJSONObject("admob")
                val enabled = admob.optBoolean("enabled", false)
                if (!enabled) return@withContext null

                admob.optString("banner_unit_id", null)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchNativeUnitId(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string() ?: return@withContext null

                val json = JSONObject(body)
                val admob = json.getJSONObject("admob")
                if (!admob.optBoolean("enabled", false)) return@withContext null

                admob.optString("native_unit_id", null)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchAdsFrequency(url: String): Int? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string() ?: return@withContext null

                val json = JSONObject(body)
                val admob = json.getJSONObject("admob")
                if (!admob.optBoolean("enabled", false)) return@withContext null

                admob.optInt("ads_frequency", -1).takeIf { it > 0 }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchOpenAppUnitId(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string() ?: return@withContext null

                val json = JSONObject(body)
                val admob = json.getJSONObject("admob")
                if (!admob.optBoolean("enabled", false)) return@withContext null

                admob.optString("open_unit_id", null)
            }
        } catch (_: Exception) {
            null
        }
    }
}
