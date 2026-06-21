package com.blibla.animeshimejipetscreen

import android.app.Application
import com.blibla.animeshimejipetscreen.ads.AdsConfigRepo
import com.google.android.gms.ads.MobileAds
import com.blibla.animeshimejipetscreen.ui.ads.AppOpenAdLifecycle
import com.blibla.animeshimejipetscreen.ui.ads.AppOpenAdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var openAdManager: AppOpenAdManager
    private lateinit var openAdLifecycle: AppOpenAdLifecycle

    override fun onCreate() {
        super.onCreate()
        MobileAds.initialize(this)

        openAdManager = AppOpenAdManager(this)
        openAdLifecycle = AppOpenAdLifecycle(this, openAdManager)
        openAdLifecycle.register()

        // ambil config dari API kamu (sekali saat app start)
        appScope.launch {
            val repo = AdsConfigRepo()
            val unitId = repo.fetchOpenAppUnitId(ADS_URL)

            // enabled sudah dicek di repo (kalau admob.enabled false => null)
            openAdManager.updateConfig(enabled = !unitId.isNullOrBlank(), unitId = unitId)
        }
    }

    companion object {
        private const val ADS_URL = "https://wallserver.xyz/blibla/shimeji/api/ads.php"
    }
}
