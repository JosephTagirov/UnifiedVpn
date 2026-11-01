package org.olcbox.app.data.model

import io.ktor.http.Url
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class OpenFluxProfileConfig(
    @SerialName("document_url") val documentUrl: String = "",
    @SerialName("encryption_key") val encryptionKey: String = "",
    val version: Int = 1,
    val transport: String = TRANSPORT_YANDEX,
    // Omit the legacy default so older strict readers can still import existing profiles.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("wire_codec") val wireCodec: String = CODEC_LEGACY
) {
    fun normalized(): OpenFluxProfileConfig = copy(
        documentUrl = documentUrl.trim(),
        encryptionKey = if (version == 2) encryptionKey.trim() else encryptionKey.trim().lowercase(),
        transport = transport.trim().lowercase(),
        wireCodec = wireCodec.trim().lowercase()
    )

    val isSession: Boolean get() = version == 2

    fun toSessionConfig(name: String = ""): OpenFluxSessionProfileConfig {
        val config = normalized()
        return OpenFluxSessionProfileConfig(
            name = name, negotiate = true, codec = "batched", secret = config.encryptionKey,
            context = config.documentUrl,
            transports = listOf(if (config.transport == TRANSPORT_DIRECT) OpenFluxSessionTransport(type = TRANSPORT_DIRECT, dial = config.documentUrl.removePrefix("tcp://"), priority = 50) else OpenFluxSessionTransport(type = config.transport, url = config.documentUrl, priority = 50))
        )
    }

    fun hasValidWireCodec(): Boolean {
        val config = normalized()
        if (config.isSession) return config.wireCodec == CODEC_SESSION && config.transport in sessionTransports
        return config.wireCodec in classicWireCodecs &&
            (config.wireCodec != CODEC_BATCH || config.transport == TRANSPORT_YANDEX)
    }

    fun isValid(): Boolean {
        val config = normalized()
        if (config.isSession) return config.hasValidWireCodec() && config.toSessionConfig().isValid()
        if (config.version != 1 || config.transport !in classicTransports) return false
        if (!config.hasValidWireCodec()) return false
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
        require(isValid()) { "OpenFlux requires a supported transport URL and a 64-character encryption key" }
        if (isSession) return toSessionConfig().toUri()
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
        require(config.isValid()) { "OpenFlux requires a supported transport URL and a 64-character encryption key" }
        require(mode == "client" || mode == "server") { "Invalid OpenFlux mode" }
        require(!config.isSession || mode == "client") { "Session wrapper supports client profiles only" }
        require(socksHost == "127.0.0.1") { "OpenFlux SOCKS must listen on loopback" }
        require(socksPort in 1..65535) { "Invalid OpenFlux SOCKS port" }
        require(socksUsername.isEmpty() == socksPassword.isEmpty()) { "Both SOCKS credentials are required" }
        require(socksUsername.encodeToByteArray().size <= 255 && socksPassword.encodeToByteArray().size <= 255) {
            "SOCKS credentials are too long"
        }
        return buildJsonObject {
            put("version", config.version)
            put("mode", mode)
            put("transport", config.transport)
            if (config.wireCodec != CODEC_LEGACY) put("wire_codec", config.wireCodec)
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
        const val TRANSPORT_YANDEX = "yandex"
        const val TRANSPORT_VYANDEX = "vyandex"
        const val TRANSPORT_TELEMOST = "telemost"
        const val TRANSPORT_MAILRU = "mailru"
        const val TRANSPORT_DIRECT = "direct"
        const val CODEC_LEGACY = "legacy-lz4-v1"
        const val CODEC_BATCH = "batch-zstd-v2"
        const val CODEC_SESSION = "upstream-session-v1"
        val sessionTransports: List<String> = listOf(TRANSPORT_TELEMOST, TRANSPORT_MAILRU, TRANSPORT_DIRECT)
        val classicTransports: List<String> = listOf(TRANSPORT_YANDEX, TRANSPORT_VYANDEX)
        val supportedTransports: List<String> = classicTransports + sessionTransports
        val classicWireCodecs: List<String> = listOf(CODEC_LEGACY, CODEC_BATCH)
        val supportedWireCodecs: List<String> = classicWireCodecs + CODEC_SESSION
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")
        private val json = Json { encodeDefaults = true }

        @OptIn(ExperimentalEncodingApi::class)
        fun parse(raw: String?): OpenFluxProfileConfig? {
            val value = raw?.trim()?.takeIf { it.length in 1..32768 } ?: return null
            if (value.startsWith(OpenFluxSessionProfileConfig.LINK_PREFIX)) {
                val session = OpenFluxSessionProfileConfig.parseLink(value) ?: return null
                return OpenFluxProfileConfig(
                    documentUrl = if (session.transports.single().type == TRANSPORT_DIRECT) session.context else session.transports.single().url, encryptionKey = session.secret,
                    version = 2, transport = session.transports.single().type, wireCodec = CODEC_SESSION
                )
            }
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
