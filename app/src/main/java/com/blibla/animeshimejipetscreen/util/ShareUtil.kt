package com.blibla.animeshimejipetscreen.ui.util

import android.content.Context
import android.content.Intent

fun shareApp(context: Context) {
    val packageName = context.packageName
    val playStoreUrl = "https://play.google.com/store/apps/details?id=$packageName"

    val shareText = buildString {
        appendLine("Try This App: Anime Shimeji Pet 👀✨")
        appendLine(playStoreUrl)
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareText)
    }

    context.startActivity(Intent.createChooser(intent, "Share App"))
}
