package com.blibla.animeshimejipetscreen.ui.ads

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd

class AppOpenAdManager(
    private val application: Application
) {
    companion object {
        private const val COOLDOWN_MS = 60_000L // 1 menit (ubah sesuai mau)
        private const val FRESHNESS_MS = 3 * 60 * 60 * 1000L // 3 jam
    }

    private var adUnitId: String? = null
    private var isEnabled: Boolean = false

    private var appOpenAd: AppOpenAd? = null
    private var loadTimeMs: Long = 0L

    private var isLoading = false
    private var isShowing = false
    private var lastShownMs: Long = 0L

    fun updateConfig(enabled: Boolean, unitId: String?) {
        isEnabled = enabled && !unitId.isNullOrBlank()
        adUnitId = unitId

        if (isEnabled) {
            // preload segera setelah config siap
            fetchAd()
        } else {
            appOpenAd = null
        }
    }

    private fun isAdAvailable(): Boolean {
        val ad = appOpenAd ?: return false
        val age = SystemClock.elapsedRealtime() - loadTimeMs
        return age < FRESHNESS_MS && ad != null
    }

    private fun fetchAd() {
        if (!isEnabled) return
        val unit = adUnitId ?: return
        if (isLoading) return
        if (isAdAvailable()) return

        isLoading = true
        AppOpenAd.load(
            application,
            unit,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    isLoading = false
                    appOpenAd = ad
                    loadTimeMs = SystemClock.elapsedRealtime()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    appOpenAd = null
                }
            }
        )
    }

    /**
     * Panggil saat app masuk foreground.
     * - cooldown supaya nggak spam
     * - nggak show kalau sudah showing
     */
    fun showAdIfAvailable(activity: Activity, shouldShow: (Activity) -> Boolean = { true }) {
        if (!isEnabled) return
        if (!shouldShow(activity)) {
            fetchAd()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (isShowing) return
        if (now - lastShownMs < COOLDOWN_MS) {
            fetchAd()
            return
        }

        val ad = appOpenAd
        if (ad == null || !isAdAvailable()) {
            fetchAd()
            return
        }

        isShowing = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                lastShownMs = SystemClock.elapsedRealtime()
            }

            override fun onAdDismissedFullScreenContent() {
                isShowing = false
                appOpenAd = null
                fetchAd()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                isShowing = false
                appOpenAd = null
                fetchAd()
            }
        }

        ad.show(activity)
    }
}
