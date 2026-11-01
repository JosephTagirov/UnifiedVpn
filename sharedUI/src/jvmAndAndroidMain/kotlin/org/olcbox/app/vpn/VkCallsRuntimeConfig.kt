package org.olcbox.app.vpn

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.data.logging.sanitizeOlcRtcDiagnosticOutput
import org.olcbox.app.data.model.LocationConfig
import java.net.URI
import java.net.Socket
import java.net.InetSocketAddress

internal object VkCallsRuntimeConfig {
    const val CORE_REVISION = "5b1e6172a9f620d477624c6575c392e70449d333"
    const val ENGINE_TYPE = "vkcalls"
    const val DTLS_PROFILE = "chrome-linux-138-compat-v1"
    const val READY_MARKER = "SOCKS5 server listening"

    fun probeSocks(port: Int, user: String, password: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 250)
            socket.soTimeout = 500
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val authenticated = user.isNotBlank()
            output.write(byteArrayOf(5, 1, if (authenticated) 2 else 0))
            output.flush()
            if (input.read() != 5 || input.read() != (if (authenticated) 2 else 0)) return@use false
            if (!authenticated) return@use password.isBlank()
            val username = user.toByteArray(Charsets.UTF_8)
            val pass = password.toByteArray(Charsets.UTF_8)
            if (username.size !in 1..255 || pass.size !in 1..255) return@use false
            output.write(byteArrayOf(1, username.size.toByte()))
            output.write(username)
            output.write(pass.size)
            output.write(pass)
            output.flush()
            input.read() == 1 && input.read() == 0
        }
    }.getOrDefault(false)

    fun validRoom(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host in setOf("vk.ru", "vk.com") &&
            uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.orEmpty().matches(Regex("/call/join/[A-Za-z0-9_-]{1,256}"))
    }.getOrDefault(false)

    fun build(location: LocationConfig, port: Int, user: String, password: String, dns: String): String {
        val config = location.normalized()
        require(config.isVkCalls() && validRoom(config.id)) { "Use an HTTPS VK Calls join link" }
        require(config.hasValidCryptoKey()) { "VK Calls key must be 64 hexadecimal characters" }
        require(port in 1..65535) { "Invalid VK Calls SOCKS port" }
        require(user.isBlank() == password.isBlank()) { "VK Calls SOCKS authentication requires both username and password" }
        require(user.toByteArray().size <= 255 && password.toByteArray().size <= 255) {
            "VK Calls SOCKS credentials are too long"
        }
        require(dns.isNotBlank() && LocationConfig.isValidDnsServer(dns)) { "Invalid VK Calls DNS endpoint" }
        return buildJsonObject {
            put("mode", "cnc")
            put("debug", false)
            putJsonObject("auth") { put("provider", "vkcalls") }
            putJsonObject("room") { put("id", config.id) }
            putJsonObject("crypto") { put("key", config.key) }
            putJsonObject("net") {
                put("transport", "vp8channel")
                put("dns", dns)
            }
            putJsonObject("socks") {
                put("host", "127.0.0.1")
                put("port", port)
                put("user", user)
                put("pass", password)
            }
            putJsonObject("vp8") {
                put("fps", config.vp8Fps)
                put("batch_size", config.vp8Batch)
            }
            putJsonObject("dtls") { put("profile", DTLS_PROFILE) }
            putJsonObject("liveness") {
                put("interval", "10s")
                put("timeout", "15s")
                put("failures", 4)
            }
        }.toString()
    }

    fun sanitizedOutput(line: String, location: LocationConfig, user: String, password: String): String {
        var safe = line.take(4096)
        listOf(location.key, user, password).filter(String::isNotBlank).sortedByDescending(String::length)
            .forEach { safe = safe.replace(it, "<redacted>") }
        safe = sanitizeOlcRtcDiagnosticOutput(safe, location.id)
        // Signaling URLs can carry short-lived anonymous tokens outside the configured room.
        return Regex("(?i)\\b(?:https?|wss?)://[^\\s\"'<>]+").replace(safe, "<signaling-url>")
    }
}
