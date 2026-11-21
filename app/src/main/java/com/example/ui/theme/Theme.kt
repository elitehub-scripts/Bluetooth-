package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.core.models.AppThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnCyanDark,
    primaryContainer = CyanContainerDark,
    onPrimaryContainer = TextPrimaryDark,
    secondary = ElectricBlue,
    onSecondary = Color.White,
    secondaryContainer = BlueContainerDark,
    onSecondaryContainer = TextPrimaryDark,
    tertiary = AmberRelay,
    onTertiary = Color.Black,
    background = ObsidianBlack,
    onBackground = TextPrimaryDark,
    surface = DeepNavySurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = NavyCardVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = NavyBorder,
    error = CoralAlert,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = DeepCyanLight,
    onPrimary = Color.White,
    primaryContainer = CyanContainerLight,
    onPrimaryContainer = TextPrimaryLight,
    secondary = CobaltBlueLight,
    onSecondary = Color.White,
    secondaryContainer = BlueContainerLight,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = AmberRelay,
    onTertiary = Color.Black,
    background = SlateWhite,
    onBackground = TextPrimaryLight,
    surface = CrispSurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SoftBlueSurfaceLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = BorderLight,
    error = CoralAlert,
    onError = Color.White
)

val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun BluetoothGlobalShareTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDark) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
