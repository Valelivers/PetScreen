package com.blibla.animeshimejipetscreen.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

fun openRateUs(context: Context) {
    val packageName = context.packageName

    // 1) Try open in Play Store app
    val marketIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$packageName")
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }

    try {
        context.startActivity(marketIntent)
        return
    } catch (_: ActivityNotFoundException) {
        // ignore -> fallback to web
    }

    // 2) Fallback: open in browser
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    context.startActivity(webIntent)
}
