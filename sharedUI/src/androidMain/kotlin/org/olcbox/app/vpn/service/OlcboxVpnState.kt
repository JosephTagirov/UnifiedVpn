package org.olcbox.app.vpn.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.olcbox.app.data.logging.sanitizeDiagnosticLogLine
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.vpn.OpenFluxConnectedTunnel
import org.olcbox.app.vpn.VpnStatus

internal data class VpnSessionSnapshot(
    val status: VpnStatus = VpnStatus.Disconnected,
    val connectionGeneration: Long = 0L,
    val connectedGeneration: Long = -1L,
    val activeProfileStorageId: String? = null,
    val connectedProfileStorageId: String? = null,
    val failedProfileStorageId: String? = null,
    val failedProfileGeneration: Long = -1L,
    val activeProfileName: String = "",
    val connectedAtElapsedRealtimeMs: Long? = null,
    val sentBytes: Long? = null,
    val receivedBytes: Long? = null
)

object OlcboxVpnState {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    @Volatile
    private var activeProxy: SubscriptionFetchProxy? = null

    @Volatile
    private var openFluxTunnel: OpenFluxConnectedTunnel? = null

    private val _session = MutableStateFlow(VpnSessionSnapshot())
    internal val session = _session.asStateFlow()

    fun setStatus(
        status: VpnStatus,
        proxy: SubscriptionFetchProxy? = null,
        profile: VpnProfileConfig? = null
    ) {
        activeProxy = proxy.takeIf { status is VpnStatus.Connected }
        openFluxTunnel = if (status is VpnStatus.Connected && proxy != null && profile != null) {
            OpenFluxConnectedTunnel.from(profile, proxy)
        } else null
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
    }

    internal fun connectedProxy(): SubscriptionFetchProxy? = activeProxy

    internal fun connectedOpenFluxTunnel(): OpenFluxConnectedTunnel? =
        openFluxTunnel.takeIf { _status.value is VpnStatus.Connected }

    internal fun clearConnectedProxy() {
        activeProxy = null
        openFluxTunnel = null
    }

    internal fun setSession(snapshot: VpnSessionSnapshot) {
        _session.value = snapshot
    }

    internal fun clearSession() {
        _session.value = VpnSessionSnapshot()
    }

    fun addLog(msg: String) {
        val sanitized = sanitizeDiagnosticLogLine(msg)
        Log.d(TAG, sanitized)
        _logs.update { (it + sanitized).takeLast(MAX_LOG_ENTRIES) }
    }

    private const val MAX_LOG_ENTRIES = 1_000
    private const val TAG = "OlcboxVpnService"
}
