package com.blibla.animeshimejipetscreen.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.blibla.animeshimejipetscreen.ui.home.HomeScreen
import com.blibla.animeshimejipetscreen.ui.settings.SettingsScreen
import com.blibla.animeshimejipetscreen.ui.theme.ThemeRed
import com.blibla.animeshimejipetscreen.ui.theme.White

// Routes
object Routes {

    const val SHIMEJI_HOME = "shimeji_home"
    const val SHIMEJI_LIST = "shimeji_list"
    const val SETTINGS = "settings"
    const val SHIMEJI_DETAIL = "shimeji_detail"
    const val SHIMEJI_DETAIL_ARG = "id"

    fun detailRoute(id: Long) = "$SHIMEJI_DETAIL/$id"
    val detailPattern = "$SHIMEJI_DETAIL/{$SHIMEJI_DETAIL_ARG}"
}

private data class BottomItem(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
)

@Composable
fun AppScaffold() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val adsRepo = remember { com.blibla.animeshimejipetscreen.ads.AdsConfigRepo() }
    var bannerUnitId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Gate untuk device yang GMS bermasalah
        if (!com.blibla.animeshimejipetscreen.ads.isGooglePlayServicesOk(context)) return@LaunchedEffect

        bannerUnitId = adsRepo.fetchBannerUnitId("https://wallserver.xyz/blibla/shimeji/api/ads.php")
    }

    val navController = rememberNavController()

    val bottomItems = remember {
        listOf(
            BottomItem(
                route = Routes.SHIMEJI_HOME,
                label = "Home",
                icon = { Icon(Icons.Filled.Home, contentDescription = null) }
            ),
            BottomItem(
                route = Routes.SHIMEJI_LIST,
                label = "Shimeji",
                icon = { Icon(Icons.Filled.List, contentDescription = null) }
            ),
            BottomItem(
                route = Routes.SETTINGS,
                label = "Settings",
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) }
            )
        )
    }

    // Hide bottom bar on detail screen
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute?.startsWith(Routes.SHIMEJI_DETAIL) != true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    val unitId = bannerUnitId
                    if (unitId != null) {
                        com.blibla.animeshimejipetscreen.ui.ads.AdaptiveBannerAd(
                            adUnitId = unitId,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    NavigationBar(containerColor = White) {
                        bottomItems.forEach { item ->
                            val selected = currentRoute == item.route

                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                alwaysShowLabel = true,
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .width(76.dp)
                                            .height(34.dp),
                                        contentAlignment = Alignment.Center
                                    ) { item.icon() }
                                },
                                label = { Text(item.label, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ThemeRed,
                                    selectedTextColor = ThemeRed,
                                    unselectedIconColor = Color(0xFF5A5A5A),
                                    unselectedTextColor = Color(0xFF5A5A5A),
                                    indicatorColor = Color(0xFFCFE1A8)
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SHIMEJI_HOME,
        modifier = modifier
    ) {
        composable(Routes.SHIMEJI_HOME) {
            HomeScreen(
                onOpenPicker = {
                    navController.navigate(Routes.SHIMEJI_LIST) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }

        composable(Routes.SHIMEJI_LIST) {
            ShimejiListScreen(
                onOpenDetail = { id -> navController.navigate(Routes.detailRoute(id)) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
        composable(
            route = Routes.detailPattern,
            arguments = listOf(navArgument(Routes.SHIMEJI_DETAIL_ARG) { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong(Routes.SHIMEJI_DETAIL_ARG) ?: 0L
            ShimejiDetailScreen(
                id = id,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
