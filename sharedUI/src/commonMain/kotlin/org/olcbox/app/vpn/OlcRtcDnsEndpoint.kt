package org.olcbox.app.vpn

internal fun normalizeOlcRtcDnsEndpoint(value: String): String {
    val endpoint = value.trim()
    if (endpoint.isEmpty()) return endpoint

    if (endpoint.startsWith("[")) {
        val closingBracket = endpoint.indexOf(']')
        return if (closingBracket == endpoint.lastIndex) "$endpoint:$DEFAULT_DNS_PORT" else endpoint
    }

    return when (endpoint.count { it == ':' }) {
        0 -> "$endpoint:$DEFAULT_DNS_PORT"
        1 -> endpoint
        else -> "[$endpoint]:$DEFAULT_DNS_PORT"
    }
}

internal fun selectOlcRtcDnsEndpoint(
    configuredValue: String,
    fallbackValue: String
): String {
    val selected = configuredValue.trim().ifEmpty { fallbackValue.trim() }
    return normalizeOlcRtcDnsEndpoint(selected)
}

private const val DEFAULT_DNS_PORT = 53
