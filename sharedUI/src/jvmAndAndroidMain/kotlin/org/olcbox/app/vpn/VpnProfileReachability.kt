package org.olcbox.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.olcbox.app.data.model.VpnProfileConfig
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.Inflater

internal data class VpnProfileEndpoint(
    val host: String,
    val port: Int
)

internal object VpnProfileReachability {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ping(profile: VpnProfileConfig): Long? = withContext(Dispatchers.IO) {
        val normalized = profile.normalized()
        val endpoint = endpoint(normalized) ?: return@withContext null

        when (normalized.normalizedType) {
            VpnProfileConfig.TYPE_VLESS -> tcpPing(endpoint)
                ?: systemPing(endpoint.host)
                ?: hostPing(endpoint.host)
            VpnProfileConfig.TYPE_AMNEZIA_WG,
            VpnProfileConfig.TYPE_AMNEZIA_VPN -> systemPing(endpoint.host)
                ?: hostPing(endpoint.host)
                ?: tcpPing(endpoint)
            else -> tcpPing(endpoint)
                ?: systemPing(endpoint.host)
                ?: hostPing(endpoint.host)
        }
    }

    fun endpoint(profile: VpnProfileConfig): VpnProfileEndpoint? {
        val normalized = profile.normalized()
        if (normalized.normalizedType == VpnProfileConfig.TYPE_VLESS) {
            listOfNotNull(normalized.rawConfig, normalized.uri)
                .firstNotNullOfOrNull(::parseVlessEndpoint)
                ?.let { return it }
        }

        if (normalized.normalizedType == VpnProfileConfig.TYPE_AMNEZIA_WG ||
            normalized.normalizedType == VpnProfileConfig.TYPE_AMNEZIA_VPN
        ) {
            listOfNotNull(normalized.rawConfig, normalized.uri)
                .firstNotNullOfOrNull { extractWireGuardConfig(it)?.let(::parseWireGuardEndpoint) }
                ?.let { return it }
        }

        val localPort = normalized.localSocksPort ?: return null
        return VpnProfileEndpoint(
            host = normalized.localSocksHost?.trim().orEmpty().ifBlank { "127.0.0.1" },
            port = localPort
        )
    }

    private fun tcpPing(endpoint: VpnProfileEndpoint): Long? {
        val startedAt = System.nanoTime()
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(endpoint.host, endpoint.port),
                    TCP_CONNECT_TIMEOUT_MS
                )
            }
            elapsedMillis(startedAt)
        }.getOrNull()
    }

    private fun hostPing(host: String): Long? {
        val startedAt = System.nanoTime()
        return runCatching {
            val reachable = InetAddress.getByName(host).isReachable(HOST_PING_TIMEOUT_MS)
            if (reachable) elapsedMillis(startedAt) else null
        }.getOrNull()
    }

    private fun systemPing(host: String): Long? {
        val command = if (System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
            listOf("ping.exe", "-n", "1", "-w", SYSTEM_PING_TIMEOUT_MS.toString(), host)
        } else {
            listOf("ping", "-n", "-c", "1", "-W", SYSTEM_PING_TIMEOUT_SECONDS.toString(), host)
        }
        val startedAt = System.nanoTime()
        val process = runCatching {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null

        val exitCode = waitForProcess(process, SYSTEM_PING_PROCESS_TIMEOUT_MS) ?: return null
        val output = runCatching {
            process.inputStream.bufferedReader().use { reader ->
                reader.readText().take(MAX_PING_OUTPUT_LENGTH)
            }
        }.getOrDefault("")
        if (exitCode != 0) return null

        val reportedLatency = SYSTEM_PING_LATENCY
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.toLong()
        return reportedLatency ?: elapsedMillis(startedAt)
    }

    private fun waitForProcess(process: Process, timeoutMs: Long): Int? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            try {
                return process.exitValue()
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(PROCESS_POLL_INTERVAL_MS)
            }
        }
        process.destroy()
        return null
    }

    private fun elapsedMillis(startedAt: Long): Long {
        return ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun parseVlessEndpoint(value: String): VpnProfileEndpoint? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("vless://", ignoreCase = true)) return null

        val payload = trimmed.substring("vless://".length)
            .substringBefore('#')
            .substringBefore('?')
        val at = payload.lastIndexOf('@')
        if (at <= 0 || at == payload.lastIndex) return null

        return parseHostPort(payload.substring(at + 1), decodeHost = true)
    }

    private fun extractWireGuardConfig(value: String, depth: Int = 0): String? {
        if (depth > MAX_NESTED_PROFILE_DEPTH) return null
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (looksLikeWireGuardConfig(trimmed)) return trimmed

        val decoded = when {
            trimmed.startsWith("awg://", ignoreCase = true) -> decodeCompressedUri(trimmed, "awg://")
            trimmed.startsWith("vpn://", ignoreCase = true) -> decodeCompressedUri(trimmed, "vpn://")
            else -> null
        }
        if (!decoded.isNullOrBlank()) {
            extractWireGuardConfig(decoded, depth + 1)?.let { return it }
        }

        if (!trimmed.startsWith('{') && !trimmed.startsWith('[') && !trimmed.startsWith('"')) {
            return null
        }
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        return extractWireGuardConfig(element, depth + 1)
    }

    private fun extractWireGuardConfig(element: JsonElement, depth: Int): String? {
        if (depth > MAX_NESTED_PROFILE_DEPTH) return null
        return when (element) {
            is JsonObject -> element.values.firstNotNullOfOrNull {
                extractWireGuardConfig(it, depth + 1)
            }
            is JsonArray -> element.firstNotNullOfOrNull {
                extractWireGuardConfig(it, depth + 1)
            }
            is JsonPrimitive -> element.contentOrNull?.let {
                extractWireGuardConfig(it, depth + 1)
            }
        }
    }

    private fun decodeCompressedUri(uri: String, prefix: String): String? {
        val encoded = uri.substring(prefix.length).substringBefore('#').trim()
        if (encoded.isBlank()) return null

        return runCatching {
            val compressed = Base64.getUrlDecoder().decode(encoded)
            val payload = if (compressed.size > 4) compressed.copyOfRange(4, compressed.size) else compressed
            val inflater = Inflater()
            val output = ByteArrayOutputStream()
            try {
                inflater.setInput(payload)
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        require(output.size() + count <= MAX_DECOMPRESSED_PROFILE_SIZE) {
                            "Compressed VPN profile is too large"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } finally {
                inflater.end()
            }
            output.toString(StandardCharsets.UTF_8.name())
        }.getOrNull()
    }

    private fun parseWireGuardEndpoint(config: String): VpnProfileEndpoint? {
        val endpoint = config.lineSequence()
            .map(String::trim)
            .firstOrNull { it.substringBefore('=', "").trim().equals("endpoint", ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            ?: return null
        return parseHostPort(endpoint, decodeHost = false)
    }

    private fun parseHostPort(value: String, decodeHost: Boolean): VpnProfileEndpoint? {
        val host: String
        val portText: String
        if (value.startsWith('[')) {
            val closing = value.indexOf(']')
            if (closing <= 1 || closing + 2 >= value.length || value[closing + 1] != ':') return null
            host = value.substring(1, closing)
            portText = value.substring(closing + 2)
        } else {
            val separator = value.lastIndexOf(':')
            if (separator <= 0 || separator == value.lastIndex) return null
            host = value.substring(0, separator)
            portText = value.substring(separator + 1)
        }

        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val normalizedHost = if (decodeHost) {
            runCatching {
                URLDecoder.decode(host.replace("+", "%2B"), StandardCharsets.UTF_8.name())
            }.getOrDefault(host)
        } else {
            host
        }.trim()
        if (normalizedHost.isBlank()) return null
        return VpnProfileEndpoint(normalizedHost, port)
    }

    private fun looksLikeWireGuardConfig(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("[interface]") &&
            lower.contains("[peer]") &&
            lower.lineSequence().any { line ->
                line.substringBefore('=', "").trim() == "endpoint"
            }
    }

    private const val TCP_CONNECT_TIMEOUT_MS = 3_000
    private const val HOST_PING_TIMEOUT_MS = 3_000
    private const val SYSTEM_PING_TIMEOUT_MS = 3_000
    private const val SYSTEM_PING_TIMEOUT_SECONDS = 3
    private const val SYSTEM_PING_PROCESS_TIMEOUT_MS = 5_000L
    private const val PROCESS_POLL_INTERVAL_MS = 25L
    private const val MAX_PING_OUTPUT_LENGTH = 16 * 1024
    private const val MAX_DECOMPRESSED_PROFILE_SIZE = 4 * 1024 * 1024
    private const val MAX_NESTED_PROFILE_DEPTH = 12
    private val SYSTEM_PING_LATENCY = Regex(
        pattern = "time[=<]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms",
        option = RegexOption.IGNORE_CASE
    )
}
