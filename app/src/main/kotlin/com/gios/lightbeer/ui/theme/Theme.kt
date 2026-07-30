package com.gios.lightbeer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Every other Light Phone III tool here is greyscale on purpose, to match LightOS chrome.
 * This one isn't — a monochrome pint of beer looks like a barium X-ray, and the whole
 * premise is a full-colour pour you tilt to drink. Colour is the joke, so it's the one
 * deliberate break from house style. See ui/BeerScreen.kt for where it's actually spent.
 */
val BeerAmberLight = Color(0xFFF6B93B)
val BeerAmberDark = Color(0xFFB9720C)
val BeerFoam = Color(0xFFFFF7E3)
val BeerFoamShadow = Color(0xFFE0C88F)
val BeerGlassLine = Color(0xFFCFCFCF)
val BeerHint = Color(0xFF8A8A8A)
val BeerCounter = Color(0xFFBBBBBB)

private val BeerColors = darkColorScheme(
    primary = BeerAmberLight, onPrimary = Color.Black,
    background = Color.Black, onBackground = Color(0xFFEDEDED),
    surface = Color.Black, onSurface = Color(0xFFEDEDED),
)

@Composable
fun LightBeerTheme(content: @Composable () -> Unit) {
    val fam = remember { akkuratFamilyOrDefault() }
    val type = Typography(
        titleLarge = TextStyle(
            fontFamily = fam, fontSize = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = fam, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp,
        ),
        bodyMedium = TextStyle(fontFamily = fam, fontSize = 13.sp, fontWeight = FontWeight.Normal),
    )
    MaterialTheme(colorScheme = BeerColors, typography = type, content = content)
}
