package com.blibla.animeshimejipetscreen.ui.shimeji

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.blibla.animeshimejipetscreen.data.local.entity.ShimejiEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ThemeRed = Color(0xFFEF5350)
private val Gold = Color(0xFFFFD700)
private val Green = Color(0xFF4CAF50)

/**
 * Rewarded unlock per item:
 * - Locked kalau belum pernah selesai rewarded untuk item ini.
 * - Setelah rewarded sukses -> unlock tersimpan (SharedPreferences).
 *
 * IMPORTANT:
 * - adManager & rewardedUnitId harus dari parent (shared instance), jangan bikin per-card.
 */
@Composable
fun ShimejiGridCard(
    item: ShimejiEntity,
    isLast: Boolean,
    isNew: Boolean,
    rewardedUnitId: String?,
    adManager: com.blibla.animeshimejipetscreen.ads.RewardedAdManager,
    onClick: () -> Unit,
    onStart: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context as? android.app.Activity }
    val scope = rememberCoroutineScope()

    // persisted unlock state
    var isUnlocked by remember(item.id) { mutableStateOf(isItemUnlocked(context, item.id)) }

    // UI state
    var showUnlockDialog by remember(item.id) { mutableStateOf(false) }
    var showLoading by remember(item.id) { mutableStateOf(false) }
    var loadingText by remember(item.id) { mutableStateOf("Preparing ad…") }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    suspend fun unlockWithRewardedAd() {
        if (!hasInternet(context)) {
            toast("Internet required to watch ad.")
            return
        }

        val act = activity
        val unitId = rewardedUnitId
        if (act == null || unitId.isNullOrBlank()) {
            toast("Ad not ready. Please try again.")
            return
        }

        // helper: setelah reward sukses
        fun onUnlocked() {
            setItemUnlocked(context, item.id, true)
            isUnlocked = true
            onClick()
        }

        // kalau sudah ready => show langsung
        if (adManager.isReady()) {
            adManager.show(
                activity = act,
                onReward = { onUnlocked() },
                onClosedOrFailed = { toast("You must finish the ad to unlock this content.") }
            )
            return
        }

        // belum ready => load + wait
        showLoading = true
        loadingText = "Loading ad…"
        adManager.load(unitId)

        val deadline = System.currentTimeMillis() + 12_000L
        while (System.currentTimeMillis() < deadline) {
            if (adManager.isReady()) break
            delay(250L)
        }
        showLoading = false

        if (!adManager.isReady()) {
            toast("Ad not available right now. Please try again.")
            return
        }

        adManager.show(
            activity = act,
            onReward = { onUnlocked() },
            onClosedOrFailed = { toast("You must finish the ad to unlock this content.") }
        )
    }

    fun handleCardClick() {
        if (isUnlocked) onClick() else showUnlockDialog = true
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = ::handleCardClick),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(2.dp, ThemeRed),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Image
            Box {
                AsyncImage(
                    model = item.iconUrl,
                    contentDescription = item.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop
                )

                if (!isUnlocked) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.25f))
                    )

                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        shape = RoundedCornerShape(999.dp),
                        color = Color.Black.copy(alpha = 0.35f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "Locked",
                                tint = Color.White
                            )
                            Text(
                                text = "Locked",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            // Name
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Chips + status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLast) MiniChip("Last")
                    if (isNew) MiniChip("New")
                }

                when {
                    !isUnlocked -> {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Gold.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Gold.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "Locked",
                                tint = Gold,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }

                    item.isReady -> {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Green.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Green.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Downloaded",
                                tint = Green,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }

                    else -> {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = ThemeRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, ThemeRed.copy(alpha = 0.35f))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Unlocked",
                                tint = ThemeRed,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ✅ Pakai overlay buatan kamu
    CoolLoadingOverlay(
        visible = showLoading,
        text = loadingText,
        onDismiss = {
            // cancel overlay (tidak membatalkan loading ad di SDK, hanya menutup UI)
            showLoading = false
        }
    )

    // Dialog unlock
    if (showUnlockDialog) {
        val online = hasInternet(context)
        AlertDialog(
            onDismissRequest = { showUnlockDialog = false },
            title = { Text("Watch Ad to Unlock") },
            text = {
                Text(
                    if (online)
                        "This content is locked. Please watch 1 rewarded ad to unlock it."
                    else
                        "Internet is required to watch the ad."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = online && !showLoading,
                    onClick = {
                        showUnlockDialog = false
                        scope.launch { unlockWithRewardedAd() }
                    }
                ) { Text("Watch") }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MiniChip(text: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(text, maxLines = 1) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = ThemeRed.copy(alpha = 0.12f),
            disabledLabelColor = ThemeRed
        ),
        border = BorderStroke(1.dp, ThemeRed.copy(alpha = 0.25f))
    )
}

private fun hasInternet(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    val hasNet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    return hasNet && validated
}

/** ===== Unlock persistence ===== */
private fun unlockPrefs(context: Context) =
    context.getSharedPreferences("shimeji_unlocks", Context.MODE_PRIVATE)

private fun isItemUnlocked(context: Context, id: Long): Boolean {
    return unlockPrefs(context).getBoolean("unlocked_$id", false)
}

private fun setItemUnlocked(context: Context, id: Long, unlocked: Boolean) {
    unlockPrefs(context).edit().putBoolean("unlocked_$id", unlocked).apply()
}

/**
 * ✅ Overlay buatan kamu (copas dari yang kamu kirim)
 */
@Composable
private fun CoolLoadingOverlay(
    visible: Boolean,
    text: String,
    onDismiss: (() -> Unit)? = null
) {
    if (!visible) return

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
                CircularProgressIndicator(
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(44.dp)
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = ThemeRed,
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
