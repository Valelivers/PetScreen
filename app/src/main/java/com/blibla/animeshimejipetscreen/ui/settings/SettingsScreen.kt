package com.blibla.animeshimejipetscreen.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blibla.animeshimejipetscreen.data.prefs.UserPrefs
import com.blibla.animeshimejipetscreen.ui.theme.White
import com.blibla.animeshimejipetscreen.ui.util.openPrivacyPolicy
import com.blibla.animeshimejipetscreen.ui.util.openRateUs
import com.blibla.animeshimejipetscreen.ui.util.shareApp
import com.blibla.animeshimejipetscreen.util.sendFeedbackEmail
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val HomeRed = Color(0xFFEF5350)
private val HomeCream = Color(0xFFF7EEDB)
private val HomePurple = Color(0xFF6C57E6)
private val CardWhite = Color(0xFFFFFFFF)

@Composable
fun SettingsScreen(
    onOpenLanguage: (() -> Unit)? = null,
    onOpenFeedback: (() -> Unit)? = null,
    onRateUs: (() -> Unit)? = null,
    onShareApp: (() -> Unit)? = null,
    onOpenPrivacyPolicy: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPrefs(context) }

    // DataStore flows
    val speedFlow by prefs.animSpeed.collectAsState(initial = 1.0f)
    val scaleFlow by prefs.animScale.collectAsState(initial = 1.0f)

    // UI state (biar slider smooth)
    var speedUi by remember { mutableFloatStateOf(1.0f) }
    var scaleUi by remember { mutableFloatStateOf(1.0f) }

    var editingSpeed by remember { mutableStateOf(false) }
    var editingScale by remember { mutableStateOf(false) }

    LaunchedEffect(speedFlow, editingSpeed) { if (!editingSpeed) speedUi = speedFlow }
    LaunchedEffect(scaleFlow, editingScale) { if (!editingScale) scaleUi = scaleFlow }

    // Debounce write saat drag
    var speedJob by remember { mutableStateOf<Job?>(null) }
    var scaleJob by remember { mutableStateOf<Job?>(null) }

    fun commitSpeed(value: Float, immediate: Boolean) {
        speedJob?.cancel()
        speedJob = scope.launch {
            if (!immediate) delay(180)
            prefs.setAnimSpeed(value)
        }
    }

    fun commitScale(value: Float, immediate: Boolean) {
        scaleJob?.cancel()
        scaleJob = scope.launch {
            if (!immediate) delay(180)
            prefs.setAnimScale(value)
        }
    }

    // ============= UI Home-Style =============
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = White
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            HomeHeaderTitle(title = "Settings")

            HomeOutlineSection(title = "Animation Control") {
                HomeSliderRow(
                    icon = Icons.Filled.Face,
                    title = "Size",
                    valueText = "${"%.2f".format(scaleUi)}x",
                    sliderValue = scaleUi,
                    valueRange = 0.5f..2.0f,
                    steps = 30,
                    onStartEditing = { editingScale = true },
                    onValueChange = { v ->
                        scaleUi = v
                        commitScale(v, immediate = false)
                    },
                    onFinishEditing = {
                        editingScale = false
                        commitScale(scaleUi, immediate = true)
                    }
                )

                HomeDivider()

                HomeSliderRow(
                    icon = Icons.Filled.PlayArrow,
                    title = "Speed",
                    valueText = "${"%.2f".format(speedUi)}x",
                    sliderValue = speedUi,
                    valueRange = 0.5f..3.0f,
                    steps = 24,
                    onStartEditing = { editingSpeed = true },
                    onValueChange = { v ->
                        speedUi = v
                        commitSpeed(v, immediate = false)
                    },
                    onFinishEditing = {
                        editingSpeed = false
                        commitSpeed(speedUi, immediate = true)
                    }
                )
            }

            HomeOutlineSection(title = "General") {
                HomeNavRow(
                    icon = Icons.Filled.Email,
                    title = "Send Feedback",
                    onClick = { sendFeedbackEmail(context) }
                )
                HomeDivider()
                HomeNavRow(
                    icon = Icons.Filled.Star,
                    title = "Rate Us",
                    onClick = { openRateUs(context) }
                )
                HomeDivider()
                HomeNavRow(
                    icon = Icons.Filled.Share,
                    title = "Share App",
                    onClick = { shareApp(context) }
                )
                HomeDivider()
                HomeNavRow(
                    icon = Icons.Filled.Info,
                    title = "Privacy Policy",
                    onClick = {
                        openPrivacyPolicy(
                            context = context,
                            url = "https://bliblametro.blogspot.com/2021/09/privacy-policy.html"
                        )
                    }
                )
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

/* ------------------------- Home Style Components ------------------------- */

@Composable
private fun HomeHeaderTitle(title: String) {
    Text(
        text = title,
        color = HomeRed,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
    )
}

@Composable
private fun HomeOutlineSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = HomeRed,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 6.dp)
        )

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            border = BorderStroke(2.dp, HomeRed),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                content = content
            )
        }
    }
}

@Composable
private fun HomeDivider() {
    Divider(
        color = HomeRed.copy(alpha = 0.35f),
        modifier = Modifier.padding(start = 54.dp, end = 14.dp)
    )
}

@Composable
private fun HomeSliderRow(
    icon: ImageVector,
    title: String,
    valueText: String,
    sliderValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onStartEditing: () -> Unit,
    onValueChange: (Float) -> Unit,
    onFinishEditing: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = HomeRed,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF6B6B6B)
            )
        }

        Spacer(Modifier.height(8.dp))

        Slider(
            modifier = Modifier.padding(start = 34.dp, end = 6.dp),
            value = sliderValue,
            onValueChange = {
                onStartEditing()
                onValueChange(it)
            },
            onValueChangeFinished = onFinishEditing,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = HomeRed,
                activeTrackColor = HomeRed,
                inactiveTrackColor = HomeRed.copy(alpha = 0.25f),
            )
        )
    }
}

@Composable
private fun HomeNavRow(
    icon: ImageVector,
    title: String,
    rightText: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = HomeRed,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )

        if (rightText != null) {
            Text(
                text = rightText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF6B6B6B)
            )
            Spacer(Modifier.width(10.dp))
        }

        Icon(
            imageVector = Icons.Filled.ArrowForward,
            contentDescription = null,
            tint = HomeRed.copy(alpha = 0.75f)
        )
    }
}
