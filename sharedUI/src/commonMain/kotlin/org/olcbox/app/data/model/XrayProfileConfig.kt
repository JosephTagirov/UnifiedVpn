package org.olcbox.app.data.model

import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Validated credentials and transport options, never an executable core configuration. */
data class XrayProfileConfig(
    val type: String, val host: String, val port: Int, val name: String,
    val credential: String, val transport: String = "raw", val tls: Boolean = true,
    val sni: String = host, val hostHeader: String? = null, val path: String = "/",
    val alpn: List<String> = emptyList(), val fingerprint: String? = null,
    val cipher: String = "auto", val mode: String? = null
) {
    override fun toString(): String = "XrayProfileConfig(type=$type, host=$host, port=$port, transport=$transport, tls=$tls, credential=<redacted>)"

    companion object {
        fun parse(profile: VpnProfileConfig): XrayProfileConfig? = runCatching {
            val source = profile.rawConfig?.takeIf { it.isNotBlank() } ?: profile.uri.orEmpty()
            parse(source, profile.normalizedType)
        }.getOrNull()

        fun parse(source: String, type: String): XrayProfileConfig {
            val value = source.trim()
            require(value.isNotEmpty() && value.length <= 65536) { "Invalid Xray profile size" }
            return when (VpnProfileConfig.normalizeType(type)) {
                VpnProfileConfig.TYPE_VMESS -> vmess(value)
                VpnProfileConfig.TYPE_HYSTERIA2 -> hysteria(value)
                else -> error("Unsupported Xray profile type")
            }
        }

        private fun host(value: String): String {
            val clean = value.removeSurrounding("[", "]")
            require(clean.isNotBlank() && clean.length <= 253 && clean.none { it.isWhitespace() || it in "/\\?#@" || it.code < 32 }) { "Invalid server host" }
            require(!clean.startsWith('-')) { "Invalid server host" }
            return clean
        }

        @OptIn(ExperimentalEncodingApi::class)
        private fun vmess(value: String): XrayProfileConfig {
            val text = if (value.startsWith("vmess://", true)) {
                val encoded = value.substring(8).substringBefore('#')
                require(encoded.isNotBlank() && encoded.none(Char::isWhitespace)) { "Invalid VMess URI" }
                val base = encoded.replace('-', '+').replace('_', '/')
                Base64.decode(base + "=".repeat((4 - base.length % 4) % 4)).decodeToString(throwOnInvalidSequence = true)
            } else value.also { require(it.startsWith('{')) { "VMess requires a vmess URI or share JSON" } }
            val obj = Json.parseToJsonElement(text) as? JsonObject ?: error("Invalid VMess JSON")
            fun field(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val address = host(field("add"))
            val port = field("port").toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Invalid VMess port")
            val id = field("id")
            require(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(id)) { "Invalid VMess UUID" }
            require(field("aid").let { it.isBlank() || it == "0" }) { "Legacy VMess alterId is unsupported" }
            val network = field("net").ifBlank { "tcp" }.let { if (it == "tcp") "raw" else it }
            require(network in setOf("raw", "ws", "grpc", "httpupgrade", "xhttp")) { "Unsupported VMess transport" }
            require(network != "raw" || field("type") in setOf("", "none")) { "Unsupported TCP header" }
            val tls = field("tls")
            require(tls in setOf("", "none", "tls")) { "Unsupported VMess security" }
            require(field("allowInsecure").lowercase() in setOf("", "0", "false")) { "Insecure TLS is unsupported" }
            val httpHost = field("host").takeIf { it.isNotBlank() }?.also { require(it.none { c -> c == '\r' || c == '\n' }) }
            val sni = host(field("sni").ifBlank { httpHost ?: address })
            val cipher = field("scy").ifBlank { "auto" }
            require(cipher in setOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")) { "Unsupported VMess cipher" }
            val path = field("path").ifBlank { "/" }.also { require(it.none { c -> c == '\r' || c == '\n' }) }
            val mode = field("type").takeIf { it !in setOf("", "none") }
            if (network == "grpc") require(mode in setOf(null,"gun","multi")) { "Unsupported gRPC mode" }
            if (network == "xhttp") require(mode in setOf(null,"auto","packet-up","stream-up","stream-one")) { "Unsupported XHTTP mode" }
            val alpn = field("alpn").split(',').map(String::trim).filter(String::isNotEmpty)
            return XrayProfileConfig(VpnProfileConfig.TYPE_VMESS,address,port,field("ps").ifBlank { "VMess" },id,
                network,tls == "tls",sni,httpHost,path,alpn,field("fp").takeIf(String::isNotBlank),cipher,mode)
        }

        private fun hysteria(value: String): XrayProfileConfig {
            if (value.startsWith('{')) return hysteriaJson(value)
            require(value.startsWith("hysteria2://",true) || value.startsWith("hy2://",true)) { "Hysteria2 requires a hysteria2 or hy2 URI" }
            require(value.none { it.isWhitespace() || it.code < 32 }) { "Invalid Hysteria2 URI" }
            require(value.indices.filter { value[it] == '%' }.all { index ->
                index + 2 < value.length && value.substring(index + 1, index + 3).all { it.digitToIntOrNull(16) != null }
            }) { "Invalid Hysteria2 encoding" }
            val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            require(authority.count { it == '@' } == 1) { "Invalid Hysteria2 credentials" }
            val url = Url(value)
            require(url.encodedPath in setOf("", "/")) { "Unsupported Hysteria2 path" }
            val auth = url.user?.let { if (url.password != null) it + ":" + url.password else it }.orEmpty()
            require(auth.isNotBlank() && auth.length <= 4096 && auth.none { it.code < 32 }) { "Missing Hysteria2 auth" }
            require(url.parameters.names().all { it in setOf("sni", "insecure") }) { "Unsupported Hysteria2 option" }
            for (key in url.parameters.names()) require(url.parameters.getAll(key)?.size == 1) { "Duplicate Hysteria2 option" }
            require(url.parameters["insecure"]?.lowercase() in setOf(null,"0","false")) { "Insecure TLS is unsupported" }
            val hostname = host(url.host)
            val port = hysteriaPort(authority.substringAfter('@'))
            val sni = host(url.parameters["sni"]?.takeIf(String::isNotBlank) ?: hostname)
            return XrayProfileConfig(VpnProfileConfig.TYPE_HYSTERIA2,hostname,port,url.fragment.ifBlank { "Hysteria2" },auth,
                transport="hysteria",sni=sni,alpn=listOf("h3"))
        }

        private fun hysteriaJson(value: String): XrayProfileConfig {
            val obj = Json.parseToJsonElement(value) as? JsonObject ?: error("Invalid Hysteria2 JSON")
            require(obj.keys.all { it in setOf("server","auth","tls","name") }) { "Unsupported Hysteria2 JSON option" }
            fun text(key: String): String {
                val field = obj[key] as? JsonPrimitive ?: error("Missing Hysteria2 field")
                require(field.isString) { "Invalid Hysteria2 field" };return field.content
            }
            val server = text("server")
            require(server.none { it.isWhitespace() || it.code < 32 } && "://" !in server) { "Invalid Hysteria2 server" }
            val endpoint = Url("https://$server")
            require(endpoint.user == null && endpoint.password == null && endpoint.parameters.names().isEmpty() &&
                endpoint.fragment.isEmpty() && endpoint.encodedPath in setOf("","/")) { "Invalid Hysteria2 server" }
            val auth = text("auth")
            require(auth.isNotBlank() && auth.length<=4096 && auth.none { it.code<32 }) { "Invalid Hysteria2 auth" }
            val tls = obj["tls"]?.let { it as? JsonObject ?: error("Invalid TLS settings") } ?: JsonObject(emptyMap())
            require(tls.keys.all { it in setOf("sni","insecure") }) { "Unsupported Hysteria2 TLS option" }
            require(tls["insecure"] == null || tls["insecure"] == JsonPrimitive(false)) { "Insecure TLS is unsupported" }
            val hostname = host(endpoint.host)
            fun optionalString(container: JsonObject, key: String): String? = container[key]?.let {
                val primitive = it as? JsonPrimitive ?: error("Invalid Hysteria2 field")
                require(primitive.isString) { "Invalid Hysteria2 field" }
                primitive.content.takeIf(String::isNotBlank)
            }
            val sni = optionalString(tls, "sni")?.let(::host) ?: hostname
            val name = optionalString(obj, "name") ?: "Hysteria2"
            return XrayProfileConfig(VpnProfileConfig.TYPE_HYSTERIA2,hostname,hysteriaPort(server),name,auth,
                transport="hysteria",sni=sni,alpn=listOf("h3"))
        }

        private fun hysteriaPort(server: String): Int {
            val suffix = if (server.startsWith('[')) {
                require(']' in server) { "Invalid Hysteria2 endpoint" }
                server.substringAfter(']')
            } else {
                require(server.count { it == ':' } <= 1) { "Bracket IPv6 Hysteria2 endpoints" }
                server.indexOf(':').let { if (it < 0) "" else server.substring(it) }
            }
            if (suffix.isEmpty()) return 443
            require(suffix.startsWith(':')) { "Invalid Hysteria2 endpoint" }
            return suffix.drop(1).toIntOrNull()?.takeIf { it in 1..65535 }
                ?: error("Invalid Hysteria2 port")
        }
    }
}
