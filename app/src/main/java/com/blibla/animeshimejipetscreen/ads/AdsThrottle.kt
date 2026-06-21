package com.blibla.animeshimejipetscreen.ads

import android.content.Context

private const val SP = "ads_throttle"

fun markRewardedShown(context: Context) {
    context.getSharedPreferences(SP, Context.MODE_PRIVATE)
        .edit()
        .putLong("last_rewarded", System.currentTimeMillis())
        .apply()
}

fun markInterstitialShown(context: Context) {
    context.getSharedPreferences(SP, Context.MODE_PRIVATE)
        .edit()
        .putLong("last_interstitial", System.currentTimeMillis())
        .apply()
}

fun canShowInterstitialNow(
    context: Context,
    interstitialCooldownMs: Long = 30_000L, // ⬅️ 30 detik
    afterRewardedBlockMs: Long = 20_000L    // ⬅️ 20 detik
): Boolean {
    val sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    val lastInterstitial = sp.getLong("last_interstitial", 0L)
    val lastRewarded = sp.getLong("last_rewarded", 0L)

    if (now - lastInterstitial < interstitialCooldownMs) return false
    if (now - lastRewarded < afterRewardedBlockMs) return false

    return true
}
