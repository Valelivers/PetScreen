package com.blibla.animeshimejipetscreen.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

fun openPrivacyPolicy(context: Context, url: String) {
    val uri = Uri.parse(url)

    val customTabsIntent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()

    try {
        customTabsIntent.launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        // Fallback ke browser biasa kalau Custom Tabs tidak tersedia
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
