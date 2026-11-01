package org.olcbox.app.data.model

import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Upstream Session profiles are not compatible with our classic AES-GCM wrapper. */
@Serializable
data class OpenFluxSessionProfileConfig(
    val name: String = "",
    val negotiate: Boolean = false,
    val codec: String = "",
    val secret: String = "",
    val context: String = "",
    val mode: String = "",
    val transports: List<OpenFluxSessionTransport> = emptyList()
) {
    fun toUri(): String {
        require(isValid()) { "Unsupported or insecure OpenFlux Session profile" }
        return LINK_PREFIX + encodeOpenFluxSessionPayload(Json.encodeToString(this))
    }

    val supportsDesktopTun: Boolean
        get() = isValid() && transports.single().type == TRANSPORT_MAILRU

    fun isValid(): Boolean {
        if (!negotiate || codec !in setOf("", "batched") || mode.isNotEmpty()) return false
        if (!SECRET_PATTERN.matches(secret) || name.length > 256) return false
        val carrier = transports.singleOrNull() ?: return false
        if (!carrier.isValid()) return false
        // This first integration accepts the context used by our separately provisioned peers.
        // Never trim or lowercase the secret or silently infer a different KDF context.
        return if (carrier.type == TRANSPORT_DIRECT) context == "tcp://${carrier.dial}" else context == carrier.url
    }

    fun toEngineConf(keyFile: String, cookieStore: String, socksPort: Int): String {
        require(isValid()) { "Unsupported or insecure OpenFlux Session profile" }
        require(socksPort in 1..65535) { "Invalid OpenFlux SOCKS port" }
        require(safeConfigPath(keyFile) && safeConfigPath(cookieStore)) { "Invalid OpenFlux private file path" }
        val carrier = transports.single()
        return buildString {
            appendLine("[Interface]")
            appendLine("Role = client")
            appendLine("Mode = l4")
            appendLine("Inbound = socks5")
            appendLine("Socks5 = 127.0.0.1:$socksPort")
            appendLine("Codec = batched")
            appendLine("EncryptionKeyFile = $keyFile")
            appendLine("SessionContext = $context")
            appendLine("CookieStore = $cookieStore")
            appendLine("Debug = 0")
            appendLine("Sensitive = false")
            appendLine()
            appendLine("[Transport \"${carrier.type}\"]")
            appendLine("Type = ${carrier.type}")
            appendLine("Priority = 50")
            if (carrier.type == TRANSPORT_DIRECT) appendLine("Dial = ${carrier.dial}")
            else appendLine("URL = ${carrier.url}")
        }
    }

    override fun toString(): String = "OpenFluxSessionProfileConfig(secrets=[redacted])"

    companion object {
        const val TRANSPORT_TELEMOST = "telemost"
        const val TRANSPORT_MAILRU = "mailru"
        const val TRANSPORT_DIRECT = "direct"
        const val LINK_PREFIX = "openflux://v1/"
        const val CORE_VERSION = "0.17.1"
        const val CORE_REVISION = "f492a1a33b7581ad8f88118f97e0b866adda3cdc"
        private val SECRET_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val json = Json

        fun parseLink(raw: String): OpenFluxSessionProfileConfig? {
            val text = raw.trim()
            if (text.length !in 1..32768 || !text.startsWith(LINK_PREFIX)) return null
            val payload = decodeOpenFluxSessionPayload(text.removePrefix(LINK_PREFIX)) ?: return null
            return parseCoreConfig(payload)
        }

        /** Decode the `config` JSON returned by the official core's offline --parse-link. */
        fun parseCoreConfig(raw: String): OpenFluxSessionProfileConfig? {
            if (raw.length !in 1..16384 || raw.encodeToByteArray().size > 16384) return null
            return runCatching {
                json.decodeFromString<OpenFluxSessionProfileConfig>(raw).takeIf { it.isValid() }
            }.getOrNull()
        }

        fun engineArguments(configPath: String): List<String> {
            require(safeConfigPath(configPath)) { "Invalid OpenFlux private file path" }
            // .conf transport sections alone still allow client legacy fallback in upstream 0.5.0.
            return listOf("--config", configPath, "--negotiate")
        }

        private fun safeConfigPath(value: String): Boolean =
            value.length in 1..4096 && value == value.trim() &&
                value.none { it.code < 32 || it.code == 127 || it in "#;\"" }
    }
}

internal expect fun decodeOpenFluxSessionPayload(encoded: String): String?
internal expect fun encodeOpenFluxSessionPayload(json: String): String

@Serializable
data class OpenFluxSessionTransport(
    val type: String = "",
    val name: String = "",
    val url: String = "",
    val priority: Int = 0,
    val dial: String = ""
) {
    fun isValid(): Boolean {
        if (name.isNotEmpty() && name != type) return false
        if (priority !in 0..100) return false
        if (type == OpenFluxSessionProfileConfig.TRANSPORT_DIRECT) {
            return url.isEmpty() && validPublicDirectEndpoint(dial)
        }
        if (dial.isNotEmpty()) return false
        if (url.length !in 1..8192 || url.any { it.isWhitespace() || it.code < 32 || it.code == 127 }) return false
        val parsed = runCatching { Url(url) }.getOrNull() ?: return false
        if (parsed.protocol.name != "https" || parsed.port != 443 ||
            !parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty() ||
            parsed.fragment.isNotEmpty() || parsed.encodedQuery.isNotEmpty() || url.any { it in "#;\"" }
        ) return false
        return when (type) {
            OpenFluxSessionProfileConfig.TRANSPORT_TELEMOST ->
                parsed.host == "telemost.yandex.ru" && Regex("/j/[0-9]{14}").matches(parsed.encodedPath)
            OpenFluxSessionProfileConfig.TRANSPORT_MAILRU ->
                parsed.host == "cloud.mail.ru" && Regex("/public/[A-Za-z0-9]+/[A-Za-z0-9]+").matches(parsed.encodedPath)
            else -> false
        }
    }

    override fun toString(): String = "OpenFluxSessionTransport(secrets=[redacted])"
}


internal fun validPublicDirectEndpoint(value: String): Boolean {
    val match = Regex("^([0-9]{1,3}(?:\\.[0-9]{1,3}){3}):([0-9]{1,5})$").matchEntire(value) ?: return false
    val octets = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 } || octets.joinToString(".") != match.groupValues[1]) return false
    if (match.groupValues[2].toIntOrNull() !in 1024..65535) return false
    val (a,b,c,_) = octets
    if (a in setOf(0,10,127) || a >= 224 || a == 169 && b == 254 || a == 172 && b in 16..31 || a == 192 && b == 168) return false
    if (a == 100 && b in 64..127 || a == 192 && b == 0 && c in setOf(0,2) || a == 198 && b in 18..19 || a == 198 && b == 51 && c == 100 || a == 203 && b == 0 && c == 113) return false
    return true
}
