package org.olcbox.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SheetValue
import org.olcbox.app.ui.localization.AppText as Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.versionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationUpdateOfferSheet(
    info: AppUpdateInfo,
    downloadProgress: Float?,
    onLater: () -> Unit,
    onDownload: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val isDownloading by rememberUpdatedState(downloadProgress != null)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> !isDownloading || value != SheetValue.Hidden }
    )

    ModalBottomSheet(
        onDismissRequest = { if (!isDownloading) onLater() },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        Text("↑", fontSize = 30.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Update UnifiedVPN",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Version ${info.versionLabel()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Your settings and subscriptions will be kept",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = info.asset.sizeBytes?.formatBytes() ?: "Size unknown",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    if (downloadProgress != null) {
                        Text("${(downloadProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Text("The download is verified before installation. UnifiedVPN may briefly close to apply the update.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick={runCatching {uriHandler.openUri(info.htmlUrl)}}) { Text("Что нового ↗") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onLater,
                    modifier = Modifier.weight(1f),
                    enabled = downloadProgress == null
                ) {
                    Text("Later")
                }
                Button(
                    onClick = onDownload,
                    modifier = Modifier.weight(1f),
                    enabled = downloadProgress == null
                ) {
                    Text(if (downloadProgress == null) "Download and install" else "Downloading…")
                }
            }
        }
    }
}

private fun Long.formatBytes(): String {
    val mb = this.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        "${(mb * 10).toInt() / 10.0} MB"
    } else {
        "${this / 1024L} KB"
    }
}
