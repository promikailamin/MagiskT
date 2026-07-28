/**
 * Theme configuration for the Magisk app. Supports five modes:
 * 0 - Follow system, 1 - Light, 2 - Dark, 3 - Dynamic follow system, 4 - Dynamic light,
 * 5 - Dynamic dark. Dynamic (Material You) requires Android 12+.
 */
package pro.magisk.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import pro.magisk.core.Config

/** Singleton holding the current color mode so it can be observed across recompositions. */
object ThemeState {
    var colorMode by mutableIntStateOf(Config.colorMode)
}

/** Composable wrapper that picks the correct Material 3 color scheme based on [ThemeState.colorMode]. */
@Composable
fun MagiskTheme(
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val mode = ThemeState.colorMode
    val context = LocalContext.current

    val isDarkTheme = when (mode) {
        1 -> false
        2 -> true
        3 -> isDark
        4 -> false
        5 -> true
        else -> isDark
    }

    val useDynamicColor = mode in listOf(3, 4, 5) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when {
        useDynamicColor && isDarkTheme -> dynamicDarkColorScheme(context)
        useDynamicColor && !isDarkTheme -> dynamicLightColorScheme(context)
        isDarkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}