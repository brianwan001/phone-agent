package tech.handsy.phoneagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ── 深色高级感配色(默认) ──
private val DarkColors = darkColorScheme(
    primary = Color(0xFF6E8BFF),
    onPrimary = Color(0xFF0A1024),
    primaryContainer = Color(0xFF232A45),
    onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = Color(0xFFB48CFF),
    background = Color(0xFF07080D),
    onBackground = Color(0xFFEEF1F8),
    surface = Color(0xFF11141D),
    onSurface = Color(0xFFEEF1F8),
    surfaceVariant = Color(0xFF191E2B),
    onSurfaceVariant = Color(0xFF8E97A8),
    surfaceContainer = Color(0xFF12151F),
    surfaceContainerHigh = Color(0xFF171B28),
    surfaceContainerLow = Color(0xFF0D1017),
    surfaceContainerLowest = Color(0xFF0A0C12),
    outline = Color(0xFF252C3E),
    outlineVariant = Color(0xFF1C2230),
    error = Color(0xFFFF6B62),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E9FF),
    onPrimaryContainer = Color(0xFF12224E),
    secondary = Color(0xFF7C4DFF),
    background = Color(0xFFF3F4F9),
    onBackground = Color(0xFF14161F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161F),
    surfaceVariant = Color(0xFFE8EAF2),
    onSurfaceVariant = Color(0xFF5D6372),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F6FA),
    outline = Color(0xFFD8DBE4),
    outlineVariant = Color(0xFFE6E8F0),
    error = Color(0xFFE5484D),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        shapes = AppShapes,
        content = content,
    )
}
