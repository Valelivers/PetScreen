package com.blibla.animeshimejipetscreen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.blibla.animeshimejipetscreen.ui.main.AppScaffold
import com.blibla.animeshimejipetscreen.ui.theme.AnimeShimejiPetScreenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnimeShimejiPetScreenTheme {
                AppScaffold()
            }
        }
    }
}
