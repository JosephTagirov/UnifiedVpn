package org.olcbox.app.data.model

import kotlinx.serialization.Serializable

/** Dial targets only. Authentication and verified TLS identity stay in the profile. */
@Serializable
data class VpnEntryEndpoint(
    val host: String,
    val port: Int,
    val name: String? = null
) {
    fun normalizedOrNull(): VpnEntryEndpoint? {
        if (host.any { it.code < 32 || it.code == 127 }) return null
        val address = host.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()
        if (port !in 1..65535 || address.isEmpty() || address.length > 253) return null
        if (address.any { it.isWhitespace() || it.code < 33 || it in "/\\?#@'\"" }) return null
        val validHost = if (':' in address) {
            address.count { it == ':' } >= 2 && ":::" !in address &&
                address.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }
        } else {
            address.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            }
        }
        if (!validHost) return null
        val displayName = name?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 && it.none { c -> c.code < 32 } }
        return copy(host = address, name = displayName)
    }
}

/** An ordered primary/backup pair, rather than a latency-based load balancing pool. */
@Serializable
data class VpnEntryFailoverConfig(
    val primary: VpnEntryEndpoint,
    val backup: VpnEntryEndpoint
) {
    fun normalizedOrNull(): VpnEntryFailoverConfig? {
        val first = primary.normalizedOrNull() ?: return null
        val second = backup.normalizedOrNull() ?: return null
        if (first.host == second.host && first.port == second.port) return null
        return copy(primary = first, backup = second)
    }
}
