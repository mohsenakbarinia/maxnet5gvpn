package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.domain.model.ThemePreference

private val DarkSlateScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Color(0xFF00262E),
    primaryContainer = Color(0xFF004B59),
    onPrimaryContainer = Color(0xFFB8F5FF),
    secondary = EmeraldAccent,
    onSecondary = Color(0xFF002912),
    secondaryContainer = Color(0xFF004F25),
    onSecondaryContainer = Color(0xFFB3FFD2),
    tertiary = AmberWarning,
    error = CoralError,
    background = MidnightBackground,
    onBackground = OnMidnightText,
    surface = MidnightSurface,
    onSurface = OnMidnightText,
    surfaceVariant = MidnightSurfaceVariant,
    onSurfaceVariant = OnMidnightMuted
)

private val AmoledBlackScheme = DarkSlateScheme.copy(
    background = AmoledBackground,
    surface = AmoledSurface,
    surfaceVariant = AmoledSurfaceVariant
)

private val CleanLightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2F5FF),
    onPrimaryContainer = Color(0xFF002630),
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8F7DC),
    onSecondaryContainer = Color(0xFF002B14),
    tertiary = AmberWarning,
    error = CoralError,
    background = LightBackground,
    onBackground = OnLightText,
    surface = LightSurface,
    onSurface = OnLightText,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = OnLightMuted
)

@Composable
fun MaxNetVpnTheme(
    themePreference: ThemePreference = ThemePreference.DARK,
    languageCode: String = "en-US",
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val colorScheme = when (themePreference) {
        ThemePreference.DARK -> DarkSlateScheme
        ThemePreference.AMOLED -> AmoledBlackScheme
        ThemePreference.LIGHT -> CleanLightScheme
        ThemePreference.SYSTEM -> if (systemDark) DarkSlateScheme else CleanLightScheme
    }

    val isRtl = languageCode.startsWith("fa", ignoreCase = true)
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val currentDensity = LocalDensity.current
    val scaledDensity = Density(
        density = currentDensity.density,
        fontScale = (currentDensity.fontScale * fontScale.coerceIn(0.85f, 1.30f))
    )

    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalDensity provides scaledDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaxNetVpnTheme(
        themePreference = if (darkTheme) ThemePreference.DARK else ThemePreference.LIGHT,
        content = content
    )
}
