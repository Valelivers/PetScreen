package com.blibla.animeshimejipetscreen.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.*
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

class RewardedAdManager(private val context: Context) {
    private var rewardedAd: RewardedAd? = null
    private var loading = false
    private var unitId: String? = null

    fun load(newUnitId: String) {
        if (loading) return

        // reload only if unit id changed or ad empty
        if (rewardedAd != null && unitId == newUnitId) return

        unitId = newUnitId
        rewardedAd = null
        loading = true

        RewardedAd.load(
            context,
            newUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    loading = false
                }

                override fun onAdFailedToLoad(err: LoadAdError) {
                    rewardedAd = null
                    loading = false
                }
            }
        )
    }

    fun isReady(): Boolean = rewardedAd != null

    fun show(
        activity: Activity,
        onReward: (RewardItem) -> Unit,
        onClosedOrFailed: () -> Unit
    ) {
        val ad = rewardedAd
        if (ad == null) {
            onClosedOrFailed()
            return
        }

        var rewardEarned = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                unitId?.let { load(it) }

                // ✅ hanya panggil fallback kalau reward TIDAK didapat
                if (!rewardEarned) onClosedOrFailed()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                rewardedAd = null
                unitId?.let { load(it) }
                onClosedOrFailed()
            }
        }

        ad.show(activity) { rewardItem ->
            rewardEarned = true
            onReward(rewardItem)
        }
    }

}
