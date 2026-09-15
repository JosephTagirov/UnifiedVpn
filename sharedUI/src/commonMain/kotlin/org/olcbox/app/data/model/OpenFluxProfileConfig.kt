package org.olcbox.app.data.model

import io.ktor.http.Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class OpenFluxProfileConfig(
    @SerialName("document_url") val documentUrl: String = "",
    @SerialName("encryption_key") val encryptionKey: String = "",
    val version: Int = 1,
    val transport: String = "yandex"
) {
    fun normalized(): OpenFluxProfileConfig = copy(
        documentUrl = documentUrl.trim(),
        encryptionKey = encryptionKey.trim().lowercase(),
        transport = transport.trim().lowercase()
    )

    fun isValid(): Boolean {
        val config = normalized()
        if (config.version != 1 || config.transport != "yandex") return false
        if (!KEY_PATTERN.matches(config.encryptionKey)) return false
        if (config.documentUrl.length !in 1..8192 || config.documentUrl.any { it.isWhitespace() }) return false
        val url = runCatching { Url(config.documentUrl) }.getOrNull() ?: return false
        return url.protocol.name == "https" && url.port == 443 &&
            url.host.lowercase() in setOf("docs.yandex.ru", "disk.yandex.ru") &&
            url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty() &&
            url.encodedPath.length > 1
    }

    fun toJson(): String = json.encodeToString(normalized())

    @OptIn(ExperimentalEncodingApi::class)
    fun toUri(): String {
        require(isValid()) { "OpenFlux requires a Yandex document URL and a 64-character encryption key" }
        return "openflux://" + Base64.UrlSafe.encode(toJson().encodeToByteArray()).trimEnd('=')
    }

    fun toEngineJson(
        mode: String = "client",
        socksHost: String = "127.0.0.1",
        socksPort: Int = 10808,
        socksUsername: String = "",
        socksPassword: String = ""
    ): String {
        val config = normalized()
        require(config.isValid()) { "OpenFlux requires a Yandex document URL and a 64-character encryption key" }
        require(mode == "client" || mode == "server") { "Invalid OpenFlux mode" }
        require(socksHost == "127.0.0.1") { "OpenFlux SOCKS must listen on loopback" }
        require(socksPort in 1..65535) { "Invalid OpenFlux SOCKS port" }
        require(socksUsername.isEmpty() == socksPassword.isEmpty()) { "Both SOCKS credentials are required" }
        require(socksUsername.encodeToByteArray().size <= 255 && socksPassword.encodeToByteArray().size <= 255) {
            "SOCKS credentials are too long"
        }
        return buildJsonObject {
            put("version", 1)
            put("mode", mode)
            put("transport", "yandex")
            put("document_url", config.documentUrl)
            put("encryption_key", config.encryptionKey)
            put("handshake_timeout_seconds", 60)
            if (mode == "client") {
                put("socks5", "$socksHost:$socksPort")
                put("socks_username", socksUsername)
                put("socks_password", socksPassword)
                put("dns_server", "1.1.1.1:53")
            }
        }.toString()
    }

    override fun toString(): String = "OpenFluxProfileConfig(transport=$transport, secrets=[redacted])"

    companion object {
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")
        private val json = Json { encodeDefaults = true }

        @OptIn(ExperimentalEncodingApi::class)
        fun parse(raw: String?): OpenFluxProfileConfig? {
            val value = raw?.trim()?.takeIf { it.length in 1..32768 } ?: return null
            return runCatching {
                val source = if (value.startsWith("openflux://", ignoreCase = true)) {
                    val encoded = value.substringAfter("://").substringBefore('#')
                    require(encoded.isNotEmpty() && encoded.all { it.isLetterOrDigit() || it in "_-=" })
                    val padded = encoded.padEnd((encoded.length + 3) / 4 * 4, '=')
                    Base64.UrlSafe.decode(padded).decodeToString(throwOnInvalidSequence = true)
                } else value
                json.decodeFromString<OpenFluxProfileConfig>(source).normalized()
            }.getOrNull()
        }
    }
}
