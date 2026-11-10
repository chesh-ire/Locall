package com.example.locall.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = VioletBright,
    onPrimary = Color(0xFF1A0F3D),
    primaryContainer = VioletDeep,
    onPrimaryContainer = Color(0xFFDCD3FF),
    secondary = Cyan,
    onSecondary = Color(0xFF00201B),
    secondaryContainer = CyanDeep,
    onSecondaryContainer = CyanBright,
    tertiary = Magenta,
    onTertiary = Color(0xFF3A0A1E),
    background = Ink,
    onBackground = InkOnSurface,
    surface = InkSurface,
    onSurface = InkOnSurface,
    surfaceVariant = InkVariant,
    onSurfaceVariant = InkOnVariant,
    surfaceContainer = InkElevated,
    surfaceContainerHigh = InkElevated,
    outline = InkOutline,
    outlineVariant = Color(0xFF2A2A3D),
    error = SignalRed,
    onError = Color(0xFF3A1620),
    errorContainer = SignalRedContainer,
    onErrorContainer = Color(0xFFFFB3BD),
)

private val LightColorScheme = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E0FF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF0E9E8C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F5EC),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFFB4345E),
    onTertiary = Color.White,
    background = Paper,
    onBackground = PaperOnSurface,
    surface = PaperSurface,
    onSurface = PaperOnSurface,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = PaperOnVariant,
    outline = PaperOutline,
    error = Color(0xFFBA1A2A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD9),
    onErrorContainer = Color(0xFF410006),
)

@Composable
fun LocaLLTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
