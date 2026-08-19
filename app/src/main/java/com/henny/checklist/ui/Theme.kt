package com.henny.checklist.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF2E7D6F)
private val TealDark = Color(0xFF1F5A50)
private val Apricot = Color(0xFFE07A3E)
private val Indigo = Color(0xFF5A67B8)
private val Cream = Color(0xFFF7F3EE)
private val Ink = Color(0xFF1E2321)

/** 작업자별로 화면 색을 다르게 줘서 "내 앱"처럼 느끼게 한다. */
val WorkerColors = listOf(
    Color(0xFF2E7D6F),
    Color(0xFF5A67B8),
    Color(0xFFC2603E),
    Color(0xFF7A5AA8)
)

fun workerColor(index: Int): Color = WorkerColors[((index % WorkerColors.size) + WorkerColors.size) % WorkerColors.size]

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE9E1),
    onPrimaryContainer = TealDark,
    secondary = Apricot,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFAE0CE),
    onSecondaryContainer = Color(0xFF7A3B14),
    tertiary = Indigo,
    background = Cream,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDE7E0),
    onSurfaceVariant = Color(0xFF5A5651),
    outline = Color(0xFFC7C0B8),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7ED0BF),
    onPrimary = Color(0xFF07322B),
    primaryContainer = Color(0xFF1F5A50),
    onPrimaryContainer = Color(0xFFCDE9E1),
    secondary = Color(0xFFF0A672),
    onSecondary = Color(0xFF4A1F04),
    tertiary = Color(0xFFA9B2EC),
    background = Color(0xFF14171A),
    onBackground = Color(0xFFE8E5E1),
    surface = Color(0xFF1E2226),
    onSurface = Color(0xFFE8E5E1),
    surfaceVariant = Color(0xFF2C3136),
    onSurfaceVariant = Color(0xFFC3BFBA),
    outline = Color(0xFF565B5F)
)

private val HennyTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp)
)

@Composable
fun HennyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = HennyTypography,
        content = content
    )
}
