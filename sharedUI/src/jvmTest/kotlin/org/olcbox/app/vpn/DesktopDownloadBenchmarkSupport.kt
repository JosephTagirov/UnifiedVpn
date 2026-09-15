package org.olcbox.app.vpn

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal const val DOWNLOAD_BENCHMARK_GATE = "UNIFIEDVPN_RUN_DOWNLOAD_BENCHMARK"
internal const val DOWNLOAD_BENCHMARK_ROOT = "UNIFIEDVPN_DOWNLOAD_BENCHMARK_ROOT"
internal const val DOWNLOAD_BENCHMARK_MANIFEST = "UNIFIEDVPN_DOWNLOAD_BENCHMARK_MANIFEST"
internal const val DOWNLOAD_MAX_RUN_BYTES = 32L * 1024 * 1024
internal val DOWNLOAD_RESERVED_PORTS = setOf(10808, 18080)

internal enum class DownloadFailureStage(val code: String) {
    Import("import"), Configure("configure"), Connect("connect"), Ownership("ownership"),
    SocksAuth("socks_auth"), Download("download"), Stop("stop")
}

internal class DownloadRequestGate {
    private val requested = AtomicBoolean(false)
    fun requireFirstRequest() {
        check(requested.compareAndSet(false, true)) { "A benchmark sample must not retry its HTTPS request" }
    }
}

internal data class DownloadBenchmarkGate(val root: Path, val manifest: Path) {
    companion object {
        fun from(environment: Map<String, String>): DownloadBenchmarkGate? {
            if (environment[DOWNLOAD_BENCHMARK_GATE] != "1") return null
            fun absolute(name: String): Path {
                val value = environment[name]?.takeIf(String::isNotBlank)
                    ?: error("Explicit isolated benchmark paths are required")
                val path = Path.of(value)
                require(path.isAbsolute) { "Benchmark paths must be absolute" }
                return path.normalize()
            }
            val root = absolute(DOWNLOAD_BENCHMARK_ROOT)
            val manifest = absolute(DOWNLOAD_BENCHMARK_MANIFEST)
            require(manifest != root && manifest.startsWith(root)) { "Benchmark manifest must be inside the protected root" }
            require(absolute("UNIFIEDVPN_TEST_APPDATA_ROOT") == root)
            require(absolute("APPDATA") == root.resolve("Roaming"))
            require(absolute("LOCALAPPDATA") == root.resolve("Local"))
            return DownloadBenchmarkGate(root, manifest)
        }
    }
}

@Serializable
internal data class DownloadBenchmarkProfile(
    val engine: String,
    @SerialName("profile_file") val profileFile: String
) {
    fun resolve(root: Path): Path {
        require(profileFile.isNotBlank() && !profileFile.contains('\\'))
        val relative = Path.of(profileFile)
        require(!relative.isAbsolute && relative.none { it.toString() == ".." })
        val path = root.resolve(relative).normalize()
        require(path != root && path.startsWith(root)) { "Profile must remain in the protected benchmark root" }
        return path
    }
}

@Serializable
internal data class DownloadBenchmarkManifest(
    val version: Int = 1,
    val source: String,
    @SerialName("payload_seconds") val payloadSeconds: Int = 15,
    val profiles: List<DownloadBenchmarkProfile>
) {
    fun validate(): DownloadSource {
        require(version == 1) { "Unsupported benchmark manifest version" }
        require(payloadSeconds in 1..15) { "Payload duration must be between 1 and 15 seconds" }
        require(profiles.isNotEmpty() && profiles.size <= 4)
        val engines = profiles.map { it.engine }
        require(engines.size == engines.toSet().size) { "An engine may appear only once per benchmark run" }
        require(engines.all { it in setOf("olcrtc", "awg", "vless", "openflux") })
        val parsedSource = DownloadSource.parse(source)
        require(parsedSource.byteLimit * profiles.size <= DOWNLOAD_MAX_RUN_BYTES) {
            "Total requested payload must not exceed 32 MiB per run"
        }
        return parsedSource
    }

    companion object {
        fun parse(raw: String): DownloadBenchmarkManifest {
            require(raw.length <= 16_384) { "Benchmark manifest is too large" }
            return Json.decodeFromString<DownloadBenchmarkManifest>(raw).also { it.validate() }
        }
    }
}

internal data class DownloadSource(val url: String, val byteLimit: Long) {
    companion object {
        fun parse(raw: String): DownloadSource {
            require(raw.length <= 256)
            val uri = URI(raw)
            require(uri.scheme.equals("https", ignoreCase = true))
            require(uri.host.equals("speed.cloudflare.com", ignoreCase = true))
            require(uri.rawUserInfo == null && uri.rawFragment == null && uri.port in setOf(-1, 443))
            require(uri.rawPath == "/__down")
            val bytes = Regex("bytes=([1-9][0-9]{0,8})").matchEntire(uri.rawQuery.orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull()
                ?: error("Benchmark source requires one explicit byte count")
            require(bytes in 1..DOWNLOAD_MAX_RUN_BYTES) { "Download source exceeds the bounded payload budget" }
            return DownloadSource("https://speed.cloudflare.com/__down?bytes=$bytes", bytes)
        }
    }
}

internal fun downloadMegabitsPerSecond(payloadBytes: Long, elapsedNanos: Long): Double {
    require(payloadBytes >= 0 && elapsedNanos > 0)
    return payloadBytes.toDouble() * 8_000.0 / elapsedNanos.toDouble()
}

internal data class DownloadPayloadSample(val bytes: Long, val elapsedNanos: Long, val stopReason: String) {
    val megabitsPerSecond: Double get() = downloadMegabitsPerSecond(bytes, elapsedNanos)
}

internal fun requireUsableDownloadSample(sample: DownloadPayloadSample, requestedBytes: Long) {
    require(sample.bytes in 1..requestedBytes) { "No valid bounded download payload was received" }
    require(sample.stopReason == "time_cap" || sample.bytes == requestedBytes) {
        "Download source ended before delivering the requested payload"
    }
}

internal fun measureDownloadPayload(
    input: InputStream,
    byteLimit: Long,
    durationNanos: Long,
    startedAtNanos: Long,
    nanoTime: () -> Long = System::nanoTime,
    healthCheck: () -> Unit = {}
): DownloadPayloadSample {
    require(byteLimit in 1..DOWNLOAD_MAX_RUN_BYTES && durationNanos in 1..15_000_000_000L)
    val buffer = ByteArray(32 * 1024)
    var received = 0L
    var reason = "byte_cap"
    while (received < byteLimit) {
        healthCheck()
        if (nanoTime() - startedAtNanos >= durationNanos) {
            reason = "time_cap"
            break
        }
        val count = try {
            input.read(buffer, 0, minOf(buffer.size.toLong(), byteLimit - received).toInt())
        } catch (timeout: InterruptedIOException) {
            healthCheck()
            if (nanoTime() - startedAtNanos < durationNanos) throw timeout
            reason = "time_cap"
            break
        }
        if (count < 0) {
            reason = "end_of_body"
            break
        }
        require(count in 1..minOf(buffer.size.toLong(), byteLimit - received).toInt()) {
            "Payload reader did not make valid bounded progress"
        }
        received += count
    }
    healthCheck()
    return DownloadPayloadSample(received, (nanoTime() - startedAtNanos).coerceAtLeast(1L), reason)
}
