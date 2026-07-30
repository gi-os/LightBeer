package com.gios.lightbeer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gios.lightbeer.data.BeerPrefs
import com.gios.lightbeer.ui.BeerScreen
import com.gios.lightbeer.ui.theme.LightBeerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The glass is the whole screen, not a card floating inside system chrome.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // The pour is dark amber to black almost everywhere it can sit under a system
            // bar, so light (white) icons read better than dark ones by default.
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val prefs = BeerPrefs(applicationContext)
        setContent {
            LightBeerTheme {
                BeerScreen(prefs = prefs)
            }
        }
    }
}
