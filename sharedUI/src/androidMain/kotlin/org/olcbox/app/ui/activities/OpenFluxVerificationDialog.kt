package org.olcbox.app.ui.activities

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.olcbox.app.ui.localization.AppText
import org.olcbox.app.ui.localization.androidUiText
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnService
import org.olcbox.app.vpn.service.OlcboxVpnState

@Composable
internal fun OpenFluxVerificationDialog() {
    val context = LocalContext.current
    val prompt by OlcboxVpnState.openFluxVerification.collectAsState()
    var openedNonce by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // Activity closure can precede IPC cleanup; never offer its one-shot action twice.
        if (result.data?.hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true &&
            OlcboxVpnState.openFluxVerification.value?.nonce == openedNonce
        ) {
            context.startService(Intent(context, OlcboxVpnService::class.java).setAction(OlcboxVpnActions.ACTION_STOP_VPN))
            Toast.makeText(context, context.androidUiText("OpenFlux verification could not open"), Toast.LENGTH_SHORT).show()
        }
    }
    val current = prompt ?: return
    if (openedNonce == current.nonce) return

    fun isCurrent(): Boolean = OlcboxVpnState.openFluxVerification.value === current
    fun cancel() {
        if (isCurrent()) {
            context.startService(Intent(context, OlcboxVpnService::class.java).setAction(OlcboxVpnActions.ACTION_STOP_VPN))
        }
    }
    AlertDialog(
        onDismissRequest = ::cancel,
        title = { AppText("OpenFlux verification required") },
        confirmButton = {
            TextButton(onClick = {
                val locked = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
                if (isCurrent() && !locked) {
                    openedNonce = current.nonce
                    runCatching {
                        launcher.launch(IntentSenderRequest.Builder(current.pendingIntent.intentSender).build())
                    }.onFailure {
                        openedNonce = null
                        Toast.makeText(context, context.androidUiText("OpenFlux verification could not open"), Toast.LENGTH_SHORT).show()
                    }
                }
            }) { AppText("Verify OpenFlux") }
        },
        dismissButton = { TextButton(onClick = ::cancel) { AppText("Cancel") } }
    )
}
