package org.olcbox.app.vpn.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.olcbox.app.data.logging.sanitizeDiagnosticLogLine
import org.olcbox.app.vpn.VpnStatus

object OlcboxVpnState {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
    }

    fun addLog(msg: String) {
        val sanitized = sanitizeDiagnosticLogLine(msg)
        Log.d(TAG, sanitized)
        _logs.update { (it + sanitized).takeLast(MAX_LOG_ENTRIES) }
    }

    private const val MAX_LOG_ENTRIES = 1_000
    private const val TAG = "OlcboxVpnService"
}
