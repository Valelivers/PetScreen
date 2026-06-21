package com.blibla.animeshimejipetscreen.ui.main

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil.compose.AsyncImage
import com.blibla.animeshimejipetscreen.R
import com.blibla.animeshimejipetscreen.ads.InterstitialAdManager
import com.blibla.animeshimejipetscreen.ads.canShowInterstitialNow
import com.blibla.animeshimejipetscreen.ads.markInterstitialShown
import com.blibla.animeshimejipetscreen.ads.markRewardedShown
import com.blibla.animeshimejipetscreen.data.local.DbProvider
import com.blibla.animeshimejipetscreen.data.remote.ApiProvider
import com.blibla.animeshimejipetscreen.data.repo.ShimejiRepository
import com.blibla.animeshimejipetscreen.data.worker.DownloadShimejiWorker
import com.blibla.animeshimejipetscreen.overlay.OverlayPermission
import com.blibla.animeshimejipetscreen.overlay.ShimejiOverlayService
import com.blibla.animeshimejipetscreen.ui.shimeji.ShimejiDetailViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShimejiDetailScreen(
    id: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    // Overlay permission dialog
    var showOverlayDialog by remember { mutableStateOf(false) }

    // Repo + VM
    val repo = remember(context) {
        val db = DbProvider.get(context)
        ShimejiRepository(ApiProvider.shimejiApi, db.shimejiDao())
    }
    val vm = remember(id) { ShimejiDetailViewModel(repo, id) }
    val item by vm.item.collectAsState()

    // WorkManager progress observer (unique per id)
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val uniqueName = remember(id) { "download_shimeji_$id" }

    val workInfos by workManager
        .getWorkInfosForUniqueWorkLiveData(uniqueName)
        .observeAsState(emptyList())

    val workInfo = workInfos.firstOrNull()
    val isRunning = workInfo?.state == WorkInfo.State.RUNNING ||
            workInfo?.state == WorkInfo.State.ENQUEUED
    val progress = workInfo?.progress?.getInt("progress", 0) ?: 0

    // Ads
    val adsRepo = remember { com.blibla.animeshimejipetscreen.ads.AdsConfigRepo() }
    val rewardedManager = remember { com.blibla.animeshimejipetscreen.ads.RewardedAdManager(context) }
    var rewardedUnitId by remember { mutableStateOf<String?>(null) }

    val interstitialManager = remember { InterstitialAdManager(context) }
    var interstitialUnitId by remember { mutableStateOf<String?>(null) }

    // UI states
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showUseAdDialog by remember { mutableStateOf(false) }
    var showAdLoading by remember { mutableStateOf(false) }
    var adLoadingText by remember { mutableStateOf("Preparing ad…") }

    LaunchedEffect(Unit) {
        if (!com.blibla.animeshimejipetscreen.ads.isGooglePlayServicesOk(context)) return@LaunchedEffect

        val url = "https://wallserver.xyz/blibla/shimeji/api/ads.php"

        rewardedUnitId = adsRepo.fetchRewardedUnitId(url)
        rewardedUnitId?.let { rewardedManager.load(it) }

        interstitialUnitId = adsRepo.fetchInterstitialUnitId(url)
        interstitialUnitId?.let { interstitialManager.load(it) }
    }

    // ---------- helpers ----------
    fun startDownload(shimejiId: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadShimejiWorker>()
            .setInputData(workDataOf("id" to shimejiId))
            .build()

        workManager.enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun startOverlayService(sId: Long, sName: String, sIcon: String?) {
        val i = Intent(context, ShimejiOverlayService::class.java).apply {
            action = ShimejiOverlayService.ACTION_SHOW
            putExtra(ShimejiOverlayService.EXTRA_SHIMEJI_ID, sId)
            putExtra(ShimejiOverlayService.EXTRA_SHIMEJI_NAME, sName)
            putExtra(ShimejiOverlayService.EXTRA_SHIMEJI_ICON, sIcon)
        }
        ContextCompat.startForegroundService(context, i)
        onBack()
    }

    suspend fun useWithRewardedAd(
        sId: Long,
        sName: String,
        sIcon: String?
    ) {
        if (!hasInternet(context)) {
            snackbarHostState.showSnackbar("Internet required to watch ad before using.")
            return
        }

        val act = activity
        val unitId = rewardedUnitId
        if (act == null || unitId.isNullOrBlank()) {
            snackbarHostState.showSnackbar("Ad not ready. Please try again.")
            return
        }

        // show if ready
        if (rewardedManager.isReady()) {
            rewardedManager.show(
                activity = act,
                onReward = {
                    // ✅ penting: tandai rewarded tampil (anti-dobel interstitial)
                    markRewardedShown(context)
                    startOverlayService(sId, sName, sIcon)
                },
                onClosedOrFailed = {
                    scope.launch { snackbarHostState.showSnackbar("You must finish the ad to use this.") }
                }
            )
            return
        }

        // load + wait
        showAdLoading = true
        adLoadingText = "Loading ad…"
        rewardedManager.load(unitId)

        val deadline = System.currentTimeMillis() + 12_000L
        while (System.currentTimeMillis() < deadline) {
            if (rewardedManager.isReady()) break
            delay(250L)
        }
        showAdLoading = false

        if (!rewardedManager.isReady()) {
            snackbarHostState.showSnackbar("Ad not available right now. Please try again.")
            return
        }

        rewardedManager.show(
            activity = act,
            onReward = {
                // ✅ penting
                markRewardedShown(context)
                startOverlayService(sId, sName, sIcon)
            },
            onClosedOrFailed = {
                scope.launch { snackbarHostState.showSnackbar("You must finish the ad to use this.") }
            }
        )
    }

    fun exitWithInterstitial() {
        val act = activity
        val unitId = interstitialUnitId

        if (act == null || unitId.isNullOrBlank()) {
            onBack()
            return
        }

        if (!canShowInterstitialNow(context) || !interstitialManager.isReady()) {
            onBack()
            return
        }

        interstitialManager.show(act) {
            markInterstitialShown(context)
            onBack()
        }
    }
    // ---------- end helpers ----------

    // ✅ System back juga lewat interstitial
    BackHandler(enabled = true) {
        exitWithInterstitial()
    }

    // Overlay permission dialog
    if (showOverlayDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayDialog = false },
            title = { Text(stringResource(R.string.overlay_permission_title)) },
            text = { Text(stringResource(R.string.overlay_permission_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOverlayDialog = false
                        context.startActivity(OverlayPermission.createSettingsIntent(context))
                    }
                ) { Text(stringResource(R.string.overlay_permission_agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayDialog = false }) {
                    Text(stringResource(R.string.overlay_permission_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = { exitWithInterstitial() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->

        if (item == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val s = item!!

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // Header card
            Card(
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = s.iconUrl,
                        contentDescription = s.name,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(Modifier.width(14.dp))

                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.shimeji_id_status, s.id, s.status),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Info assets
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assets_title),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = stringResource(R.string.zip_updated_at, s.zipUpdatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val zipSize = s.zipSizeBytes
                    if (zipSize != null) {
                        Text(
                            text = stringResource(R.string.zip_size_bytes, zipSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Progress UI
            if (isRunning) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.downloading_progress, progress))
                Spacer(Modifier.height(10.dp))
            }

            // CTA button
            Button(
                onClick = {
                    // USE -> ALWAYS rewarded
                    if (s.isReady && !s.needsUpdate) {
                        if (!OverlayPermission.canDrawOverlays(context)) {
                            showOverlayDialog = true
                            return@Button
                        }
                        showUseAdDialog = true
                        return@Button
                    }

                    // DOWNLOAD / UPDATE -> FREE
                    if (!isRunning) startDownload(s.id)
                },
                enabled = !isRunning && !showAdLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                val labelRes = when {
                    isRunning -> R.string.btn_downloading
                    s.isReady && s.needsUpdate -> R.string.btn_update
                    s.isReady -> R.string.btn_use
                    else -> R.string.btn_download
                }
                Text(stringResource(labelRes))
            }

            // Optional second button for update assets (still FREE)
            if (s.needsUpdate && !isRunning) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { startDownload(s.id) },
                    enabled = !showAdLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.btn_update_assets))
                }
            }
        }

        if (showAdLoading) {
            AdLoadingOverlay(text = adLoadingText)
        }

        // Dialog: Watch ad to USE
        if (showUseAdDialog) {
            val online = hasInternet(context)

            AlertDialog(
                onDismissRequest = { showUseAdDialog = false },
                title = { Text("Watch Ad to Use") },
                text = {
                    Text(
                        if (online)
                            "To use this Shimeji, please watch 1 rewarded ad."
                        else
                            "Internet is required to watch the ad."
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = online,
                        onClick = {
                            showUseAdDialog = false
                            scope.launch {
                                useWithRewardedAd(
                                    sId = s.id,
                                    sName = s.name,
                                    sIcon = s.iconUrl
                                )
                            }
                        }
                    ) { Text("Watch") }
                },
                dismissButton = {
                    TextButton(onClick = { showUseAdDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

private fun hasInternet(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    val hasNet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    return hasNet && validated
}

@Composable
private fun AdLoadingOverlay(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Please wait a moment…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
