package com.bailensn.sort.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Teal80,
    secondary = Sand80,
    tertiary = Slate80,
)

private val LightColors = lightColorScheme(
    primary = Teal40,
    secondary = Sand40,
    tertiary = Slate40,
)

@Composable
fun SortTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Android 12+ 跟随系统壁纸取色；关掉则使用上面的品牌色。
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SortTypography,
        content = content,
    )
}
