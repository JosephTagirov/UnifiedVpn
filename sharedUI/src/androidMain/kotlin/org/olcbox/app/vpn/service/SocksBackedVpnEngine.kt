package org.olcbox.app.vpn.service

import android.content.Context
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.olcbox.app.data.model.VpnProfileConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.zip.Inflater
import kotlin.concurrent.thread

internal interface SocksBackedVpnEngine {
    val profileType: String
    val socksHost: String
    val socksPort: Int
    val isRunning: Boolean
    suspend fun start()
    fun stop()
}

internal fun createSocksBackedVpnEngine(
    context: Context,
    profile: VpnProfileConfig,
    defaultSocksPort: Int,
    username: String,
    password: String,
    log: (String) -> Unit
): SocksBackedVpnEngine {
    val normalized = profile.normalized()
    val localSocksPort = normalized.localSocksPort
    if (localSocksPort != null) {
        return ExistingSocksEngine(
            profileType = normalized.normalizedType,
            host = normalized.localSocksHost ?: DEFAULT_SOCKS_HOST,
            port = localSocksPort,
            log = log
        )
    }

    return when (normalized.normalizedType) {
        VpnProfileConfig.TYPE_VLESS -> SingBoxVlessEngine(
            context = context.applicationContext,
            profile = normalized,
            socksPort = defaultSocksPort,
            username = username,
            password = password,
            log = log
        )

        VpnProfileConfig.TYPE_AMNEZIA_WG,
        VpnProfileConfig.TYPE_AMNEZIA_VPN -> SingBoxWireGuardEngine(
            context = context.applicationContext,
            profile = normalized,
            socksPort = defaultSocksPort,
            username = username,
            password = password,
            log = log
        )

        else -> MissingNativeEngine(
            profileType = normalized.normalizedType,
            message = "Unsupported VPN profile type: ${normalized.normalizedType}"
        )
    }
}

private class ExistingSocksEngine(
    override val profileType: String,
    private val host: String,
    private val port: Int,
    private val log: (String) -> Unit
) : SocksBackedVpnEngine {
    override val socksHost: String = host
    override val socksPort: Int = port
    override val isRunning: Boolean
        get() = canConnect(socksHost, socksPort)

    override suspend fun start() {
        if (!isRunning) {
            throw IllegalStateException("Local SOCKS $socksHost:$socksPort is not accepting connections")
        }
        log("Using existing SOCKS $socksHost:$socksPort")
    }

    override fun stop() = Unit
}

private class MissingNativeEngine(
    override val profileType: String,
    private val message: String
) : SocksBackedVpnEngine {
    override val socksHost: String = DEFAULT_SOCKS_HOST
    override val socksPort: Int = 0
    override val isRunning: Boolean = false

    override suspend fun start() {
        throw IllegalStateException(message)
    }

    override fun stop() = Unit
}

private class SingBoxVlessEngine(
    private val context: Context,
    private val profile: VpnProfileConfig,
    override val socksPort: Int,
    private val username: String,
    private val password: String,
    private val log: (String) -> Unit
) : SocksBackedVpnEngine {
    override val profileType: String = VpnProfileConfig.TYPE_VLESS
    override val socksHost: String = DEFAULT_SOCKS_HOST
    override val isRunning: Boolean
        get() = process?.isAlive == true && canConnect(socksHost, socksPort)

    private var process: Process? = null
    private var logThread: Thread? = null

    override suspend fun start() {
        val uri = profile.rawConfig?.takeIf { it.startsWith("vless://", ignoreCase = true) }
            ?: profile.uri?.takeIf { it.startsWith("vless://", ignoreCase = true) }
            ?: throw IllegalStateException("VLESS profile does not contain a vless:// URI")
        val executableSource = findEngineExecutable(context, "sing-box")
            ?: throw IllegalStateException(
                "sing-box executable is missing. Put it at jniLibs/<abi>/libsing-box.so " +
                    "or assets/bin/<abi>/sing-box."
            )
        val workDir = File(context.noBackupFilesDir, "engines/sing-box/${executableSource.abi}").apply {
            mkdirs()
        }
        val executable = executableSource.file ?: File(workDir, "sing-box").also { target ->
            copyAsset(context, executableSource.assetPath.orEmpty(), target)
            target.setExecutable(true, false)
        }
        val outbound = parseVlessUri(uri)
        val configFile = File(workDir, "vless-$socksPort.json").also { file ->
            file.writeText(buildSingBoxConfig(outbound))
        }

        stop()
        process = ProcessBuilder(
            executable.absolutePath,
            "run",
            "-c",
            configFile.absolutePath
        )
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        logThread = process?.let { started ->
            thread(name = "SingBoxVlessLog", isDaemon = true) {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> log("sing-box: $line") }
                }
            }
        }

        waitForSocksPort()
        log("VLESS ready on $socksHost:$socksPort")
    }

    override fun stop() {
        process?.destroy()
        process = null
        logThread?.interrupt()
        logThread = null
    }

    private suspend fun waitForSocksPort() {
        val deadline = System.currentTimeMillis() + SING_BOX_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val started = process
            if (started != null && !started.isAlive) {
                throw IllegalStateException("sing-box exited before SOCKS became ready")
            }
            if (canConnect(socksHost, socksPort)) return
            delay(SOCKS_READY_POLL_MS)
        }
        throw IllegalStateException("sing-box SOCKS port $socksPort did not become ready")
    }

    private fun buildSingBoxConfig(outbound: VlessOutbound): String {
        val inbound = buildSingBoxSocksInbound(socksHost, socksPort, username, password)

        val vless = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", outbound.host)
            .put("server_port", outbound.port)
            .put("uuid", outbound.uuid)

        outbound.flow?.let { vless.put("flow", it) }
        buildTransport(outbound)?.let { vless.put("transport", it) }

        if (outbound.security == "tls" || outbound.security == "reality") {
            val tls = JSONObject().put("enabled", true)
            outbound.sni?.let { tls.put("server_name", it) }
            outbound.fingerprint?.let {
                tls.put(
                    "utls",
                    JSONObject()
                        .put("enabled", true)
                        .put("fingerprint", it)
                )
            }
            if (outbound.security == "reality") {
                val reality = JSONObject().put("enabled", true)
                outbound.publicKey?.let { reality.put("public_key", it) }
                outbound.shortId?.let { reality.put("short_id", it) }
                tls.put("reality", reality)
            }
            vless.put("tls", tls)
        }

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(inbound))
            .put(
                "outbounds",
                JSONArray()
                    .put(vless)
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
            )
            .put("route", buildSingBoxRoute(finalOutbound = "proxy"))
            .toString(2)
    }

    private fun buildTransport(outbound: VlessOutbound): JSONObject? {
        return when (outbound.transport) {
            "tcp", "raw", null -> null

            "ws" -> {
                val transport = JSONObject()
                    .put("type", "ws")
                    .put("path", outbound.path ?: "/")
                outbound.hostHeader?.let {
                    transport.put("headers", JSONObject().put("Host", it))
                }
                transport
            }

            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                outbound.serviceName?.let { transport.put("service_name", it) }
                transport
            }

            "http", "h2" -> {
                val transport = JSONObject()
                    .put("type", "http")
                    .put("path", outbound.path ?: "/")
                outbound.hostHeader?.let {
                    transport.put("host", JSONArray().put(it))
                }
                transport
            }

            "httpupgrade" -> {
                val transport = JSONObject()
                    .put("type", "httpupgrade")
                    .put("path", outbound.path ?: "/")
                outbound.hostHeader?.let { transport.put("host", it) }
                transport
            }

            "quic" -> JSONObject().put("type", "quic")

            "xhttp", "splithttp" -> throw IllegalArgumentException(
                "VLESS transport '${outbound.transport}' is not supported by the packaged sing-box core. " +
                    "Use a VLESS tcp/ws/grpc/http/httpupgrade/quic profile, or package an XHTTP-capable core."
            )

            else -> throw IllegalArgumentException(
                "Unsupported VLESS transport '${outbound.transport}'. " +
                    "Supported transports: tcp, raw, ws, grpc, http, h2, httpupgrade, quic."
            )
        }
    }
}

private data class VlessOutbound(
    val uuid: String,
    val host: String,
    val port: Int,
    val security: String?,
    val flow: String?,
    val sni: String?,
    val fingerprint: String?,
    val publicKey: String?,
    val shortId: String?,
    val transport: String?,
    val path: String?,
    val hostHeader: String?,
    val serviceName: String?
)

private class SingBoxWireGuardEngine(
    private val context: Context,
    private val profile: VpnProfileConfig,
    override val socksPort: Int,
    private val username: String,
    private val password: String,
    private val log: (String) -> Unit
) : SocksBackedVpnEngine {
    override val profileType: String = profile.normalizedType
    override val socksHost: String = DEFAULT_SOCKS_HOST
    override val isRunning: Boolean
        get() = process?.isAlive == true && canConnect(socksHost, socksPort)

    private var process: Process? = null
    private var logThread: Thread? = null

    override suspend fun start() {
        val wireGuardConfig = resolveWireGuardConfig(profile)
        val outbound = parseWireGuardConfig(wireGuardConfig)
        val executableSource = findEngineExecutable(context, "sing-box")
            ?: throw IllegalStateException(
                "sing-box executable is missing. Put it at jniLibs/<abi>/libsing-box.so " +
                    "or assets/bin/<abi>/sing-box."
            )
        val workDir = File(context.noBackupFilesDir, "engines/sing-box/${executableSource.abi}").apply {
            mkdirs()
        }
        val executable = executableSource.file ?: File(workDir, "sing-box").also { target ->
            copyAsset(context, executableSource.assetPath.orEmpty(), target)
            target.setExecutable(true, false)
        }
        val configFile = File(workDir, "wireguard-$socksPort.json").also { file ->
            file.writeText(buildSingBoxConfig(outbound))
        }

        stop()
        process = ProcessBuilder(
            executable.absolutePath,
            "run",
            "-c",
            configFile.absolutePath
        )
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        logThread = process?.let { started ->
            thread(name = "SingBoxWireGuardLog", isDaemon = true) {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> log("sing-box: $line") }
                }
            }
        }

        waitForSocksPort()
        log("${profile.typeLabel()} ready on $socksHost:$socksPort")
    }

    override fun stop() {
        process?.destroy()
        process = null
        logThread?.interrupt()
        logThread = null
    }

    private suspend fun waitForSocksPort() {
        val deadline = System.currentTimeMillis() + SING_BOX_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val started = process
            if (started != null && !started.isAlive) {
                throw IllegalStateException("sing-box exited before SOCKS became ready")
            }
            if (canConnect(socksHost, socksPort)) return
            delay(SOCKS_READY_POLL_MS)
        }
        throw IllegalStateException("sing-box SOCKS port $socksPort did not become ready")
    }

    private fun buildSingBoxConfig(outbound: WireGuardOutbound): String {
        val inbound = buildSingBoxSocksInbound(socksHost, socksPort, username, password)

        val peer = JSONObject()
            .put("address", outbound.peerHost)
            .put("port", outbound.peerPort)
            .put("public_key", outbound.peerPublicKey)
            .put("allowed_ips", JSONArray(outbound.allowedIps))

        outbound.preSharedKey?.let { peer.put("pre_shared_key", it) }
        outbound.persistentKeepalive?.let { peer.put("persistent_keepalive_interval", it) }

        val endpoint = JSONObject()
            .put("type", "wireguard")
            .put("tag", "amnezia-wireguard")
            .put("address", JSONArray(outbound.addresses))
            .put("private_key", outbound.privateKey)
            .put("peers", JSONArray().put(peer))

        outbound.mtu?.let { endpoint.put("mtu", it) }
        if (outbound.amneziaWireGuardFields.isNotEmpty()) {
            endpoint.put(
                "amnezia_wg",
                JSONObject().also { amnezia ->
                    outbound.amneziaWireGuardFields.forEach { (key, value) ->
                        amnezia.put(key, value)
                    }
                }
            )
        }

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(inbound))
            .put("endpoints", JSONArray().put(endpoint))
            .put(
                "outbounds",
                JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct"))
            )
            .put("route", buildSingBoxRoute(finalOutbound = "amnezia-wireguard"))
            .toString(2)
    }
}

private data class WireGuardOutbound(
    val privateKey: String,
    val addresses: List<String>,
    val mtu: Int?,
    val peerPublicKey: String,
    val preSharedKey: String?,
    val peerHost: String,
    val peerPort: Int,
    val allowedIps: List<String>,
    val persistentKeepalive: Int?,
    val amneziaWireGuardFields: Map<String, Any> = emptyMap()
)

private fun resolveWireGuardConfig(profile: VpnProfileConfig): String {
    val candidates = listOfNotNull(profile.rawConfig, profile.uri)
        .map { it.trim() }
        .filter { it.isNotBlank() }

    candidates.forEach { candidate ->
        extractWireGuardConfig(candidate)?.let { return it }
    }

    throw IllegalStateException("${profile.typeLabel()} profile does not contain a WireGuard config")
}

private fun extractWireGuardConfig(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return null

    if (looksLikeWireGuardConfig(trimmed)) return trimmed

    val decoded = when {
        trimmed.startsWith("vpn://", ignoreCase = true) -> decodeCompressedVpnUri(trimmed, "vpn://")
        trimmed.startsWith("awg://", ignoreCase = true) -> decodeCompressedVpnUri(trimmed, "awg://")
        else -> null
    }

    if (!decoded.isNullOrBlank()) {
        extractWireGuardConfig(decoded)?.let { return it }
    }

    if (!trimmed.startsWith("{")) return null

    return runCatching {
        extractWireGuardConfig(JSONObject(trimmed))
    }.getOrNull()
}

private fun extractWireGuardConfig(json: JSONObject): String? {
    json.optString("config")
        .takeIf { looksLikeWireGuardConfig(it) }
        ?.let { return it }

    val containers = json.optJSONArray("containers")
    if (containers != null) {
        for (index in containers.length() - 1 downTo 0) {
            val container = containers.optJSONObject(index) ?: continue
            extractWireGuardConfigFromContainer(json, container)?.let { return it }
        }
    }

    json.keys().forEach { key ->
        val value = json.opt(key)
        when (value) {
            is JSONObject -> extractWireGuardConfig(value)?.let { return it }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val nested = value.opt(index)
                    when (nested) {
                        is JSONObject -> extractWireGuardConfig(nested)?.let { return it }
                        is String -> extractWireGuardConfig(nested)?.let { return it }
                    }
                }
            }
            is String -> extractWireGuardConfig(value)?.let { return it }
        }
    }

    return null
}

private fun extractWireGuardConfigFromContainer(root: JSONObject, container: JSONObject): String? {
    val protocolObjects = listOf("awg", "wireguard", "wireguard-go", "amneziawg")
        .mapNotNull { container.optJSONObject(it) }
    protocolObjects.forEach { protocol ->
        val lastConfigText = protocol.optString("last_config").takeIf { it.isNotBlank() }
        if (lastConfigText != null) {
            val lastConfig = runCatching { JSONObject(lastConfigText) }.getOrNull()
            val config = lastConfig?.optString("config")
                ?.replace("$PRIMARY_DNS", root.optString("dns1"))
                ?.replace("$SECONDARY_DNS", root.optString("dns2"))
            if (looksLikeWireGuardConfig(config.orEmpty())) {
                return config
            }
        }
        extractWireGuardConfig(protocol)?.let { return it }
    }

    return null
}

private fun decodeCompressedVpnUri(uri: String, prefix: String): String? {
    if (!uri.startsWith(prefix, ignoreCase = true)) return null

    val encoded = uri.substring(prefix.length)
        .substringBefore("#")
        .trim()
    if (encoded.isBlank()) return null

    return runCatching {
        val compressed = Base64.decode(
            encoded,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val zlibPayload = if (compressed.size > 4) compressed.copyOfRange(4, compressed.size) else compressed
        val inflater = Inflater()
        val output = ByteArrayOutputStream()
        try {
            inflater.setInput(zlibPayload)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    output.write(buffer, 0, count)
                }
            }
        } finally {
            inflater.end()
        }
        output.toString(Charsets.UTF_8.name())
    }.getOrNull()
}

private fun parseWireGuardConfig(config: String): WireGuardOutbound {
    val parsed = parseWireGuardIni(config)
    val interfaceConfig = parsed.interfaceConfig
    val peerConfig = parsed.peers.firstOrNull()
        ?: throw IllegalArgumentException("WireGuard peer section is missing")

    val amneziaWireGuardFields = AMNEZIA_WG_FIELDS
        .mapNotNull { (configKey, singBoxKey) ->
            parsed.allValues[configKey]
                ?.takeIf { it.isAwgParameterEnabled() }
                ?.let { singBoxKey to it.toSingBoxWireGuardValue(singBoxKey) }
        }
        .toMap()

    val endpoint = parseEndpoint(
        peerConfig["endpoint"]
            ?: throw IllegalArgumentException("WireGuard peer endpoint is missing")
    )

    return WireGuardOutbound(
        privateKey = interfaceConfig["privatekey"]
            ?: throw IllegalArgumentException("WireGuard private key is missing"),
        addresses = interfaceConfig["address"].orEmpty().splitCsv(),
        mtu = interfaceConfig["mtu"]?.toIntOrNull(),
        peerPublicKey = peerConfig["publickey"]
            ?: throw IllegalArgumentException("WireGuard peer public key is missing"),
        preSharedKey = peerConfig["presharedkey"]?.takeIf { it.isNotBlank() },
        peerHost = endpoint.host,
        peerPort = endpoint.port,
        allowedIps = peerConfig["allowedips"].orEmpty().splitCsv().ifEmpty { listOf("0.0.0.0/0") },
        persistentKeepalive = peerConfig["persistentkeepalive"]?.toIntOrNull(),
        amneziaWireGuardFields = amneziaWireGuardFields
    ).also {
        require(it.addresses.isNotEmpty()) { "WireGuard interface address is missing" }
    }
}

private data class ParsedWireGuardConfig(
    val interfaceConfig: Map<String, String>,
    val peers: List<Map<String, String>>,
    val allValues: Map<String, String>
)

private fun parseWireGuardIni(config: String): ParsedWireGuardConfig {
    val interfaceConfig = linkedMapOf<String, String>()
    val peers = mutableListOf<MutableMap<String, String>>()
    var currentSection: String? = null
    var currentValues: MutableMap<String, String>? = null
    val allValues = linkedMapOf<String, String>()

    config.lineSequence().forEach { rawLine ->
        val line = rawLine
            .substringBefore("#")
            .substringBefore(";")
            .trim()
        if (line.isBlank()) return@forEach

        if (line.startsWith("[") && line.endsWith("]")) {
            currentSection = line.removePrefix("[").removeSuffix("]").trim().lowercase()
            currentValues = when (currentSection) {
                "interface" -> interfaceConfig
                "peer" -> linkedMapOf<String, String>().also { peers += it }
                else -> null
            }
            return@forEach
        }

        val separator = line.indexOf('=')
        if (separator <= 0) return@forEach

        val key = line.substring(0, separator).trim().lowercase()
        val value = line.substring(separator + 1).trim()
        currentValues?.put(key, value)
        if (currentSection == "interface" || currentSection == "peer") {
            allValues[key] = value
        }
    }

    return ParsedWireGuardConfig(interfaceConfig, peers, allValues)
}

private data class HostPort(val host: String, val port: Int)

private fun parseEndpoint(endpoint: String): HostPort {
    val value = endpoint.trim()
    require(value.isNotBlank()) { "WireGuard endpoint is blank" }

    val host: String
    val portText: String
    if (value.startsWith("[")) {
        val closing = value.indexOf(']')
        require(closing > 0 && value.length > closing + 2 && value[closing + 1] == ':') {
            "Invalid WireGuard IPv6 endpoint"
        }
        host = value.substring(1, closing)
        portText = value.substring(closing + 2)
    } else {
        val separator = value.lastIndexOf(':')
        require(separator > 0) { "Invalid WireGuard endpoint" }
        host = value.substring(0, separator)
        portText = value.substring(separator + 1)
    }

    val port = portText.toIntOrNull()
        ?: throw IllegalArgumentException("Invalid WireGuard endpoint port")
    require(port in 1..65535) { "WireGuard endpoint port is out of range" }
    return HostPort(host = host, port = port)
}

private fun looksLikeWireGuardConfig(config: String): Boolean {
    val lower = config.lowercase()
    return lower.contains("[interface]") &&
        lower.contains("[peer]") &&
        lower.contains("privatekey") &&
        lower.contains("publickey")
}

private fun String.splitCsv(): List<String> {
    return split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun String.isAwgParameterEnabled(): Boolean {
    val normalized = trim().trim('"', '\'').lowercase()
    return normalized.isNotBlank() &&
        normalized != "0" &&
        normalized != "0-0" &&
        normalized != "false" &&
        normalized != "off"
}

private fun String.toSingBoxWireGuardValue(field: String): Any {
    val normalized = trim().trim('"', '\'')
    return if (field in AMNEZIA_WG_INTEGER_FIELDS) {
        normalized.toLongOrNull() ?: normalized
    } else {
        normalized
    }
}

private data class EngineExecutable(
    val abi: String,
    val file: File? = null,
    val assetPath: String? = null
)

private fun parseVlessUri(uri: String): VlessOutbound {
    val payload = uri.removePrefix("vless://")
    val withoutFragment = payload.substringBefore("#")
    val authority = withoutFragment.substringBefore("?")
    val query = withoutFragment.substringAfter("?", "")
    val at = authority.lastIndexOf('@')
    require(at > 0) { "Invalid VLESS URI: missing uuid or server" }

    val uuid = authority.substring(0, at).urlDecode()
    val hostPort = authority.substring(at + 1)
    val host: String
    val port: Int
    if (hostPort.startsWith("[")) {
        val end = hostPort.indexOf(']')
        require(end > 0) { "Invalid VLESS IPv6 host" }
        host = hostPort.substring(1, end)
        port = hostPort.substring(end + 1).removePrefix(":").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid VLESS port")
    } else {
        val separator = hostPort.lastIndexOf(':')
        require(separator > 0) { "Invalid VLESS host:port" }
        host = hostPort.substring(0, separator)
        port = hostPort.substring(separator + 1).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid VLESS port")
    }

    val params = parseQuery(query)
    return VlessOutbound(
        uuid = uuid,
        host = host.urlDecode(),
        port = port,
        security = params["security"]?.lowercase(),
        flow = params["flow"],
        sni = params["sni"] ?: params["serverName"],
        fingerprint = params["fp"],
        publicKey = params["pbk"] ?: params["publicKey"],
        shortId = params["sid"] ?: params["shortId"],
        transport = params["type"]?.lowercase(),
        path = params["path"],
        hostHeader = params["host"],
        serviceName = params["serviceName"] ?: params["service_name"]
    )
}

private fun parseQuery(query: String): Map<String, String> {
    if (query.isBlank()) return emptyMap()
    return query.split('&')
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            part.substring(0, separator).urlDecode() to
                part.substring(separator + 1).urlDecode()
        }
        .toMap()
}

private fun findEngineExecutable(context: Context, name: String): EngineExecutable? {
    val nativeDir = context.applicationInfo.nativeLibraryDir?.let { File(it) }
    if (nativeDir != null) {
        Build.SUPPORTED_ABIS.forEach { abi ->
            val nativeExecutable = File(nativeDir, "lib$name.so")
            if (nativeExecutable.isFile) {
                return EngineExecutable(abi = abi, file = nativeExecutable)
            }
        }
    }

    return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
        val path = "bin/$abi/$name"
        if (assetExists(context, path)) EngineExecutable(abi = abi, assetPath = path) else null
    }
}

private fun assetExists(context: Context, path: String): Boolean {
    return runCatching {
        context.assets.open(path).use { }
        true
    }.getOrDefault(false)
}

private fun copyAsset(context: Context, path: String, target: File) {
    context.assets.open(path).use { input ->
        target.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

private fun canConnect(host: String, port: Int): Boolean {
    if (port !in 1..65535) return false
    return runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
    }.isSuccess
}

private fun buildSingBoxSocksInbound(
    socksHost: String,
    socksPort: Int,
    username: String,
    password: String
): JSONObject {
    val inbound = JSONObject()
        .put("type", "socks")
        .put("tag", SING_BOX_LOCAL_SOCKS_TAG)
        .put("listen", socksHost)
        .put("listen_port", socksPort)

    if (username.isNotBlank()) {
        inbound.put(
            "users",
            JSONArray().put(
                JSONObject()
                    .put("username", username)
                    .put("password", password)
            )
        )
    }

    return inbound
}

private fun buildSingBoxRoute(finalOutbound: String): JSONObject {
    return JSONObject()
        .put(
            "rules",
            JSONArray().put(
                JSONObject()
                    .put("inbound", SING_BOX_LOCAL_SOCKS_TAG)
                    .put("action", "sniff")
            )
        )
        .put("final", finalOutbound)
}

private fun String.urlDecode(): String {
    return URLDecoder.decode(this, "UTF-8")
}

private const val DEFAULT_SOCKS_HOST = "127.0.0.1"
private const val SING_BOX_LOCAL_SOCKS_TAG = "local-socks"
private const val CONNECT_TIMEOUT_MS = 150
private const val SING_BOX_READY_TIMEOUT_MS = 8_000L
private const val SOCKS_READY_POLL_MS = 150L
private const val PRIMARY_DNS = "\$PRIMARY_DNS"
private const val SECONDARY_DNS = "\$SECONDARY_DNS"
private val AMNEZIA_WG_INTEGER_FIELDS = setOf(
    "jc", "jmin", "jmax", "s1", "s2", "s3", "s4"
)
private val AMNEZIA_WG_FIELDS = linkedMapOf(
    "jc" to "jc",
    "jmin" to "jmin",
    "jmax" to "jmax",
    "s1" to "s1",
    "s2" to "s2",
    "s3" to "s3",
    "s4" to "s4",
    "h1" to "h1",
    "h2" to "h2",
    "h3" to "h3",
    "h4" to "h4",
    "i1" to "i1",
    "i2" to "i2",
    "i3" to "i3",
    "i4" to "i4",
    "i5" to "i5",
    "headerprotectionkey" to "header_protection_key",
    "contentpaddingaddition" to "content_padding_addition",
    "rekeyaftertime" to "rekey_after_time",
    "rekeytimeout" to "rekey_timeout",
    "rejectaftertime" to "reject_after_time",
    "keepalivetimeout" to "keepalive_timeout",
    "maxhandshakeattempts" to "max_handshake_attempts"
)
