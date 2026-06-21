package com.blibla.animeshimejipetscreen.ui.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.blibla.animeshimejipetscreen.data.local.DbProvider
import com.blibla.animeshimejipetscreen.data.local.entity.ActiveShimejiEntity
import com.blibla.animeshimejipetscreen.data.prefs.ServiceHeartbeatPrefs
import com.blibla.animeshimejipetscreen.data.prefs.ServiceTogglePrefs
import com.blibla.animeshimejipetscreen.overlay.ShimejiOverlayService
import com.blibla.animeshimejipetscreen.ui.theme.White
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPicker: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { DbProvider.get(context) }
    val active by db.activeShimejiDao().observeAll().collectAsState(initial = emptyList())

    // Permission state (auto refresh on resume)
    val hasNotifPermission by rememberNotificationPermissionState()

    // Service switch UI state (kalau mau persist, simpan ke DataStore)
    val togglePrefs = remember { ServiceTogglePrefs(context) }
    val serviceOn by togglePrefs.enabledFlow.collectAsState(initial = false)

    val scope = rememberCoroutineScope()

    val heartbeatPrefs = remember { ServiceHeartbeatPrefs(context) }

    // Dialog state
    var showPermissionDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val slotPrefs = remember { com.blibla.animeshimejipetscreen.data.prefs.SlotPrefs(context) }
    val openSlots by slotPrefs.openSlotsFlow.collectAsState(initial = 3)

    val activity = context as? android.app.Activity
    val adManager = remember { com.blibla.animeshimejipetscreen.ads.RewardedAdManager(context) }
    val adsRepo = remember { com.blibla.animeshimejipetscreen.ads.AdsConfigRepo() }
    var rewardedUnitId by remember { mutableStateOf<String?>(null) }

    var showUnlockDialog by remember { mutableStateOf(false) }
    var unlockInProgress by remember { mutableStateOf(false) }

    var showAdLoading by remember { mutableStateOf(false) }
    var adLoadingText by remember { mutableStateOf("Preparing ad…") }

    // Slots grid
    val maxSlots = 9
    val filled = active.take(openSlots)

    val slots: List<Slot> = buildList {
        filled.forEach { add(Slot.Active(it)) }
        repeat((openSlots - filled.size).coerceAtLeast(0)) { add(Slot.Add) }
        while (size < maxSlots) add(Slot.Locked)
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    slotPrefs.resetDailyBonusIfNewDay()
                    // Ambil nilai terakhir (sekali) dari flow
                    val lastAlive = heartbeatPrefs.lastAliveFlow
                        .map { it }
                        .first()

                    val now = System.currentTimeMillis()
                    val staleMs = 15_000L // threshold 15 detik (5 detik heartbeat + buffer)

                    val isStale = lastAlive == 0L || (now - lastAlive) > staleMs
                    if (isStale) {
                        db.activeShimejiDao().clear()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var pendingEnableAfterPermission by remember { mutableStateOf(false) }

    LaunchedEffect(hasNotifPermission) {
        if (hasNotifPermission && pendingEnableAfterPermission) {
            pendingEnableAfterPermission = false

            togglePrefs.setEnabled(true)
            context.startService(
                Intent(context, ShimejiOverlayService::class.java).apply {
                    action = ShimejiOverlayService.ACTION_ENABLE
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        // kalau GMS tidak ok, jangan load ads sama sekali (menghindari error fatal di beberapa device)
        if (!isGooglePlayServicesOk(context)) return@LaunchedEffect

        rewardedUnitId = adsRepo.fetchRewardedUnitId("https://wallserver.xyz/blibla/shimeji/api/ads.php")
        rewardedUnitId?.let { adManager.load(it) }
    }

    Scaffold(
        containerColor = White,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->

        val top = inner.calculateTopPadding()
        val start = inner.calculateStartPadding(LayoutDirection.Ltr)
        val end = inner.calculateEndPadding(LayoutDirection.Ltr)
        // bottom sengaja 0.dp biar tidak muncul ruang putih
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = start, top = top, end = end, bottom = 0.dp)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            HeaderPetsOnScreen()

            Spacer(Modifier.height(14.dp))

            // ✅ Enable to begin (yang benar, tidak dobel)
            EnableToBeginCard(
                isServiceOn = serviceOn,
                hasNotifPermission = hasNotifPermission,
                onToggle = { on ->
                    if (on) {
                        if (!hasNotifPermission) {
                            pendingEnableAfterPermission = true
                            showPermissionDialog = true
                            return@EnableToBeginCard
                        }
                        scope.launch { togglePrefs.setEnabled(true) }
                        context.startService(
                            Intent(context, ShimejiOverlayService::class.java).apply {
                                action = ShimejiOverlayService.ACTION_ENABLE
                            }
                        )
                    } else {
                        scope.launch { togglePrefs.setEnabled(false) }
                        context.startService(
                            Intent(context, ShimejiOverlayService::class.java).apply {
                                action = ShimejiOverlayService.ACTION_STOP
                            }
                        )
                    }
                },
                onNeedPermission = {
                    pendingEnableAfterPermission = true
                    showPermissionDialog = true
                }
            )

            Spacer(Modifier.height(16.dp))

            // Grid 3 kolom
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                userScrollEnabled = false,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(slots) { slot ->
                    when (slot) {
                        is Slot.Active -> ActiveSlotTile(
                            item = slot.item,
                            onStop = {
                                context.startService(
                                    Intent(context, ShimejiOverlayService::class.java).apply {
                                        action = ShimejiOverlayService.ACTION_STOP_ONE
                                        putExtra(ShimejiOverlayService.EXTRA_SHIMEJI_ID, slot.item.id)
                                    }
                                )
                            }
                        )

                        Slot.Add -> AddSlotTile(onClick = onOpenPicker)
                        Slot.Locked -> LockedSlotTile(
                            onUnlock = {
                                if (openSlots >= com.blibla.animeshimejipetscreen.data.prefs.SlotPrefs.MAX_SLOTS) return@LockedSlotTile
                                showUnlockDialog = true
                            }
                        )
                    }
                }
            }

            if (showUnlockDialog) {
                AlertDialog(
                    onDismissRequest = { showUnlockDialog = false },
                    title = { Text("Unlock 1 Slot") },
                    text = { Text("Watch 1 rewarded ad to unlock 1 slot for today.") },
                    confirmButton = {
                        TextButton(
                            enabled = !unlockInProgress,
                            onClick = {
                                val unitId = rewardedUnitId
                                val act = activity

                                if (unitId == null || act == null) {
                                    scope.launch { snackbarHostState.showSnackbar("Ad config not ready yet.") }
                                    return@TextButton
                                }

                                if (!isGooglePlayServicesOk(context)) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Google Play services not available. Can't show ads on this device.")
                                    }
                                    showUnlockDialog = false
                                    return@TextButton
                                }

                                unlockInProgress = true
                                showUnlockDialog = false // tutup dialog dulu

                                scope.launch {
                                    // tampilkan loading overlay
                                    adLoadingText = "Preparing ad…"
                                    showAdLoading = true

                                    // delay kecil biar transisi UI enak + kasih waktu render overlay
                                    kotlinx.coroutines.delay(650)

                                    // kalau belum ready, coba load lalu tunggu beberapa detik
                                    if (!adManager.isReady()) {
                                        adLoadingText = "Loading ad…"
                                        adManager.load(unitId)

                                        val timeoutMs = 6_000L
                                        val stepMs = 250L
                                        val start = System.currentTimeMillis()

                                        while (!adManager.isReady() && (System.currentTimeMillis() - start) < timeoutMs) {
                                            kotlinx.coroutines.delay(stepMs)
                                        }
                                    }

                                    // kalau masih belum ready -> fail gracefully
                                    if (!adManager.isReady()) {
                                        showAdLoading = false
                                        unlockInProgress = false
                                        snackbarHostState.showSnackbar("Ad is still loading. Please try again.")
                                        return@launch
                                    }

                                    // siap show
                                    adLoadingText = "Starting…"
                                    kotlinx.coroutines.delay(350) // bonus delay kecil biar smooth
                                    showAdLoading = false

                                    adManager.show(
                                        activity = act,
                                        onReward = {
                                            scope.launch {
                                                slotPrefs.addDailyBonusSlot(1)
                                                snackbarHostState.showSnackbar("Slot unlocked for today!")
                                            }
                                        },
                                        onClosedOrFailed = {
                                            // pastikan overlay mati kalau ad gagal/ditutup
                                            showAdLoading = false
                                            unlockInProgress = false
                                        }
                                    )
                                }
                            }
                        ) { Text("Watch ad") }

                    },
                    dismissButton = {
                        TextButton(onClick = { showUnlockDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // ✅ 1 dialog saja
            if (showPermissionDialog) {
                AlertDialog(
                    onDismissRequest = { showPermissionDialog = false },
                    title = { Text("Grant Permission") },
                    text = {
                        Text(
                            "You need to grant Notification permission to use the Anime Shimeji Pets service.\n\n" +
                                    "[Settings] > [Notifications] : Please turn on permission."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showPermissionDialog = false
                                openNotificationSettings(context)
                            }
                        ) { Text("Open Settings") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPermissionDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }

        CoolLoadingOverlay(
            visible = showAdLoading,
            text = adLoadingText,
            onDismiss = {
                showAdLoading = false
                unlockInProgress = false
            }
        )

    }
}

@Composable
private fun CoolLoadingOverlay(
    visible: Boolean,
    text: String,
    onDismiss: (() -> Unit)? = null
) {
    if (!visible) return

    // Full-screen overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            modifier = Modifier
                .padding(24.dp)
                .widthIn(min = 260.dp, max = 320.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Spinner + ring
                CircularProgressIndicator(
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(44.dp)
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFEF5350),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "Please wait a moment",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                if (onDismiss != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
            }
        }
    }
}


/* ---------------- Permission helpers ---------------- */

fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    context.startActivity(intent)
}

@Composable
private fun rememberNotificationPermissionState(): State<Boolean> {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val hasPerm = remember { mutableStateOf(hasNotificationPermission(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPerm.value = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return hasPerm
}

/* ---------------- UI Components ---------------- */

@Composable
fun EnableToBeginCard(
    isServiceOn: Boolean,
    hasNotifPermission: Boolean,
    onToggle: (Boolean) -> Unit,
    onNeedPermission: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEF5350)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = Color.White
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    "Enable to begin",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                if (!hasNotifPermission) {
                    Text(
                        "Turn on Notification permission first",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Switch(
                checked = if (hasNotifPermission) isServiceOn else false,
                onCheckedChange = { wantOn ->
                    if (wantOn && !hasNotifPermission) {
                        onNeedPermission()
                    } else {
                        onToggle(wantOn)
                    }
                }
            )
        }
    }
}

private fun isGooglePlayServicesOk(context: Context): Boolean {
    val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
    return code == ConnectionResult.SUCCESS
}

private sealed class Slot {
    data class Active(val item: ActiveShimejiEntity) : Slot()
    data object Add : Slot()
    data object Locked : Slot()
}

@Composable
private fun HeaderPetsOnScreen() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Anime Shimeji Pets",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFFEF5350),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActiveSlotTile(
    item: ActiveShimejiEntity,
    onStop: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .border(2.dp, Color(0xFFEF5350), RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(10.dp)
    ) {
        IconButton(
            onClick = onStop,
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(28.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFFF4081))
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = item.iconUrl,
                contentDescription = item.name,
                modifier = Modifier.size(64.dp),
                contentScale = ContentScale.Fit
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFFEF5350))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = item.name,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AddSlotTile(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(2.dp, Color(0xFFEF5350)),
        modifier = Modifier.aspectRatio(1f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add",
                tint = Color(0xFFEF5350),
                modifier = Modifier.size(44.dp)
            )
        }
    }
}

@Composable
private fun LockedSlotTile(onUnlock: () -> Unit) {
    Surface(
        onClick = onUnlock,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF9FA8DA),
        border = BorderStroke(2.dp, Color(0xFFEF9A9A)),
        modifier = Modifier.aspectRatio(1f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                tint = Color(0xFFFFC107),
                modifier = Modifier.size(44.dp)
            )
        }
    }
}

