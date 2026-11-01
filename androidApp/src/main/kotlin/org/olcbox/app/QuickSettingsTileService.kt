package org.olcbox.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.olcbox.app.ui.activities.VpnProfileChooserActivity
import org.olcbox.app.ui.localization.androidUiText
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState

/** Opens explicit VPN controls; tapping the tile must never silently stop a tunnel. */
@RequiresApi(Build.VERSION_CODES.N)
open class QuickSettingsTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { listening ->
            OlcboxVpnState.status.onEach(::updateTile).launchIn(listening)
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openControls) else openControls()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openControls() {
        val intent = Intent(this, VpnProfileChooserActivity::class.java).apply {
            action = OlcboxVpnActions.ACTION_OPEN_PROFILE_CHOOSER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(this, 201, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (error: RuntimeException) {
            // A vendor SystemUI failure must not kill the process hosting the VPN.
            OlcboxVpnState.addLog("Quick settings could not open VPN controls: " + error.javaClass.simpleName)
            Log.w("UnifiedVPN.Tile", "Could not open VPN controls", error)
        }
    }

    private fun updateTile(status: VpnStatus) {
        val tile = qsTile ?: return
        val label = when (status) {
            VpnStatus.Connected -> "Connected"
            VpnStatus.Connecting -> "Connecting..."
            VpnStatus.Reconnecting -> "Reconnecting..."
            VpnStatus.Stopping -> "Stopping..."
            VpnStatus.Disconnected -> "Disconnected"
            is VpnStatus.Error -> "Connection failed"
        }
        tile.state = if (status is VpnStatus.Disconnected || status is VpnStatus.Error)
            Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.qs_tile_label)
        tile.contentDescription = androidUiText("Profiles") + ". " + androidUiText(label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = androidUiText(label)
        tile.updateTile()
    }
}
