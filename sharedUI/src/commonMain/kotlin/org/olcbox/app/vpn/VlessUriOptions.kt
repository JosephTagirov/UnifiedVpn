package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal fun vlessUriPayload(uri: String): String {
    val prefix = "vless://"
    require(uri.startsWith(prefix, ignoreCase = true)) { "Invalid VLESS URI" }
    return uri.substring(prefix.length)
}

internal fun parseXhttpExtra(value: String?): JsonObject? {
    if (value.isNullOrBlank()) return null
    return try {
        Json.parseToJsonElement(value) as? JsonObject
            ?: throw IllegalArgumentException()
    } catch (_: IllegalArgumentException) {
        // Do not include subscription parameters or keys in the error.
        throw IllegalArgumentException("Invalid XHTTP extra settings: expected a JSON object")
    }
}
