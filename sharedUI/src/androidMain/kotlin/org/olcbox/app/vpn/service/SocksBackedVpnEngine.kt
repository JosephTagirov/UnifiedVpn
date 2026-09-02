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
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
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

internal fun createTunSocksBridge(
    context: Context,
    profileType: String,
    upstreamSocksHost: String,
    upstreamSocksPort: Int,
    bridgeSocksPort: Int,
    username: String,
    password: String,
    log: (String) -> Unit
): SocksBackedVpnEngine = SingBoxTunSocksBridge(
    context = context.applicationContext,
    profileType = profileType,
    upstreamSocksHost = upstreamSocksHost,
    upstreamSocksPort = upstreamSocksPort,
    socksPort = bridgeSocksPort,
    username = username,
    password = password,
    log = log
)

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

private class SingBoxTunSocksBridge(
    private val context: Context,
    override val profileType: String,
    private val upstreamSocksHost: String,
    private val upstreamSocksPort: Int,
    override val socksPort: Int,
    private val username: String,
    private val password: String,
    private val log: (String) -> Unit
) : SocksBackedVpnEngine {
    override val socksHost: String = DEFAULT_SOCKS_HOST
    override val isRunning: Boolean
        get() = process?.isAlive == true && canConnect(socksHost, socksPort)

    private var process: Process? = null
    private var logThread: Thread? = null

    override suspend fun start() {
        require(upstreamSocksPort in 1..65535) { "Invalid olcRTC SOCKS port" }
        require(socksPort in 1..65535 && socksPort != upstreamSocksPort) {
            "Invalid olcRTC bridge SOCKS port"
        }

        val executableSource = findEngineExecutable(context, "sing-box")
            ?: throw IllegalStateException(
                "sing-box executable is missing. Put it at jniLibs/<abi>/libsing-box.so " +
                    "or assets/bin/<abi>/sing-box."
            )
        val workDir = File(
            context.noBackupFilesDir,
            "engines/sing-box/${executableSource.abi}"
        ).apply { mkdirs() }
        val executable = executableSource.file ?: File(workDir, "sing-box").also { target ->
            copyAsset(context, executableSource.assetPath.orEmpty(), target)
            target.setExecutable(true, false)
        }
        val configFile = File(workDir, "tun-bridge-$socksPort.json").also { file ->
            file.writeText(buildTunBridgeConfig().toString())
        }

        stop()
        val started = ProcessBuilder(
            executable.absolutePath,
            "run",
            "-c",
            configFile.absolutePath
        )
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        process = started
        logThread = startEngineLogReader(
            threadName = "SingBoxTunBridgeLog",
            logPrefix = "TUN bridge",
            process = started,
            log = log
        )

        val deadline = System.currentTimeMillis() + SING_BOX_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive) {
                throw IllegalStateException("TUN DNS bridge exited before SOCKS became ready")
            }
            if (canConnect(socksHost, socksPort)) {
                log("TUN DNS bridge ready on $socksHost:$socksPort")
                return
            }
            delay(SOCKS_READY_POLL_MS)
        }
        stop()
        throw IllegalStateException("TUN DNS bridge did not open SOCKS port $socksPort")
    }

    override fun stop() {
        val running = process
        process = null
        running?.destroy()
        if (running?.isAlive == true) {
            runCatching {
                if (!running.waitFor(ENGINE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    running.destroyForcibly()
                    running.waitFor(ENGINE_FORCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
        logThread?.interrupt()
        logThread = null
    }

    private fun buildTunBridgeConfig(): JSONObject {
        val inbound = buildSingBoxSocksInbound(
            socksHost = socksHost,
            socksPort = socksPort,
            username = username,
            password = password
        ).put("tag", SING_BOX_TUN_BRIDGE_INBOUND_TAG)

        val outbound = JSONObject()
            .put("type", "socks")
            .put("tag", SING_BOX_TUN_BRIDGE_OUTBOUND_TAG)
            .put("server", upstreamSocksHost)
            .put("server_port", upstreamSocksPort)
            .put("version", "5")
            .also { socks ->
                if (username.isNotBlank()) {
                    socks.put("username", username)
                    socks.put("password", password)
                }
            }

        val dnsServer = JSONObject()
            .put("type", "https")
            .put("tag", SING_BOX_TUN_BRIDGE_DNS_TAG)
            .put("server", "8.8.8.8")
            .put("server_port", 443)
            .put("path", "/dns-query")
            .put(
                "tls",
                JSONObject()
                    .put("enabled", true)
                    .put("server_name", "dns.google")
            )
            .put("detour", SING_BOX_TUN_BRIDGE_OUTBOUND_TAG)

        val routeRules = JSONArray()
            .put(
                JSONObject()
                    .put("inbound", SING_BOX_TUN_BRIDGE_INBOUND_TAG)
                    .put("port", 53)
                    .put("action", "hijack-dns")
            )

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put(
                "dns",
                JSONObject()
                    .put("servers", JSONArray().put(dnsServer))
                    .put("final", SING_BOX_TUN_BRIDGE_DNS_TAG)
                    .put("strategy", "ipv4_only")
            )
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound))
            .put(
                "route",
                JSONObject()
                    .put("rules", routeRules)
                    .put("final", SING_BOX_TUN_BRIDGE_OUTBOUND_TAG)
            )
    }
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
    private var activeEngineName: String = "sing-box"

    override suspend fun start() {
        val uri = profile.rawConfig?.takeIf { it.startsWith("vless://", ignoreCase = true) }
            ?: profile.uri?.takeIf { it.startsWith("vless://", ignoreCase = true) }
            ?: throw IllegalStateException("VLESS profile does not contain a vless:// URI")
        val outbound = parseVlessUri(uri)
        val useXray = outbound.transport in XRAY_VLESS_TRANSPORTS
        val engineName = if (useXray) "xray" else "sing-box"
        val executableSource = findEngineExecutable(context, engineName)
            ?: throw IllegalStateException(
                "$engineName executable is missing. Put it at jniLibs/<abi>/lib$engineName.so " +
                    "or assets/bin/<abi>/$engineName."
            )
        val workDir = File(context.noBackupFilesDir, "engines/$engineName/${executableSource.abi}").apply {
            mkdirs()
        }
        val executable = executableSource.file ?: File(workDir, engineName).also { target ->
            copyAsset(context, executableSource.assetPath.orEmpty(), target)
            target.setExecutable(true, false)
        }
        val configFile = File(workDir, "vless-$socksPort.json").also { file ->
            file.writeText(if (useXray) buildXrayConfig(outbound) else buildSingBoxConfig(outbound))
        }

        stop()
        activeEngineName = engineName
        val command = if (useXray) {
            listOf(executable.absolutePath, "run", "-config", configFile.absolutePath)
        } else {
            listOf(executable.absolutePath, "run", "-c", configFile.absolutePath)
        }
        process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        logThread = process?.let { started ->
            startEngineLogReader(
                threadName = if (useXray) "XrayVlessLog" else "SingBoxVlessLog",
                logPrefix = engineName,
                process = started,
                log = log
            )
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
                throw IllegalStateException("$activeEngineName exited before SOCKS became ready")
            }
            if (canConnect(socksHost, socksPort)) return
            delay(SOCKS_READY_POLL_MS)
        }
        throw IllegalStateException("$activeEngineName SOCKS port $socksPort did not become ready")
    }

    private fun buildXrayConfig(outbound: VlessOutbound): String {
        val inboundSettings = JSONObject().put("udp", true)
        if (username.isBlank()) {
            inboundSettings.put("auth", "noauth")
        } else {
            inboundSettings
                .put("auth", "password")
                .put(
                    "accounts",
                    JSONArray().put(
                        JSONObject()
                            .put("user", username)
                            .put("pass", password)
                    )
                )
        }
        val inbound = JSONObject()
            .put("listen", socksHost)
            .put("port", socksPort)
            .put("protocol", "socks")
            .put("settings", inboundSettings)
        val user = JSONObject()
            .put("id", outbound.uuid)
            .put("encryption", outbound.encryption ?: "none")
        outbound.flow?.let { user.put("flow", it) }
        val streamSettings = JSONObject()
            .put("network", "xhttp")
            .put("security", outbound.security ?: "none")
            .put(
                "xhttpSettings",
                JSONObject()
                    .put("path", outbound.path ?: "/")
                    .also { settings ->
                        outbound.hostHeader?.let { settings.put("host", it) }
                        outbound.mode?.let { settings.put("mode", it) }
                    }
            )

        when (outbound.security) {
            "reality" -> streamSettings.put(
                "realitySettings",
                JSONObject()
                    .put("show", false)
                    .also { reality ->
                        outbound.sni?.let { reality.put("serverName", it) }
                        outbound.fingerprint?.let { reality.put("fingerprint", it) }
                        outbound.publicKey?.let { reality.put("publicKey", it) }
                        outbound.shortId?.let { reality.put("shortId", it) }
                        outbound.spiderX?.let { reality.put("spiderX", it) }
                    }
            )
            "tls" -> streamSettings.put(
                "tlsSettings",
                JSONObject().also { tls ->
                    outbound.sni?.let { tls.put("serverName", it) }
                    outbound.fingerprint?.let { tls.put("fingerprint", it) }
                    outbound.alpn
                        ?.takeIf(List<String>::isNotEmpty)
                        ?.let { tls.put("alpn", JSONArray(it)) }
                    outbound.allowInsecure?.let { tls.put("allowInsecure", it) }
                }
            )
        }
        val proxy = JSONObject()
            .put("protocol", "vless")
            .put("tag", "proxy")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", outbound.host)
                            .put("port", outbound.port)
                            .put("users", JSONArray().put(user))
                    )
                )
            )
            .put("streamSettings", streamSettings)

        return JSONObject()
            .put(
                "log",
                JSONObject()
                    .put("loglevel", "warning")
                    .put("dnsLog", false)
            )
            .put("inbounds", JSONArray().put(inbound))
            .put(
                "outbounds",
                JSONArray()
                    .put(proxy)
                    .put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
            )
            .toString(2)
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
    val encryption: String?,
    val security: String?,
    val flow: String?,
    val sni: String?,
    val fingerprint: String?,
    val publicKey: String?,
    val shortId: String?,
    val transport: String?,
    val path: String?,
    val hostHeader: String?,
    val serviceName: String?,
    val mode: String?,
    val spiderX: String?,
    val alpn: List<String>?,
    val allowInsecure: Boolean?
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
            startEngineLogReader("SingBoxWireGuardLog", "sing-box", started, log)
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
            .put("domain_resolver", SING_BOX_BOOTSTRAP_DNS_TAG)
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
            .put("dns", buildSingBoxDns(outbound.dnsServers, outbound.addresses))
            .put("inbounds", JSONArray().put(inbound))
            .put("endpoints", JSONArray().put(endpoint))
            .put(
                "outbounds",
                JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct"))
            )
            .put(
                "route",
                buildSingBoxRoute(
                    finalOutbound = "amnezia-wireguard",
                    defaultDomainResolver = "${SING_BOX_TUNNEL_DNS_TAG_PREFIX}0",
                    hijackDns = true
                )
            )
            .toString(2)
    }

    private fun buildSingBoxDns(
        configuredServers: List<String>,
        interfaceAddresses: List<String>
    ): JSONObject {
        val tunnelServers = configuredServers
            .mapNotNull(::parseDnsEndpoint)
            .distinct()
            .take(MAX_SING_BOX_DNS_SERVERS)
            .ifEmpty { DEFAULT_SING_BOX_DNS_SERVERS.mapNotNull(::parseDnsEndpoint) }
        val bootstrap = DEFAULT_SING_BOX_DNS_SERVERS.first().let(::parseDnsEndpoint)
            ?: error("Invalid built-in DNS endpoint")
        val servers = JSONArray().put(
            JSONObject()
                .put("type", "udp")
                .put("tag", SING_BOX_BOOTSTRAP_DNS_TAG)
                .put("server", bootstrap.host)
                .put("server_port", bootstrap.port)
        )
        tunnelServers.forEachIndexed { index, server ->
            servers.put(
                JSONObject()
                    .put("type", "tcp")
                    .put("tag", "$SING_BOX_TUNNEL_DNS_TAG_PREFIX$index")
                    .put("server", server.host)
                    .put("server_port", server.port)
                    .put("detour", SING_BOX_AMNEZIA_WIREGUARD_TAG)
            )
        }
        return JSONObject()
            .put("servers", servers)
            .put("final", "${SING_BOX_TUNNEL_DNS_TAG_PREFIX}0")
            .put("strategy", singBoxDnsStrategy(interfaceAddresses))
    }
}

private data class WireGuardOutbound(
    val privateKey: String,
    val addresses: List<String>,
    val dnsServers: List<String>,
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
                    require(output.size() + count <= MAX_DECOMPRESSED_PROFILE_SIZE) {
                        "Compressed VPN profile is too large"
                    }
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
        dnsServers = interfaceConfig["dns"].orEmpty().splitCsv(),
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

private fun parseDnsEndpoint(endpoint: String): HostPort? {
    val value = endpoint.trim()
    if (value.isBlank() || value.startsWith('$')) return null
    if (value.startsWith("[")) {
        val closing = value.indexOf(']')
        if (closing <= 0) return null
        val host = value.substring(1, closing)
        val port = value.substring(closing + 1).removePrefix(":").toIntOrNull() ?: 53
        return HostPort(host, port).takeIf { it.port in 1..65535 && it.host.isIpLiteral() }
    }
    if (value.count { it == ':' } > 1) return HostPort(value, 53).takeIf { it.host.isIpLiteral() }
    val separator = value.lastIndexOf(':')
    if (separator <= 0) return HostPort(value, 53).takeIf { it.host.isIpLiteral() }
    val port = value.substring(separator + 1).toIntOrNull() ?: return null
    return HostPort(value.substring(0, separator), port)
        .takeIf { it.port in 1..65535 && it.host.isIpLiteral() }
}

private fun String.isIpLiteral(): Boolean {
    if (':' in this) {
        return isNotBlank() && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it in ":.%" }
    }
    val octets = split('.')
    return octets.size == 4 && octets.all { octet ->
        val number = octet.toIntOrNull()
        octet.isNotEmpty() && octet.length <= 3 && number != null && number in 0..255
    }
}

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
    return WIREGUARD_INTERFACE_SECTION.containsMatchIn(config) &&
        WIREGUARD_PEER_SECTION.containsMatchIn(config) &&
        WIREGUARD_PRIVATE_KEY.containsMatchIn(config) &&
        WIREGUARD_PUBLIC_KEY.containsMatchIn(config)
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

private fun startEngineLogReader(
    threadName: String,
    logPrefix: String,
    process: Process,
    log: (String) -> Unit
): Thread = thread(name = threadName, isDaemon = true) {
    try {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> log("$logPrefix: $line") }
        }
    } catch (exception: IOException) {
        if (process.isAlive && !Thread.currentThread().isInterrupted) {
            log("$logPrefix log reader failed: ${exception.message ?: exception::class.simpleName}")
        }
    }
}

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
        encryption = params["encryption"],
        security = params["security"]?.lowercase(),
        flow = params["flow"],
        sni = params["sni"] ?: params["serverName"],
        fingerprint = params["fp"],
        publicKey = params["pbk"] ?: params["publicKey"],
        shortId = params["sid"] ?: params["shortId"],
        transport = params["type"]?.lowercase(),
        path = params["path"],
        hostHeader = params["host"],
        serviceName = params["serviceName"] ?: params["service_name"],
        mode = params["mode"],
        spiderX = params["spx"] ?: params["spiderX"],
        alpn = params["alpn"]?.split(',')?.map(String::trim)?.filter(String::isNotBlank),
        allowInsecure = params["allowInsecure"]?.toBooleanStrictOrNull()
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

private fun buildSingBoxRoute(
    finalOutbound: String,
    defaultDomainResolver: String? = null,
    hijackDns: Boolean = false
): JSONObject {
    val rules = JSONArray().put(
        JSONObject()
            .put("inbound", SING_BOX_LOCAL_SOCKS_TAG)
            .put("action", "sniff")
    )
    if (hijackDns) {
        rules.put(
            JSONObject()
                .put("protocol", "dns")
                .put("action", "hijack-dns")
        )
    }
    return JSONObject()
        .put("rules", rules)
        .also { route ->
            defaultDomainResolver?.let { route.put("default_domain_resolver", it) }
        }
        .put("final", finalOutbound)
}

private fun singBoxDnsStrategy(interfaceAddresses: List<String>): String {
    val hosts = interfaceAddresses.map { it.substringBefore('/').trim() }
    val hasIpv4 = hosts.any { '.' in it }
    val hasIpv6 = hosts.any { ':' in it }
    return when {
        hasIpv4 && hasIpv6 -> "prefer_ipv4"
        hasIpv6 -> "ipv6_only"
        else -> "ipv4_only"
    }
}

private fun String.urlDecode(): String {
    return URLDecoder.decode(this, "UTF-8")
}

private const val DEFAULT_SOCKS_HOST = "127.0.0.1"
private const val SING_BOX_LOCAL_SOCKS_TAG = "local-socks"
private const val SING_BOX_AMNEZIA_WIREGUARD_TAG = "amnezia-wireguard"
private const val SING_BOX_BOOTSTRAP_DNS_TAG = "dns-bootstrap"
private const val SING_BOX_TUNNEL_DNS_TAG_PREFIX = "dns-tunnel-"
private const val SING_BOX_TUN_BRIDGE_INBOUND_TAG = "tun-bridge-in"
private const val SING_BOX_TUN_BRIDGE_OUTBOUND_TAG = "tun-upstream"
private const val SING_BOX_TUN_BRIDGE_DNS_TAG = "tun-dns"
private const val MAX_SING_BOX_DNS_SERVERS = 3
private val DEFAULT_SING_BOX_DNS_SERVERS = listOf("1.1.1.1:53", "8.8.8.8:53")
private const val CONNECT_TIMEOUT_MS = 150
private const val SING_BOX_READY_TIMEOUT_MS = 8_000L
private const val SOCKS_READY_POLL_MS = 150L
private const val ENGINE_STOP_TIMEOUT_MS = 1_500L
private const val ENGINE_FORCE_STOP_TIMEOUT_MS = 1_000L
private const val MAX_DECOMPRESSED_PROFILE_SIZE = 4 * 1024 * 1024
private val XRAY_VLESS_TRANSPORTS = setOf("xhttp", "splithttp")
private const val PRIMARY_DNS = "\$PRIMARY_DNS"
private const val SECONDARY_DNS = "\$SECONDARY_DNS"
private val WIREGUARD_INTERFACE_SECTION = Regex(
    """^\s*\[\s*interface\s*]\s*$""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
)
private val WIREGUARD_PEER_SECTION = Regex(
    """^\s*\[\s*peer\s*]\s*$""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
)
private val WIREGUARD_PRIVATE_KEY = Regex(
    """^\s*privatekey\s*=""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
)
private val WIREGUARD_PUBLIC_KEY = Regex(
    """^\s*publickey\s*=""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
)
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
