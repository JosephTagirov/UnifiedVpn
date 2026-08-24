package org.olcbox.app.data.logging

private val connectionUriPattern = Regex(
    """(?i)\b(vless|vmess|ss|trojan|awg|wireguard|vpn)://[^\s\"'<>]+"""
)
private val bundledConfigPattern = Regex(
    """(?i)\b(unifiedvpn-friend-v1|unifiedvpn\+zlib):[^\s\"'<>]+"""
)
private val credentialUriPattern = Regex(
    """(?i)\b((?:https?|socks5h?|ssh)://)([^/\s:@]+):([^@\s/]+)@"""
)
private val secretAssignmentPattern = Regex(
    """(?i)([\"']?\b(?:password|passwd|private[_-]?key|preshared[_-]?key|secret|token|uuid)\b[\"']?\s*[:=]\s*)([\"']?)([^\s,;}&\"']+)([\"']?)"""
)

fun sanitizeDiagnosticLogLine(line: String): String {
    var sanitized = connectionUriPattern.replace(line) { match ->
        "${match.groupValues[1]}://<redacted>"
    }
    sanitized = bundledConfigPattern.replace(sanitized) { match ->
        "${match.groupValues[1]}:<redacted>"
    }
    sanitized = credentialUriPattern.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>:<redacted>@"
    }
    return secretAssignmentPattern.replace(sanitized) { match ->
        val quote = match.groupValues[2]
        val closingQuote = if (quote.isEmpty()) "" else match.groupValues[4].ifEmpty { quote }
        "${match.groupValues[1]}$quote<redacted>$closingQuote"
    }
}
