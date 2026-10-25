package com.bailensn.sort.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = SurfaceLight,
    primaryContainer = TealContainer,
    onPrimaryContainer = TealDeep,
    background = CanvasLight,
    onBackground = Ink,
    surface = SurfaceLight,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7EDE7),
    onSurfaceVariant = InkMuted,
    outline = Color(0xFFC2CCC2),
    outlineVariant = Hairline,
    tertiary = Amber,
    onTertiary = SurfaceLight,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = Color(0xFF3B2308),
    error = ErrorRed,
    onError = SurfaceLight,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = TealDeepDark,
    primaryContainer = TealDeep,
    onPrimaryContainer = TealDark,
    background = CanvasDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = Color(0xFF232824),
    onSurfaceVariant = InkMutedDark,
    outline = Color(0xFF4A524B),
    outlineVariant = HairlineDark,
    tertiary = AmberDark,
    onTertiary = Color(0xFF2A1804),
    tertiaryContainer = Color(0xFF4A3212),
    onTertiaryContainer = AmberContainer,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * Expressive 的圆角尺度：比经典 M3 更圆一档，但保持同心
 * （外层 16 配内层 8，差一个 8dp padding）。
 */
private val SortShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SortTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // material3 1.4.0 把 MaterialExpressiveTheme / MotionScheme 标成了 internal，
    // 用不了；先只取 Expressive 的形状尺度（更圆一档），配色和字体不变。
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SortTypography,
        shapes = SortShapes,
        content = content,
    )
}
