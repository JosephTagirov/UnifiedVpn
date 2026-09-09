package org.olcbox.app.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import org.olcbox.app.ui.settings.AppLanguagePreference
import org.olcbox.app.ui.settings.AppThemePreference

@Composable
fun ComponentActivity.SyncAppSystemBars(themeMode: AppThemePreference) {
    val isDark = themeMode.resolve(isSystemInDarkTheme())
    SideEffect {
        val statusBarStyle = if (isDark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        val navigationBarStyle = if (isDark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = statusBarStyle,
            navigationBarStyle = navigationBarStyle
        )
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
actual fun AppTheme(
    useDynamicColor: Boolean,
    themeMode: AppThemePreference,
    language: AppLanguagePreference,
    content: @Composable () -> Unit
) {
    val systemIsDark = isSystemInDarkTheme()
    val isDark = themeMode.resolve(systemIsDark)
    val isDarkState = remember(isDark) { mutableStateOf(isDark) }
    val typography = getAppTypography()

    CompositionLocalProvider(
        LocalThemeIsDark provides isDarkState,
        LocalAppLanguagePreference provides language
    ) {
        MaterialTheme(
            colorScheme = if (isDark) OlcboxDarkColorScheme else OlcboxLightColorScheme,
            typography = typography
        ) {
            AppThemeContent(content)
        }
    }
}
