package org.olcbox.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle

internal val LocalThemeIsDark = compositionLocalOf { mutableStateOf(true) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppThemeContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
    }
}

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    AppTheme(useDynamicColor = false, content = content)
}

@Composable
expect fun AppTheme(
    useDynamicColor: Boolean,
    content: @Composable () -> Unit
)
