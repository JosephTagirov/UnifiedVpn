package org.olcbox.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

@Composable
fun SensitiveValueVisibilityButton(
    visible: Boolean,
    onVisibilityChanged: (Boolean) -> Unit,
    valueLabel: String,
    enabled: Boolean = true
) {
    IconButton(
        onClick = { onVisibilityChanged(!visible) },
        enabled = enabled
    ) {
        Icon(
            imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            contentDescription = if (visible) "Hide $valueLabel" else "Show $valueLabel"
        )
    }
}
