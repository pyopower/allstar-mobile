package ovh.adan.allstar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class RadioColors(
    val rx: Color, val tx: Color, val idle: Color, val star: Color, val muted: Color, val card: Color,
)

val LocalRadioColors = staticCompositionLocalOf {
    RadioColors(Color(0xFF2BD47D), Color(0xFFFF4D5E), Color(0xFF3DA9FC), Color(0xFFFFB020), Color(0xFF8A97AD), Color(0xFF141E33))
}

private val Dark = darkColorScheme(
    primary = Color(0xFF3DA9FC),
    onPrimary = Color(0xFF00213D),
    primaryContainer = Color(0xFF123A63),
    onPrimaryContainer = Color(0xFFD3E8FF),
    secondary = Color(0xFFFFB020),
    onSecondary = Color(0xFF2A1A00),
    secondaryContainer = Color(0xFF4A3300),
    onSecondaryContainer = Color(0xFFFFE0A8),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE3E8F2),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE3E8F2),
    surfaceVariant = Color(0xFF1A2540),
    onSurfaceVariant = Color(0xFFAAB6CC),
    surfaceContainer = Color(0xFF111A2D),
    surfaceContainerHigh = Color(0xFF16213A),
    surfaceContainerHighest = Color(0xFF1C2945),
    surfaceContainerLow = Color(0xFF0F1828),
    outline = Color(0xFF3A4864),
    outlineVariant = Color(0xFF26324C),
    error = Color(0xFFFF6B78),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0F65B4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E8FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF9A6200),
    secondaryContainer = Color(0xFFFFE0A8),
    onSecondaryContainer = Color(0xFF2A1A00),
    background = Color(0xFFF5F7FB),
    surface = Color(0xFFF5F7FB),
    surfaceVariant = Color(0xFFE1E7F2),
    surfaceContainer = Color(0xFFEBEFF6),
    surfaceContainerHigh = Color(0xFFE4E9F2),
    surfaceContainerHighest = Color(0xFFDDE3EE),
    surfaceContainerLow = Color(0xFFF0F3F9),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val MonoNumber = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp)

@Composable
fun AllStarTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val radio = if (dark) LocalRadioColors.current
    else RadioColors(Color(0xFF12A35A), Color(0xFFD7263D), Color(0xFF0F65B4), Color(0xFFE09400), Color(0xFF5E6B82), Color(0xFFFFFFFF))
    androidx.compose.runtime.CompositionLocalProvider(LocalRadioColors provides radio) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
    }
}
