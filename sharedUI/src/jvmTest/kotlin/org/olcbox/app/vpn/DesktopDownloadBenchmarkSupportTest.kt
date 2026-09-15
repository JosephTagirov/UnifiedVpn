package org.olcbox.app.vpn

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopDownloadBenchmarkSupportTest {
    private val root = Path.of(System.getProperty("java.io.tmpdir"), "unifiedvpn-benchmark-fixture").toAbsolutePath().normalize()

    @Test fun missingGateDoesNotRequireOrReadPrivatePaths() {
        assertNull(DownloadBenchmarkGate.from(emptyMap()))
        assertNull(DownloadBenchmarkGate.from(mapOf(DOWNLOAD_BENCHMARK_GATE to "true")))
    }

    @Test fun explicitGateRequiresAllIsolatedPaths() {
        assertFails { DownloadBenchmarkGate.from(mapOf(DOWNLOAD_BENCHMARK_GATE to "1")) }
        val gate = assertNotNull(DownloadBenchmarkGate.from(environment()))
        assertEquals(root, gate.root)
        assertEquals(root.resolve("benchmark.json"), gate.manifest)
    }

    @Test fun gateRejectsOutsideManifestAndNormalAppData() {
        assertFails { DownloadBenchmarkGate.from(environment() + (DOWNLOAD_BENCHMARK_MANIFEST to root.parent.resolve("outside.json").toString())) }
        assertFails { DownloadBenchmarkGate.from(environment() + ("APPDATA" to root.parent.toString())) }
        assertFails { DownloadBenchmarkGate.from(environment() + ("LOCALAPPDATA" to root.parent.toString())) }
        assertFails { DownloadBenchmarkGate.from(environment() + ("UNIFIEDVPN_TEST_APPDATA_ROOT" to root.parent.toString())) }
    }

    @Test fun gateRejectsRelativePaths() {
        assertFails { DownloadBenchmarkGate.from(environment() + (DOWNLOAD_BENCHMARK_ROOT to "relative-root")) }
        assertFails { DownloadBenchmarkGate.from(environment() + (DOWNLOAD_BENCHMARK_MANIFEST to "benchmark.json")) }
    }

    @Test fun sourceUsesOnePublicHttpsEndpointAndExactByteBudget() {
        assertEquals(DownloadSource("https://speed.cloudflare.com/__down?bytes=8388608", 8_388_608),
            DownloadSource.parse("https://speed.cloudflare.com/__down?bytes=8388608"))
        assertEquals("https://speed.cloudflare.com/__down?bytes=1",
            DownloadSource.parse("HTTPS://SPEED.CLOUDFLARE.COM:443/__down?bytes=1").url)
    }

    @Test fun sourceRejectsInsecurePrivateAndAlternateDestinations() {
        listOf(
            "http://speed.cloudflare.com/__down?bytes=1",
            "https://127.0.0.1/__down?bytes=1",
            "https://speed.cloudflare.com.evil.example/__down?bytes=1",
            "https://user:password@speed.cloudflare.com/__down?bytes=1",
            "https://speed.cloudflare.com:444/__down?bytes=1",
            "https://speed.cloudflare.com/__down?bytes=1#fragment",
            "https://speed.cloudflare.com/other?bytes=1"
        ).forEach { assertFails { DownloadSource.parse(it) } }
    }

    @Test fun sourceRejectsAmbiguousOrUnboundedByteCounts() {
        listOf("", "bytes=0", "bytes=-1", "bytes=01", "bytes=33554433", "bytes=1&bytes=2", "bytes=1&url=https://example.com", "bytes=%31")
            .forEach { assertFails { DownloadSource.parse("https://speed.cloudflare.com/__down?$it") } }
    }

    @Test fun fourEightMibSamplesMeetTheWholeRunBudget() {
        val manifest = manifest(listOf("olcrtc", "awg", "vless", "openflux"), 8_388_608)
        assertEquals(8_388_608L, manifest.validate().byteLimit)
        assertEquals(33_554_432L, manifest.validate().byteLimit * manifest.profiles.size)
    }

    @Test fun optionalLargerSampleRemainsWithinTheWholeRunBudget() {
        assertEquals(33_554_432L, manifest(listOf("openflux"), 33_554_432).validate().byteLimit)
        assertFails { manifest(listOf("olcrtc", "awg", "vless", "openflux"), 16_777_216).validate() }
        assertFails { manifest(listOf("olcrtc", "openflux"), 33_554_432).validate() }
    }

    @Test fun manifestRejectsDuplicateUnknownAndEmptyEngineSets() {
        assertFails { manifest(emptyList(), 1).validate() }
        assertFails { manifest(listOf("openflux", "openflux"), 1).validate() }
        assertFails { manifest(listOf("max"), 1).validate() }
    }

    @Test fun manifestRejectsUnsupportedVersionAndDuration() {
        val valid = manifest(listOf("openflux"), 1)
        assertFails { valid.copy(version = 2).validate() }
        assertFails { valid.copy(payloadSeconds = 0).validate() }
        assertFails { valid.copy(payloadSeconds = 16).validate() }
    }

    @Test fun manifestParserRejectsUnknownFieldsAndParsesExplicitProfiles() {
        val json = """{"version":1,"source":"https://speed.cloudflare.com/__down?bytes=8","payload_seconds":15,"profiles":[{"engine":"vless","profile_file":"vless.txt"}]}"""
        assertEquals("vless", DownloadBenchmarkManifest.parse(json).profiles.single().engine)
        assertFails { DownloadBenchmarkManifest.parse(json.replace("\"version\":1", "\"version\":1,\"allow_direct\":true")) }
    }

    @Test fun profilePathsCannotEscapeTheProtectedRoot() {
        assertEquals(root.resolve("profiles/vless.txt"), DownloadBenchmarkProfile("vless", "profiles/vless.txt").resolve(root))
        listOf("", "../outside.txt", "profiles/../../outside.txt", "profiles\\vless.txt", root.parent.resolve("outside.txt").toString())
            .forEach { assertFails { DownloadBenchmarkProfile("vless", it).resolve(root) } }
    }

    @Test fun decimalMegabitsUsesPayloadBytesAndNanoseconds() {
        assertEquals(8.0, downloadMegabitsPerSecond(1_000_000, 1_000_000_000), 0.0000001)
        assertEquals(67.108864, downloadMegabitsPerSecond(8_388_608, 1_000_000_000), 0.0000001)
        assertEquals(4.0, downloadMegabitsPerSecond(1_000_000, 2_000_000_000), 0.0000001)
        assertEquals(0.0, downloadMegabitsPerSecond(0, 1_000_000_000))
    }

    @Test fun throughputRejectsInvalidMeasurements() {
        assertFails { downloadMegabitsPerSecond(-1, 1) }
        assertFails { downloadMegabitsPerSecond(1, 0) }
        assertFails { downloadMegabitsPerSecond(1, -1) }
    }

    @Test fun payloadNeverReadsPastItsByteBudget() {
        var now = 0L
        var requestedBytes = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Use bounded buffer reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                requestedBytes += length
                now += 1_000_000L
                return length
            }
        }
        val sample = measureDownloadPayload(input, 32_775, 15_000_000_000L, 0, { now })
        assertEquals(32_775L, sample.bytes)
        assertEquals(32_775, requestedBytes)
        assertEquals(2_000_000L, sample.elapsedNanos)
        assertEquals("byte_cap", sample.stopReason)
    }

    @Test fun elapsedPayloadDeadlineStopsBeforeAnotherRead() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int { reads++; return 0 }
        }
        val sample = measureDownloadPayload(input, 8, 15, 0, { 15 })
        assertEquals(0, reads)
        assertEquals(0L, sample.bytes)
        assertEquals("time_cap", sample.stopReason)
    }

    @Test fun deadlineTimeoutKeepsOnlyReceivedPayload() {
        var now = 0L
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Use bounded buffer reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (reads == 1) { now = 10; return 3 }
                now = 15
                throw InterruptedIOException("synthetic payload deadline")
            }
        }
        val sample = measureDownloadPayload(input, 8, 15, 0, { now })
        assertEquals(3L, sample.bytes)
        assertEquals(15L, sample.elapsedNanos)
        assertEquals("time_cap", sample.stopReason)
    }

    @Test fun earlySocketTimeoutIsNotReportedAsSuccessfulThroughput() {
        val input = object : InputStream() {
            override fun read(): Int = throw InterruptedIOException("synthetic early timeout")
        }
        assertFailsWith<InterruptedIOException> { measureDownloadPayload(input, 8, 15, 0, { 1 }) }
    }

    @Test fun healthFailureAbortsInsteadOfFallingBackOrCountingMoreBytes() {
        var checks = 0
        assertFailsWith<IllegalStateException> {
            measureDownloadPayload(ByteArrayInputStream(ByteArray(8)), 8, 15, 0, { 1 }) {
                checks++
                error("synthetic owned engine death")
            }
        }
        assertEquals(1, checks)
    }

    @Test fun eofAndZeroProgressAreDistinguished() {
        assertEquals("end_of_body", measureDownloadPayload(ByteArrayInputStream(ByteArray(0)), 8, 15, 0, { 1 }).stopReason)
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertFails { measureDownloadPayload(stalled, 8, 15, 0, { 1 }) }
    }

    @Test fun prematureEofCannotBecomeSuccessfulThroughput() {
        val sample = measureDownloadPayload(ByteArrayInputStream(ByteArray(3)), 8, 15, 0, { 1 })
        assertEquals(3L, sample.bytes)
        assertEquals("end_of_body", sample.stopReason)
        assertFails { requireUsableDownloadSample(sample, 8) }
        assertFails { requireUsableDownloadSample(DownloadPayloadSample(0, 15, "time_cap"), 8) }
        requireUsableDownloadSample(DownloadPayloadSample(3, 15, "time_cap"), 8)
        requireUsableDownloadSample(DownloadPayloadSample(8, 1, "byte_cap"), 8)
    }

    @Test fun healthFailureDuringOrAfterPayloadPreventsAResult() {
        for (limit in listOf(3L, 8L)) {
            var checks = 0
            var reads = 0
            val input = object : InputStream() {
                override fun read(): Int = error("Use bounded buffer reads")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; return 3 }
            }
            assertFailsWith<IllegalStateException> {
                measureDownloadPayload(input, limit, 15, 0, { 1 }) {
                    checks++
                    if (checks == 2) error("synthetic mid-download or final health failure")
                }
            }
            assertEquals(1, reads)
        }
    }

    @Test fun retryGatePermitsOnlyOneHttpsRequest() {
        val gate = DownloadRequestGate()
        gate.requireFirstRequest()
        assertFails { gate.requireFirstRequest() }
        assertFails { gate.requireFirstRequest() }
    }

    @Test fun failureStagesAreFixedAndNeverIncludeExceptionDetails() {
        assertEquals(setOf("import", "configure", "connect", "ownership", "socks_auth", "download", "stop"),
            DownloadFailureStage.entries.map { it.code }.toSet())
        for (stage in DownloadFailureStage.entries) {
            val result = benchmarkFailure("openflux", "https://speed.cloudflare.com/__down?bytes=8", stage,
                IllegalStateException("private-fixture-key-and-document-must-not-appear"))
            val json = Json { encodeDefaults = true }.encodeToString(result)
            assertEquals(stage.code, result.failureStage)
            assertEquals("failed", result.status)
            assertEquals("download", result.direction)
            assertNull(result.megabitsPerSecond)
            kotlin.test.assertFalse(json.contains("private-fixture"))
        }
    }

    private fun environment(): Map<String, String> = mapOf(
        DOWNLOAD_BENCHMARK_GATE to "1",
        DOWNLOAD_BENCHMARK_ROOT to root.toString(),
        DOWNLOAD_BENCHMARK_MANIFEST to root.resolve("benchmark.json").toString(),
        "UNIFIEDVPN_TEST_APPDATA_ROOT" to root.toString(),
        "APPDATA" to root.resolve("Roaming").toString(),
        "LOCALAPPDATA" to root.resolve("Local").toString()
    )

    private fun manifest(engines: List<String>, byteCount: Long): DownloadBenchmarkManifest = DownloadBenchmarkManifest(
        source = "https://speed.cloudflare.com/__down?bytes=$byteCount",
        profiles = engines.map { DownloadBenchmarkProfile(it, "$it.txt") }
    )
}
