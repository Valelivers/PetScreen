package com.blibla.animeshimejipetscreen.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

class InterstitialAdManager(private val context: Context) {

    private var ad: InterstitialAd? = null
    private var isLoading = false
    private var lastUnitId: String? = null

    fun isReady(): Boolean = ad != null

    fun load(unitId: String) {
        if (unitId.isBlank()) return
        if (isLoading) return

        lastUnitId = unitId
        isLoading = true

        InterstitialAd.load(
            context,
            unitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    ad = interstitialAd
                    isLoading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    ad = null
                    isLoading = false
                }
            }
        )
    }

    /**
     * Show interstitial jika ready.
     * onFinished dipanggil setelah ad ditutup / gagal show / tidak ready.
     */
    fun show(activity: Activity, onFinished: () -> Unit) {
        val current = ad
        if (current == null) {
            onFinished()
            return
        }

        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                ad = null
                lastUnitId?.let { load(it) } // auto preload again
                onFinished()
            }

            override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                ad = null
                lastUnitId?.let { load(it) }
                onFinished()
            }

            override fun onAdShowedFullScreenContent() {
                // consumed
                ad = null
            }
        }

        current.show(activity)
    }
}
