package org.olcbox.app.data.logging

private val connectionUriPattern = Regex(
    """(?i)\b(vless|vmess|ss|trojan|awg|wireguard|vpn|olcrtc)://[^\s\"'<>]+"""
)
private val bundledConfigPattern = Regex(
    """(?i)\b(unifiedvpn-friend-v\d+|unifiedvpn\+zlib):[^\s\"'<>]+"""
)
private val credentialUriPattern = Regex(
    """(?i)\b((?:https?|socks5h?|ssh)://)([^/\s:@]+):([^@\s/]+)@"""
)
private const val SECRET_FIELD_NAME_PATTERN =
    """(?:password|passwd|passphrase|private[_-]?key|preshared[_-]?key|secret|token|uuid|id|identifier|hwid|(?:client|user|device)[_-]?id|api[_-]?key|access[_-]?token|refresh[_-]?token|subscription(?:[_-]?(?:url|uri|link|token|secret))?)"""
private val doubleQuotedSecretAssignmentPattern = Regex(
    """(?i)([\"']?\b$SECRET_FIELD_NAME_PATTERN\b[\"']?\s*[:=]\s*)(\"(?:\\[\s\S]|[^\"\\])*\")"""
)
private val singleQuotedSecretAssignmentPattern = Regex(
    """(?i)([\"']?\b$SECRET_FIELD_NAME_PATTERN\b[\"']?\s*[:=]\s*)('(?:\\[\s\S]|[^'\\])*')"""
)
private val unquotedSecretAssignmentPattern = Regex(
    """(?i)([\"']?\b$SECRET_FIELD_NAME_PATTERN\b[\"']?\s*[:=]\s*)(?![\"']|<redacted>)([^\s,;}\]\)>\"']+)"""
)
private val unterminatedDoubleQuotedSecretAssignmentPattern = Regex(
    """(?i)([\"']?\b$SECRET_FIELD_NAME_PATTERN\b[\"']?\s*[:=]\s*)\"(?:\\[\s\S]|[^\"\\])*$"""
)
private val unterminatedSingleQuotedSecretAssignmentPattern = Regex(
    """(?i)([\"']?\b$SECRET_FIELD_NAME_PATTERN\b[\"']?\s*[:=]\s*)'(?:\\[\s\S]|[^'\\])*$"""
)
private val subscriptionLinkPattern = Regex(
    """(?i)(\bsubscription(?:[_ -]?(?:url|uri|link))?\b\s*[:=]?\s*)(https?://[^\s\"'<>]+)"""
)
private val olcRtcContextPattern = Regex(
    """(?i)\b(?:olc\s*rtc|jitsi|muc|room)\b"""
)
private val absoluteRoomReferencePattern = Regex(
    """(?i)\bhttps?://([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::\d+)?)(/[^\s\"'<>]+)"""
)
private val bareRoomReferencePattern = Regex(
    """(?i)\b([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}(?::\d+)?)(/[^\s\"'<>]+)"""
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
    sanitized = doubleQuotedSecretAssignmentPattern.replace(sanitized) { match ->
        "${match.groupValues[1]}\"<redacted>\""
    }
    sanitized = singleQuotedSecretAssignmentPattern.replace(sanitized) { match ->
        "${match.groupValues[1]}'<redacted>'"
    }
    sanitized = unquotedSecretAssignmentPattern.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>"
    }
    sanitized = unterminatedDoubleQuotedSecretAssignmentPattern.replace(sanitized) { match ->
        "${match.groupValues[1]}\"<redacted>\""
    }
    sanitized = unterminatedSingleQuotedSecretAssignmentPattern.replace(sanitized) { match ->
        "${match.groupValues[1]}'<redacted>'"
    }
    sanitized = subscriptionLinkPattern.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>"
    }
    return redactOlcRtcRoomReferences(sanitized)
}

internal fun diagnosticOlcRtcRoomReference(room: String): String {
    val canonicalRoom = canonicalOlcRtcRoom(room)
    return "host=${roomHost(canonicalRoom)}, roomId=${stableDiagnosticId(canonicalRoom)}"
}

internal fun sanitizeOlcRtcDiagnosticOutput(message: String, room: String): String {
    val replacement = diagnosticOlcRtcRoomReference(room)
    var sanitized = message
    roomVariants(room)
        .sortedByDescending(String::length)
        .forEach { roomVariant ->
            sanitized = sanitized.replace(roomVariant, replacement, ignoreCase = true)
        }
    return sanitizeDiagnosticLogLine(sanitized)
}

private fun redactOlcRtcRoomReferences(value: String): String {
    var sanitized = absoluteRoomReferencePattern.replace(value) { match ->
        if (hasOlcRtcContext(value, match.range.first)) {
            diagnosticOlcRtcRoomReference(match.value)
        } else {
            match.value
        }
    }
    sanitized = bareRoomReferencePattern.replace(sanitized) { match ->
        if (hasOlcRtcContext(sanitized, match.range.first)) {
            diagnosticOlcRtcRoomReference(match.value)
        } else {
            match.value
        }
    }
    return sanitized
}

private fun hasOlcRtcContext(value: String, matchIndex: Int): Boolean {
    val lineStart = if (matchIndex <= 0) {
        0
    } else {
        value.lastIndexOf('\n', matchIndex - 1).let { index -> if (index < 0) 0 else index + 1 }
    }
    val lineEnd = value.indexOf('\n', matchIndex).let { index -> if (index < 0) value.length else index }
    return olcRtcContextPattern.containsMatchIn(value.substring(lineStart, lineEnd))
}

private fun canonicalOlcRtcRoom(room: String): String {
    val trimmed = room.trim()
    val withoutScheme = trimmed.substringAfter("://", trimmed)
    val host = withoutScheme.substringBefore('/').lowercase()
    val suffix = withoutScheme.substringAfter('/', "")
    return if (suffix.isEmpty()) host else "$host/$suffix"
}

private fun roomHost(canonicalRoom: String): String {
    val candidate = canonicalRoom.substringBefore('/')
    return candidate.takeIf {
        it.matches(Regex("""(?i)(?:[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?|\[[a-f0-9:]+])(?::\d+)?"""))
    } ?: "<unknown>"
}

private fun roomVariants(room: String): Set<String> {
    val trimmed = room.trim()
    if (trimmed.isEmpty()) return emptySet()
    val withoutScheme = trimmed.substringAfter("://", trimmed)
    val path = withoutScheme.substringAfter('/', "")
    return buildSet {
        add(trimmed)
        add(withoutScheme)
        if (path.isNotBlank()) add(path)
    }.filterTo(linkedSetOf()) { it.isNotBlank() }
}

private fun stableDiagnosticId(value: String): String {
    var hash = 1_125_899_906_842_597L
    value.forEach { character ->
        hash = 31L * hash + character.code
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
