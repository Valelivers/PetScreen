package com.blibla.animeshimejipetscreen.ui.main

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blibla.animeshimejipetscreen.ads.AdsConfigRepo
import com.blibla.animeshimejipetscreen.data.local.DbProvider
import com.blibla.animeshimejipetscreen.data.prefs.UserPrefs
import com.blibla.animeshimejipetscreen.data.remote.ApiProvider
import com.blibla.animeshimejipetscreen.data.repo.ShimejiRepository
import com.blibla.animeshimejipetscreen.data.repo.UserRepository
import com.blibla.animeshimejipetscreen.overlay.ShimejiOverlayService
import com.blibla.animeshimejipetscreen.ui.ads.NativeAdCard
import com.blibla.animeshimejipetscreen.ui.shimeji.ShimejiCard
import com.blibla.animeshimejipetscreen.ui.shimeji.ShimejiGridCard
import com.blibla.animeshimejipetscreen.ui.shimeji.ShimejiViewModel
import com.blibla.animeshimejipetscreen.ui.theme.White
import androidx.compose.foundation.lazy.grid.items


private val ThemeRed = Color(0xFFEF5350)  // sesuai request kamu
private val HomeCream = Color(0xFFF7EEDB)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShimejiListScreen(
    modifier: Modifier = Modifier,
    onOpenDetail: (Long) -> Unit
) {
    val context = LocalContext.current

    val adsRepo = remember { AdsConfigRepo() }
    var nativeUnitId by remember { mutableStateOf<String?>(null) }
    var adsFreq by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        nativeUnitId = adsRepo.fetchNativeUnitId(ADS_URL)
        adsFreq = adsRepo.fetchAdsFrequency(ADS_URL) ?: 0
    }

    val adManager = remember { com.blibla.animeshimejipetscreen.ads.RewardedAdManager(context) }
    var rewardedUnitId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!com.blibla.animeshimejipetscreen.ads.isGooglePlayServicesOk(context)) return@LaunchedEffect
        rewardedUnitId = adsRepo.fetchRewardedUnitId("https://wallserver.xyz/blibla/shimeji/api/ads.php")
        rewardedUnitId?.let { adManager.load(it) }
    }


    // Manual wiring (cepat untuk MVP)
    val vm = remember {
        val db = DbProvider.get(context)
        val shimejiRepo = ShimejiRepository(ApiProvider.shimejiApi, db.shimejiDao())
        val userRepo = UserRepository(UserPrefs(context))
        ShimejiViewModel(shimejiRepo, userRepo)
    }

    val items by vm.items.collectAsState()
    val lastId by vm.lastShimejiId.collectAsState()

    // Sync sekali saat screen muncul
    LaunchedEffect(Unit) { vm.sync() }

    // Search state
    var query by rememberSaveable { mutableStateOf("") }

    val filteredItems = remember(items, query) {
        val q = query.trim()
        if (q.isEmpty()) items
        else items.filter { it.name.contains(q, ignoreCase = true) }
    }

    val gridState = rememberLazyGridState()

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // NEW badge: 24 jam
    fun isNew(firstSeenAt: Long): Boolean {
        val now = System.currentTimeMillis()
        return (now - firstSeenAt) <= 24L * 60 * 60 * 1000
    }

    // Auto scroll ke "Last" kalau ada (di list yang sudah difilter)
    LaunchedEffect(lastId, filteredItems, query) {
        val id = lastId ?: return@LaunchedEffect
        if (filteredItems.isEmpty()) return@LaunchedEffect

        val index = filteredItems.indexOfFirst { it.id == id }
        if (index >= 0) {
            gridState.animateScrollToItem(index)
        }
    }

    Column(
        modifier = modifier
            .background(White)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Shimeji",
            color = ThemeRed,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = ThemeRed) },
            placeholder = { Text("Search shimeji...") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ThemeRed,
                unfocusedBorderColor = ThemeRed.copy(alpha = 0.65f),
                focusedLeadingIconColor = ThemeRed,
                unfocusedLeadingIconColor = ThemeRed,
                cursorColor = ThemeRed
            )
        )

        val gridRows = remember(filteredItems, nativeUnitId, adsFreq, query) {
            if (query.isNotBlank()) {
                filteredItems.map { GridRow.Shimeji(it.id) }
            } else if (nativeUnitId.isNullOrBlank() || adsFreq <= 0) {
                filteredItems.map { GridRow.Shimeji(it.id) }
            } else {
                buildList<GridRow> {
                    filteredItems.forEachIndexed { i, s ->
                        add(GridRow.Shimeji(s.id))
                        val pos = i + 1
                        if (pos % adsFreq == 0) {
                            add(GridRow.Ad)
                        }
                    }
                }
            }
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                count = gridRows.size,
                key = { idx ->
                    when (val row = gridRows[idx]) {
                        is GridRow.Shimeji -> "s_${row.id}"
                        GridRow.Ad -> "ad_$idx"
                    }
                },
                span = { idx ->
                    when (gridRows[idx]) {
                        GridRow.Ad -> GridItemSpan(maxLineSpan) // span 2 kolom
                        else -> GridItemSpan(1)
                    }
                }
            ) { idx ->
                when (val row = gridRows[idx]) {
                    is GridRow.Shimeji -> {
                        val s = filteredItems.first { it.id == row.id }

                        // ✅ GANTI pemanggilan ShimejiGridCard kamu jadi ini (tinggal copas):
// Pastikan kamu sudah punya rewardedUnitId & adManager di parent (lihat catatan di bawah)

                        ShimejiGridCard(
                            item = s,
                            isLast = (s.id == lastId),
                            isNew = isNew(s.firstSeenAt),
                            rewardedUnitId = rewardedUnitId,
                            adManager = adManager,
                            onClick = {
                                vm.setLastUsed(s.id)
                                onOpenDetail(s.id)
                            },
                            onStart = {
                                // optional: kalau kamu masih pakai tombol start terpisah di card
                                vm.setLastUsed(s.id)
                                context.startService(
                                    Intent(context, ShimejiOverlayService::class.java).apply {
                                        action = ShimejiOverlayService.ACTION_SHOW
                                        putExtra(ShimejiOverlayService.EXTRA_SHIMEJI_ID, s.id)
                                        putExtra(ShimejiOverlayService.EXTRA_SHIMEJI_NAME, s.name)
                                        putExtra(ShimejiOverlayService.EXTRA_SHIMEJI_ICON, s.iconUrl)
                                    }
                                )
                            }
                        )

                    }

                    GridRow.Ad -> {
                        // pakai wrapper yang mirip ShimejiGridCard
                        NativeAdShimejiStyle(
                            adUnitId = nativeUnitId!!,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

private sealed interface GridRow {
    data class Shimeji(val id: Long) : GridRow
    data object Ad : GridRow
}

@Composable
fun NativeAdShimejiStyle(
    adUnitId: String,
    modifier: Modifier = Modifier
) {
    val ThemeRed = Color(0xFFEF5350)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(2.dp, ThemeRed),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        NativeAdCard(
            adUnitId = adUnitId,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )
    }
}

private const val ADS_URL =
    "https://wallserver.xyz/blibla/shimeji/api/ads.php"
