package org.olcbox.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.vpn.desktop.DesktopXrayConfig
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopDownloadBenchmarkIntegrationTest {
    @Test
    fun realWindowsProfilesMeasureBoundedDownloadThroughput() = runBlocking {
        val gate = DownloadBenchmarkGate.from(System.getenv()) ?: return@runBlocking
        require(System.getProperty("os.name").contains("windows", ignoreCase = true))
        require(System.getenv("OLCBOX_OLCRTC_EXE").isNullOrBlank()) {
            "Use OLCRTC_BINARY so the benchmark runs an isolated native copy"
        }
        require(Files.isDirectory(gate.root, LinkOption.NOFOLLOW_LINKS))
        val manifestPath = requirePrivateFile(gate.root, gate.manifest, 16_384)
        val manifest = DownloadBenchmarkManifest.parse(Files.readString(manifestPath))
        val source = manifest.validate()
        val profiles = manifest.profiles.map { profile ->
            profile to requirePrivateFile(gate.root, profile.resolve(gate.root), 1_048_576)
        }
        val results = mutableListOf<DownloadBenchmarkResult>()
        try {
            for ((profile, path) in profiles) {
                val result = measureProfile(gate.root, profile.engine, path, source, manifest.payloadSeconds)
                results += result
                if (result.failureStage == DownloadFailureStage.Stop.code) break
            }
        } finally {
            val report = Json { encodeDefaults = true }.encodeToString(results)
            Files.writeString(Files.createTempFile(gate.root, "download-results-", ".json"), report)
            println("DOWNLOAD_BENCHMARK_RESULTS=$report")
        }
        assertTrue(results.all { it.status == "ok" }, "One or more bounded download samples failed")
    }

    private suspend fun measureProfile(
        root: Path,
        engine: String,
        profilePath: Path,
        source: DownloadSource,
        payloadSeconds: Int
    ): DownloadBenchmarkResult {
        val dataDirectory = Files.createTempDirectory(root, "download-profile-")
        val repository = LocationsRepositoryImpl(JvmLocationsDataSourceImpl(dataDirectory))
        val manager = DesktopVpnManager(repository)
        var ownedProcess: ProcessHandle? = null
        var proxy: SubscriptionFetchProxy? = null
        val childPidsBefore = currentChildren().map { it.pid() }.toSet()
        var stage = DownloadFailureStage.Import
        var result: DownloadBenchmarkResult? = null
        try {
            require(repository.importText(Files.readString(profilePath))) { "Explicit benchmark profile import failed" }
            val active = repository.getActiveLocation() ?: error("Benchmark profile is missing")
            require(repository.getAllLocations().size == 1) { "Each benchmark file must contain exactly one profile" }
            stage = DownloadFailureStage.Configure
            require(active.profile.localSocksPort == null) { "Existing SOCKS references cannot be benchmarked" }
            require(expectedProfile(engine, active.profile)) { "Benchmark profile does not match its engine" }
            if (engine == "vless") {
                require(DesktopXrayConfig.supports(active.profile)) { "Desktop VLESS requires XHTTP or splithttp" }
            }
            manager.updateSocksProxySettings(DesktopSocksProxySettings(
                port = unusedLoopbackPort(),
                username = UUID.randomUUID().toString(),
                password = UUID.randomUUID().toString(),
                routingMode = DesktopRoutingMode.LocalSocks,
                externalRoutingMode = DesktopRoutingMode.LocalSocks
            ))
            stage = DownloadFailureStage.Connect
            manager.startVpn()
            val status = withTimeout(100_000L) {
                manager.status.first { it is VpnStatus.Connected || it is VpnStatus.Error }
            }
            require(status is VpnStatus.Connected) { "Benchmark engine did not become ready" }
            if (engine == "openflux") {
                require(manager.logs.value.contains("OpenFlux: OPENFLUX_READY")) { "Authenticated OpenFlux Ready is missing" }
            }
            stage = DownloadFailureStage.Ownership
            val endpoint = manager.subscriptionFetchProxy() ?: error("Owned benchmark SOCKS endpoint is missing")
            proxy = endpoint
            require(endpoint.host == "127.0.0.1" && endpoint.port !in DOWNLOAD_RESERVED_PORTS)
            val expectedBinary = root.resolve("Roaming/Olcbox/bin/${nativeFile(engine)}")
            val native = currentChildren().singleOrNull { child ->
                child.pid() !in childPidsBefore && child.isAlive && child.info().command().orElse(null)
                    ?.let(Path::of)?.toAbsolutePath()?.normalize() == expectedBinary
            } ?: error("Cannot verify the benchmark-owned native process")
            ownedProcess = native
            val health = {
                check(native.isAlive && manager.status.value is VpnStatus.Connected &&
                    manager.subscriptionFetchProxy() == endpoint) { "Benchmark engine lost readiness or ownership" }
            }
            health()
            stage = DownloadFailureStage.SocksAuth
            verifySocksCredentials(endpoint)
            health()
            stage = DownloadFailureStage.Download
            val sample = download(endpoint, source, payloadSeconds, health)
            health()
            requireUsableDownloadSample(sample, source.byteLimit)
            result = DownloadBenchmarkResult(
                engine = engine,
                status = "ok",
                source = source.url,
                payloadBytes = sample.bytes,
                elapsedSeconds = sample.elapsedNanos / 1_000_000_000.0,
                megabitsPerSecond = sample.megabitsPerSecond,
                stopReason = sample.stopReason
            )
        } catch (failure: Exception) {
            result = benchmarkFailure(engine, source.url, stage, failure)
        } finally {
            try {
                manager.close()
                withTimeout(15_000L) {
                    while (ownedProcess?.isAlive == true) delay(100L)
                }
                check(manager.status.value is VpnStatus.Disconnected) { "Benchmark manager did not stop; refusing another engine" }
                val remaining = currentChildren().filter { it.pid() !in childPidsBefore && it.isAlive &&
                    it.info().command().orElse("").endsWith(nativeFile(engine), ignoreCase = true) }
                check(remaining.isEmpty()) { "Owned native child remains alive; refusing another benchmark engine" }
                proxy?.let { endpoint ->
                    val refused = Socket().use { socket ->
                        runCatching { socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 1_000) }
                            .exceptionOrNull() is ConnectException
                    }
                    check(refused) { "Owned SOCKS listener remains open; refusing another benchmark engine" }
                }
                val runtime = root.resolve("Roaming/Olcbox/runtime")
                if (Files.isDirectory(runtime)) {
                    Files.list(runtime).use { check(it.findAny().isEmpty) { "Benchmark runtime config remains after stop" } }
                }
                check(dataDirectory.toAbsolutePath().normalize().startsWith(root))
                Files.walk(dataDirectory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            } catch (failure: Exception) {
                result = benchmarkFailure(engine, source.url, DownloadFailureStage.Stop, failure)
            }
        }
        return requireNotNull(result)
    }

    private suspend fun download(
        proxy: SubscriptionFetchProxy,
        source: DownloadSource,
        payloadSeconds: Int,
        health: () -> Unit
    ): DownloadPayloadSample = coroutineScope {
        val requestGate = DownloadRequestGate()
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port)))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                requestGate.requireFirstRequest()
                chain.proceed(chain.request())
            }
            .build()
        val call = client.newCall(Request.Builder().url(source.url)
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "UnifiedVPN-bounded-download-test")
            .build())
        val healthMonitor = launch(Dispatchers.Default) {
            while (isActive) {
                try { health() } catch (failure: Exception) {
                    call.cancel()
                    throw failure
                }
                delay(100L)
            }
        }
        try {
            withProxyAuthentication(proxy) {
                withContext(Dispatchers.IO) {
                    call.execute().use { response ->
                        check(response.code == 200) { "Download endpoint did not return HTTP 200" }
                        check(response.handshake != null) { "Verified HTTPS handshake is missing" }
                        check(response.header("Content-Encoding").let { it == null || it.equals("identity", ignoreCase = true) }) {
                            "Encoded payload cannot be used for the benchmark"
                        }
                        val body = response.body ?: error("Download response has no payload")
                        check(body.contentLength() == -1L || body.contentLength() == source.byteLimit) {
                            "Download source did not honor the requested payload size"
                        }
                        health()
                        val started = System.nanoTime()
                        val duration = TimeUnit.SECONDS.toNanos(payloadSeconds.toLong())
                        body.source().timeout().deadlineNanoTime(started + duration)
                        measureDownloadPayload(body.byteStream(), source.byteLimit, duration, started, healthCheck = health)
                    }
                }
            }
        } finally {
            call.cancel()
            healthMonitor.cancelAndJoin()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun requirePrivateFile(root: Path, path: Path, maxBytes: Long): Path {
        require(path.startsWith(root) && path != root)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) in 1..maxBytes)
        require(path.toRealPath().startsWith(root.toRealPath())) { "Benchmark file must remain inside its protected root" }
        return path
    }

    private fun currentChildren(): List<ProcessHandle> = ProcessHandle.current().children().use { it.toList() }

    private fun verifySocksCredentials(proxy: SubscriptionFetchProxy) {
        val username = proxy.username.toByteArray(StandardCharsets.UTF_8)
        val password = proxy.password.toByteArray(StandardCharsets.UTF_8)
        require(username.size in 1..255 && password.size in 1..255)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(proxy.host, proxy.port), 5_000)
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 2))
            output.flush()
            check(input.readNBytes(2).contentEquals(byteArrayOf(5, 2))) { "Owned SOCKS listener did not negotiate authentication" }
            output.write(byteArrayOf(1, username.size.toByte()) + username + byteArrayOf(password.size.toByte()) + password)
            output.flush()
            check(input.readNBytes(2).contentEquals(byteArrayOf(1, 0))) { "Owned SOCKS listener rejected benchmark credentials" }
        }
    }

    private fun unusedLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use {
        it.localPort.also { port -> require(port !in DOWNLOAD_RESERVED_PORTS) }
    }

    private fun expectedProfile(engine: String, profile: VpnProfileConfig): Boolean = when (engine) {
        "olcrtc" -> profile.isOlcRtc()
        "awg" -> profile.normalizedType in setOf(VpnProfileConfig.TYPE_AMNEZIA_WG, VpnProfileConfig.TYPE_AMNEZIA_VPN)
        "vless" -> profile.normalizedType == VpnProfileConfig.TYPE_VLESS
        "openflux" -> profile.isOpenFlux()
        else -> false
    }

    private fun nativeFile(engine: String): String = when (engine) {
        "olcrtc" -> "olcrtc-windows-amd64.exe"
        "awg" -> "sing-box-awg-windows-amd64.exe"
        "vless" -> "xray-windows-amd64.exe"
        "openflux" -> "openflux-windows-amd64.exe"
        else -> error("Unsupported benchmark engine")
    }
}

internal fun benchmarkFailure(engine: String, source: String, stage: DownloadFailureStage, failure: Exception): DownloadBenchmarkResult =
    DownloadBenchmarkResult(engine, "failed", source, failureStage = stage.code, failureType = failure.javaClass.simpleName)

@Serializable
internal data class DownloadBenchmarkResult(
    val engine: String,
    val status: String,
    val source: String,
    val direction: String = "download",
    @SerialName("payload_bytes") val payloadBytes: Long? = null,
    @SerialName("elapsed_seconds") val elapsedSeconds: Double? = null,
    @SerialName("megabits_per_second") val megabitsPerSecond: Double? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("failure_stage") val failureStage: String? = null,
    @SerialName("failure_type") val failureType: String? = null
)
