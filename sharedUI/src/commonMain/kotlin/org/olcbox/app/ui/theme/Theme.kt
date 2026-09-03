package org.olcbox.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RippleConfiguration

internal val LocalThemeIsDark = compositionLocalOf { mutableStateOf(true) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppThemeContent(content: @Composable () -> Unit) {
    val pressFeedback = RippleConfiguration(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
    )
    CompositionLocalProvider(LocalRippleConfiguration provides pressFeedback) {
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
