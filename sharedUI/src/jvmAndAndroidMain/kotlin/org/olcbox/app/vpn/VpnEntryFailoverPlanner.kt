package org.olcbox.app.vpn

import org.olcbox.app.data.model.VpnEntryEndpoint
import org.olcbox.app.data.model.VpnEntryFailoverConfig
import org.olcbox.app.data.model.VpnProfileConfig
import java.net.URLDecoder

/** Keeps saved profile identity intact; only temporary engine configurations change their dial target. */
internal object VpnEntryFailoverPlanner {
    fun plan(profile: VpnProfileConfig, wireGuardConfig: String? = null): VpnEntryFailoverConfig? {
        val p = profile.normalized()
        if (p.localSocksPort != null) return null
        val original = when (p.normalizedType) {
            VpnProfileConfig.TYPE_VLESS -> vlessEndpoint(p)
            VpnProfileConfig.TYPE_AMNEZIA_WG, VpnProfileConfig.TYPE_AMNEZIA_VPN ->
                wireGuardEndpoint(wireGuardConfig ?: p.rawConfig.orEmpty())
            else -> null
        } ?: return null
        // Migrate only the deployed, mirrored entry points. Imported third-party profiles stay unchanged.
        val managed = when (p.normalizedType) {
            VpnProfileConfig.TYPE_VLESS -> original.host == MANAGED_VLESS_HOST && original.port == 443 &&
                vlessQuery(p).let { it["type"]?.lowercase() in setOf("ws", "websocket") && it["security"]?.lowercase() == "tls" }
            VpnProfileConfig.TYPE_AMNEZIA_WG, VpnProfileConfig.TYPE_AMNEZIA_VPN ->
                original.host in MANAGED_WIREGUARD_HOSTS && original.port in MIRRORED_WIREGUARD_PORTS
            else -> false
        }
        p.entryFailover?.normalizedOrNull()?.let { supplied ->
            // Named managed profiles may carry metadata from before the provider changed its IP.
            // A literal manual profile or a custom pair must retain its explicitly selected route.
            if (managed && supplied.primary.host in setOf(original.host, ManagedEntryDns.PRIMARY_HOST, MANAGED_PRIMARY_IP, RETIRED_MANAGED_PRIMARY_IP) &&
                supplied.primary.port == original.port &&
                supplied.backup.host in setOf(MANAGED_BACKUP_HOST, MANAGED_BACKUP_IP) && supplied.backup.port == original.port)
                return managedPair(original.port)
            if (!original.sameDialTarget(supplied.primary)) return null
            return supplied
        }
        return if (managed) managedPair(original.port) else null
    }

    fun forEndpoint(profile: VpnProfileConfig, endpoint: VpnEntryEndpoint, wireGuardConfig: String? = null): VpnProfileConfig {
        val target = requireNotNull(endpoint.normalizedOrNull()) { "Invalid entry endpoint" }
        return when (profile.normalizedType) {
            VpnProfileConfig.TYPE_VLESS -> profile.copy(
                uri = profile.uri?.let { if (it.startsWith("vless://", true)) rewriteVless(it, target) else it },
                rawConfig = profile.rawConfig?.let { if (it.startsWith("vless://", true)) rewriteVless(it, target) else it },
                entryFailover = null
            )
            VpnProfileConfig.TYPE_AMNEZIA_WG, VpnProfileConfig.TYPE_AMNEZIA_VPN -> {
                val raw = wireGuardConfig ?: profile.rawConfig.orEmpty()
                require(wireGuardEndpoint(raw) != null) { "Failover requires one WireGuard peer endpoint" }
                profile.copy(uri = null, rawConfig = WIREGUARD_ENDPOINT.replace(raw) { match ->
                    match.groupValues[1] + target.authority() + match.groupValues[3]
                }, entryFailover = null)
            }
            else -> throw IllegalArgumentException("Entry failover is unsupported for this protocol")
        }
    }

    private fun vlessUri(profile: VpnProfileConfig): String? =
        listOfNotNull(profile.rawConfig, profile.uri).firstOrNull { it.startsWith("vless://", true) }

    private fun vlessEndpoint(profile: VpnProfileConfig): VpnEntryEndpoint? {
        val uri = vlessUri(profile) ?: return null
        val authority = uri.substringAfter("://").substringBefore('?').substringBefore('#')
        return parseEndpoint(authority.substringAfterLast('@', ""))
    }

    private fun vlessQuery(profile: VpnProfileConfig): Map<String, String> =
        vlessUri(profile)?.substringAfter('?', "")?.substringBefore('#')?.split('&')?.mapNotNull { field ->
            if ('=' !in field) null else runCatching {
                URLDecoder.decode(field.substringBefore('='), "UTF-8") to URLDecoder.decode(field.substringAfter('='), "UTF-8")
            }.getOrNull()
        }?.toMap().orEmpty()

    private fun wireGuardEndpoint(config: String): VpnEntryEndpoint? {
        if (WIREGUARD_PEER.findAll(config).count() != 1) return null
        val endpoints = WIREGUARD_ENDPOINT.findAll(config).toList()
        return endpoints.singleOrNull()?.groupValues?.get(2)?.trim()?.let(::parseEndpoint)
    }

    private fun parseEndpoint(value: String): VpnEntryEndpoint? {
        val separator = value.lastIndexOf(':')
        if (separator < 1) return null
        return VpnEntryEndpoint(value.substring(0, separator).removeSurrounding("[", "]"),
            value.substring(separator + 1).toIntOrNull() ?: return null).normalizedOrNull()
    }

    private fun rewriteVless(uri: String, target: VpnEntryEndpoint): String {
        val start = uri.indexOf("://") + 3
        val end = uri.indexOfAny(charArrayOf('?', '#'), start).takeIf { it >= 0 } ?: uri.length
        val at = uri.lastIndexOf('@', end - 1)
        require(at >= start) { "VLESS URI has no server authority" }
        return uri.substring(0, at + 1) + target.authority() + uri.substring(end)
    }

    private fun VpnEntryEndpoint.authority(): String = (if (':' in host) "[$host]" else host) + ":$port"
    private fun VpnEntryEndpoint.sameDialTarget(other: VpnEntryEndpoint) = host == other.host && port == other.port

    // Public aliases may temporarily point at the backup during an incident. Health checks must
    // still reach the actual primary, so restoration can be detected without another DNS change.
    private fun managedPair(port: Int) = VpnEntryFailoverConfig(
        VpnEntryEndpoint(ManagedEntryDns.PRIMARY_HOST, port, "RuWeb"), VpnEntryEndpoint(MANAGED_BACKUP_IP, port, "Justhost")
    )

    private const val MANAGED_VLESS_HOST = "ru-kvn.meesharr.space"
    private const val MANAGED_BACKUP_HOST = "jh-kvn.meesharr.space"
    private const val MANAGED_PRIMARY_IP = ManagedEntryDns.FALLBACK_PRIMARY_IP
    private const val RETIRED_MANAGED_PRIMARY_IP = "185.11.247.237"
    private const val MANAGED_BACKUP_IP = "48.103.218.29"
    private val MANAGED_WIREGUARD_HOSTS = setOf(MANAGED_VLESS_HOST, "awg-legacy-primary.meesharr.space")
    private val MIRRORED_WIREGUARD_PORTS = setOf(8443, 18443, 28443, 43559, 49311, 49312, 55424)
    private val WIREGUARD_PEER = Regex("""^\s*\[\s*peer\s*]\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val WIREGUARD_ENDPOINT = Regex("""^([ \t]*Endpoint[ \t]*=[ \t]*)([^\r\n]+)(\r?)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
}
