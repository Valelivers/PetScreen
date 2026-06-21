package com.blibla.animeshimejipetscreen.ui.ads

import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.blibla.animeshimejipetscreen.R
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import android.content.res.ColorStateList
import androidx.core.view.ViewCompat

private val nativeAdCache = mutableMapOf<String, NativeAd>()

@Composable
fun NativeAdCard(adUnitId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var nativeAd by remember { mutableStateOf(nativeAdCache[adUnitId]) }

    DisposableEffect(adUnitId) {
        if (nativeAd == null) {
            val loader = AdLoader.Builder(context, adUnitId)
                .forNativeAd { ad ->
                    nativeAdCache[adUnitId]?.destroy()
                    nativeAdCache[adUnitId] = ad
                    nativeAd = ad
                }
                .withNativeAdOptions(NativeAdOptions.Builder().build())
                .build()

            loader.loadAd(AdRequest.Builder().build())
        }
        onDispose { /* keep cache */ }
    }

    val ad = nativeAd ?: return

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            LayoutInflater.from(ctx)
                .inflate(R.layout.view_native_ad, android.widget.FrameLayout(ctx), false)
        },
        update = { view ->
            val adView = view as NativeAdView

            val headline = adView.findViewById<TextView>(R.id.ad_headline)
            val body = adView.findViewById<TextView>(R.id.ad_body)
            val cta = adView.findViewById<Button>(R.id.ad_cta)
            val icon = adView.findViewById<ImageView>(R.id.ad_icon)
            val media = adView.findViewById<com.google.android.gms.ads.nativead.MediaView>(R.id.ad_media)

            adView.headlineView = headline
            adView.bodyView = body
            adView.callToActionView = cta
            adView.iconView = icon
            adView.mediaView = media

            headline.text = ad.headline ?: ""

            val bodyText = ad.body
            body.visibility = if (bodyText.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            body.text = bodyText ?: ""

            val ctaText = ad.callToAction
            if (ctaText.isNullOrBlank()) {
                cta.visibility = android.view.View.GONE
            } else {
                cta.visibility = android.view.View.VISIBLE
                cta.text = ctaText

                // 🎨 CTA merah ThemeRed (mirip ShimejiCard)
                val themeRed = android.graphics.Color.parseColor("#EF5350")
                ViewCompat.setBackgroundTintList(cta, ColorStateList.valueOf(themeRed))
                cta.setTextColor(android.graphics.Color.WHITE)

                // pill feel
                cta.background = cta.background?.mutate()
                cta.setPadding(36, 14, 36, 14)
            }


            val iconDrawable = ad.icon?.drawable
            icon.visibility = if (iconDrawable == null) android.view.View.GONE else android.view.View.VISIBLE
            if (iconDrawable != null) icon.setImageDrawable(iconDrawable)

            adView.setNativeAd(ad)
        }
    )
}
