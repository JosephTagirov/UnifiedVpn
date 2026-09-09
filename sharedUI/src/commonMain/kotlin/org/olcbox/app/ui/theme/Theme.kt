package org.olcbox.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import org.olcbox.app.ui.settings.AppLanguagePreference
import org.olcbox.app.ui.settings.AppThemePreference

internal val LocalThemeIsDark = compositionLocalOf { mutableStateOf(true) }
internal val LocalAppLanguagePreference = compositionLocalOf { AppLanguagePreference.System }

@Composable
internal fun AppThemeContent(content: @Composable () -> Unit) {
    ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
}

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    AppTheme(
        useDynamicColor = false,
        themeMode = AppThemePreference.System,
        language = AppLanguagePreference.System,
        content = content
    )
}

@Composable
expect fun AppTheme(
    useDynamicColor: Boolean,
    themeMode: AppThemePreference = AppThemePreference.System,
    language: AppLanguagePreference = AppLanguagePreference.System,
    content: @Composable () -> Unit
)
