package dev.folha.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.folha.preferences.ThemeMode

private val Dark = darkColorScheme(
    primary = Color(0xFFB2CAA7), onPrimary = Color(0xFF172113),
    primaryContainer = Color(0xFF273D26), onPrimaryContainer = Color(0xFFD4E8CA),
    secondary = Color(0xFFBBCBB1), onSecondary = Color(0xFF1C2917),
    secondaryContainer = Color(0xFF293728), onSecondaryContainer = Color(0xFFD8E6CF),
    tertiary = Color(0xFFAFCCBD), onTertiary = Color(0xFF182C21),
    background = Color(0xFF0D100E), onBackground = Color(0xFFE3E7DF),
    surface = Color(0xFF0D100E), onSurface = Color(0xFFE3E7DF),
    surfaceVariant = Color(0xFF202720), onSurfaceVariant = Color(0xFFB0B9AD),
    surfaceContainer = Color(0xFF181E19), surfaceContainerHigh = Color(0xFF202720),
    surfaceContainerHighest = Color(0xFF29312A), outline = Color(0xFF747E70),
)
private val Light = lightColorScheme(
    primary = Color(0xFF47674D), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E8D2), onPrimaryContainer = Color(0xFF18321C),
    secondary = Color(0xFF54684D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E9DC), onSecondaryContainer = Color(0xFF24391F),
    tertiary = Color(0xFF4A6A59), onTertiary = Color.White,
    background = Color(0xFFFAF9F5), onBackground = Color(0xFF252C25),
    surface = Color(0xFFFAF9F5), onSurface = Color(0xFF252C25),
    surfaceVariant = Color(0xFFE8ECE3), onSurfaceVariant = Color(0xFF64705F),
    surfaceContainer = Color(0xFFF1F2EB), surfaceContainerHigh = Color(0xFFE8ECE3),
    surfaceContainerHighest = Color(0xFFE1E7DC), outline = Color(0xFF7C8775),
)
@Composable fun FolhaTheme(mode: ThemeMode, content: @Composable (Boolean) -> Unit) {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) Dark else Light) { content(dark) }
}
