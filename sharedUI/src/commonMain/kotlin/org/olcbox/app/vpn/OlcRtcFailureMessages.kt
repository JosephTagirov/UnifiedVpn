package org.olcbox.app.vpn

internal fun friendlyOlcRtcFailure(message: String): String {
    val text = message.lowercase()
    return when {
        "bad record magic" in text ->
            "olcRTC client and server use incompatible record-layer versions"
        "client: handshake:" in text && "timeout" in text ->
            "olcRTC server did not answer the authenticated handshake; " +
                "make sure its peer is running and uses the same olcRTC protocol version"
        else -> message
    }
}
