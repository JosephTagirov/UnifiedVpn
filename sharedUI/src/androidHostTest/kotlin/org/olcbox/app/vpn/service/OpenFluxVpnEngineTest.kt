package org.olcbox.app.vpn.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.olcbox.app.data.model.OpenFluxProfileConfig
import org.olcbox.app.data.model.VpnProfileConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OpenFluxVpnEngineTest {
    @Test
    fun sessionSkipsBrowserUsesItsOwnContractAndRequestsGracefulStop() = runTest {
        val session = TEST_CONFIG.copy(
            documentUrl = "https://telemost.yandex.ru/j/12345678901234",
            encryptionKey = "Ab12".repeat(16), version = 2,
            transport = OpenFluxProfileConfig.TRANSPORT_TELEMOST,
            wireCodec = OpenFluxProfileConfig.CODEC_SESSION
        )
        Fixture(config = session).use { fixture ->
            fixture.engine.start()
            assertEquals(0, fixture.browserCalls)
            assertEquals("--control-stdio", fixture.command[3])
            val runtime = Json.parseToJsonElement(File(fixture.command[2]).readText()).jsonObject
            assertEquals("2", runtime.getValue("version").jsonPrimitive.content)
            assertEquals(session.encryptionKey, runtime.getValue("encryption_key").jsonPrimitive.content)
            fixture.onOutput("OPENFLUX_BROWSER_VERIFY $TEST_REQUEST_ID")
            runCurrent()
            assertEquals(0, fixture.browserCalls)
            assertEquals("", fixture.process.stdinText())
            fixture.engine.stop()
            assertEquals("STOP\n", fixture.process.stdinText())
            assertFalse(fixture.engine.isRunning)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun sessionLaunchFailureIsNotMisreportedAsBrowserVerification() = runTest {
        Fixture(config = TEST_CONFIG.copy(
            documentUrl = "https://telemost.yandex.ru/j/12345678901234", version = 2,
            transport = OpenFluxProfileConfig.TRANSPORT_TELEMOST,
            wireCodec = OpenFluxProfileConfig.CODEC_SESSION
        )).use { fixture ->
            fixture.startFailure = IOException("Synthetic launch failure")
            assertIs<IOException>(runCatching { fixture.engine.start() }.exceptionOrNull())
            assertEquals(0, fixture.browserCalls)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun keepsSecretsOutOfArgumentsAndDeletesConfigurationOnStop() = runTest {
        Fixture().use { fixture ->
            fixture.engine.start()

            assertEquals(4, fixture.command.size)
            assertEquals("--config", fixture.command[1])
            assertEquals("--bootstrap-stdio", fixture.command[3])
            val configFile = File(fixture.command[2])
            assertEquals(fixture.directory.canonicalFile, assertNotNull(configFile.parentFile).canonicalFile)
            val config = Json.parseToJsonElement(configFile.readText()).jsonObject
            assertEquals("client", config.getValue("mode").jsonPrimitive.content)
            assertEquals("127.0.0.1:23841", config.getValue("socks5").jsonPrimitive.content)
            assertEquals(TEST_KEY, config.getValue("encryption_key").jsonPrimitive.content)
            assertEquals(TEST_USERNAME, config.getValue("socks_username").jsonPrimitive.content)
            assertEquals(TEST_PASSWORD, config.getValue("socks_password").jsonPrimitive.content)
            listOf(TEST_DOCUMENT_URL, TEST_KEY, TEST_USERNAME, TEST_PASSWORD).forEach { secret ->
                assertFalse(fixture.command.any { secret in it })
            }
            assertTrue(fixture.engine.isRunning)

            fixture.engine.stop()

            assertFalse(fixture.engine.isRunning)
            assertFalse(configFile.exists())
            assertEquals(1, fixture.process.destroyCount)
        }
    }

    @Test
    fun openPortAloneDoesNotPassTheEncryptedReadinessGate() = runTest {
        Fixture(autoReady = false).use { fixture ->
            val startup = async { fixture.engine.start() }
            runCurrent()
            assertFalse(startup.isCompleted)
            assertFalse(fixture.engine.isRunning)

            fixture.onOutput("log prefix OPENFLUX_READY")
            advanceTimeBy(150)
            runCurrent()
            assertFalse(startup.isCompleted)

            fixture.onOutput("OPENFLUX_READY")
            advanceTimeBy(150)
            runCurrent()
            startup.await()
            assertTrue(fixture.engine.isRunning)
        }
    }

    @Test
    fun readyMarkerDoesNotBypassTheAuthenticatedSocksProbe() = runTest {
        Fixture().use { fixture ->
            fixture.socksReady = false
            val startup = async { runCatching { fixture.engine.start() } }
            runCurrent()
            assertFalse(startup.isCompleted)
            advanceUntilIdle()

            val failure = assertIs<IllegalStateException>(startup.await().exceptionOrNull())
            assertContains(failure.message.orEmpty(), "120 seconds")
            assertFalse(fixture.process.isAlive)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun childExitBeforeReadinessCleansUpAndFailsStartup() = runTest {
        Fixture(autoReady = false).use { fixture ->
            val startup = async { runCatching { fixture.engine.start() } }
            runCurrent()
            fixture.process.alive = false
            advanceUntilIdle()

            val failure = assertIs<IllegalStateException>(startup.await().exceptionOrNull())
            assertContains(failure.message.orEmpty(), "exited before")
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun cancellingStartupStopsChildAndDeletesConfiguration() = runTest {
        Fixture(autoReady = false).use { fixture ->
            val startup = launch { fixture.engine.start() }
            runCurrent()
            assertEquals(1, fixture.configFiles().size)

            startup.cancelAndJoin()

            assertFalse(fixture.process.isAlive)
            assertFalse(fixture.engine.isRunning)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun failedProcessCreationDoesNotLeaveConfigurationBehind() = runTest {
        Fixture().use { fixture ->
            fixture.startFailure = IOException("Synthetic process launch failure")

            assertIs<IOException>(runCatching { fixture.engine.start() }.exceptionOrNull())

            assertTrue(fixture.configFiles().isEmpty())
            assertFalse(fixture.engine.isRunning)
        }
    }

    @Test
    fun stopForcesAnUnresponsiveChildAndIsIdempotent() = runTest {
        Fixture().use { fixture ->
            fixture.process.ignoresDestroy = true
            fixture.engine.start()

            fixture.engine.stop()
            fixture.engine.stop()

            assertEquals(1, fixture.process.destroyCount)
            assertEquals(1, fixture.process.forceDestroyCount)
            assertFalse(fixture.process.isAlive)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun stoppingPendingStartupCannotLaterMarkItReady() = runTest {
        Fixture(autoReady = false).use { fixture ->
            val startup = async { runCatching { fixture.engine.start() } }
            runCurrent()

            fixture.engine.stop()
            fixture.onOutput("OPENFLUX_READY")
            advanceUntilIdle()

            assertTrue(startup.await().isFailure)
            assertFalse(fixture.engine.isRunning)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun stopDuringSocksProbeCannotCompleteStartup() = runTest {
        Fixture().use { fixture ->
            fixture.onSocksProbe = { fixture.engine.stop() }

            assertTrue(runCatching { fixture.engine.start() }.isFailure)

            assertFalse(fixture.engine.isRunning)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun childExitDuringSocksProbeCannotCompleteStartup() = runTest {
        Fixture().use { fixture ->
            fixture.onSocksProbe = { fixture.process.alive = false }

            assertIs<IllegalStateException>(runCatching { fixture.engine.start() }.exceptionOrNull())

            assertFalse(fixture.engine.isRunning)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun cancellationDuringSocksProbeCleansUpBeforeReturning() = runTest {
        Fixture().use { fixture ->
            val startup = launch { fixture.engine.start() }
            fixture.onSocksProbe = { startup.cancel() }

            runCurrent()
            startup.join()

            assertTrue(startup.isCancelled)
            assertFalse(fixture.process.isAlive)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun isRunningRechecksChildOwnershipAfterItsSocksProbe() = runTest {
        Fixture().use { fixture ->
            fixture.engine.start()
            fixture.onSocksProbe = { fixture.engine.stop() }

            assertFalse(fixture.engine.isRunning)

            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun failedForcedStopRetainsTheChildAndBlocksAnotherLaunch() = runTest {
        Fixture().use { fixture ->
            fixture.process.ignoresDestroy = true
            fixture.process.ignoresForceDestroy = true
            fixture.engine.start()

            assertIs<IllegalStateException>(runCatching { fixture.engine.stop() }.exceptionOrNull())
            assertIs<IllegalStateException>(runCatching { fixture.engine.start() }.exceptionOrNull())

            assertEquals(1, fixture.processStartCount)
            assertEquals(2, fixture.process.forceDestroyCount)
            assertFalse(fixture.engine.isRunning)
            assertTrue(fixture.configFiles().isEmpty())

            fixture.process.ignoresForceDestroy = false
            fixture.engine.stop()
            assertFalse(fixture.process.isAlive)
        }
    }

    @Test
    fun outputReaderOmitsOversizedLinesWithoutExposingTheirSuffix() {
        val output = mutableListOf<String>()
        val input = ("x".repeat(5000) + "OPENFLUX_READY\nOPENFLUX_READY\r\nlast line")
            .byteInputStream()

        consumeOpenFluxEngineOutput(input, output::add)

        assertEquals(
            listOf("Process output line omitted (too long)", "OPENFLUX_READY", "last line"),
            output
        )
    }

    @Test
    fun outputRedactsDocumentKeyAndLocalCredentialsBeforeTheSink() {
        val output = sanitizeOpenFluxEngineOutput(
            "$TEST_DOCUMENT_URL ${TEST_KEY.uppercase()} $TEST_USERNAME $TEST_PASSWORD",
            TEST_CONFIG,
            TEST_USERNAME,
            TEST_PASSWORD
        )

        listOf(TEST_DOCUMENT_URL, TEST_KEY, TEST_USERNAME, TEST_PASSWORD).forEach { secret ->
            assertFalse(output.contains(secret, ignoreCase = true))
        }
        assertEquals("OpenFlux: <redacted> <redacted> <redacted> <redacted>", output)
    }

    @Test
    fun browserVerificationWritesOnePrivateResponseButCannotMarkTransportReady() = runTest {
        Fixture(autoReady = false).use { fixture ->
            val startup = async { fixture.engine.start() }
            runCurrent()
            fixture.onOutput("OPENFLUX_BROWSER_VERIFY $TEST_REQUEST_ID")
            runCurrent()

            assertEquals(1, fixture.browserCalls)
            val lines = fixture.process.stdinText().lineSequence().filter(String::isNotBlank).toList()
            assertEquals(1, lines.size)
            assertEquals(TEST_REQUEST_ID, Json.parseToJsonElement(lines.single()).jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("SyntheticBrowser/1.0", Json.parseToJsonElement(lines.single()).jsonObject.getValue("user_agent").jsonPrimitive.content)
            assertContains(lines.single(), TEST_COOKIE)
            assertFalse(startup.isCompleted)
            assertFalse(fixture.engine.isRunning)
            assertFalse(fixture.configFiles().single().readText().contains(TEST_COOKIE))
            fixture.onOutput("native echo $TEST_COOKIE")
            assertFalse(fixture.logs.any { TEST_COOKIE in it || TEST_REQUEST_ID in it })

            fixture.onOutput("OPENFLUX_READY")
            advanceTimeBy(150)
            runCurrent()
            startup.await()
            assertTrue(fixture.engine.isRunning)
        }
    }

    @Test
    fun preflightFailureDoesNotStartNativeOrExposeExceptionSecrets() = runTest {
        Fixture().use { fixture ->
            fixture.browserVerify = { throw IOException("$TEST_DOCUMENT_URL $TEST_COOKIE") }
            val failure = assertIs<OpenFluxBrowserException>(runCatching { fixture.engine.start() }.exceptionOrNull())
            assertEquals(OpenFluxBrowserFailure.Failed, failure.reason)
            assertEquals(0, fixture.processStartCount)
            assertTrue(fixture.configFiles().isEmpty())
            assertFalse(failure.message.orEmpty().contains(TEST_COOKIE))
            assertFalse(fixture.logs.any { TEST_COOKIE in it || TEST_DOCUMENT_URL in it })
        }
    }

    @Test
    fun preflightMetadataFailureLogsOnlyTheFixedValidationStage() = runTest {
        Fixture().use { fixture ->
            fixture.browserVerify = {
                throw OpenFluxBrowserException(OpenFluxBrowserFailure.Failed, OpenFluxBrowserValidationStage.ClassicDownload)
            }
            assertIs<OpenFluxBrowserException>(runCatching { fixture.engine.start() }.exceptionOrNull())
            assertEquals(listOf("OpenFlux: Browser metadata validation failed (stage=classic_download)"), fixture.logs)
            assertEquals(0, fixture.processStartCount)
        }
    }

    @Test
    fun releasePreflightDiagnosticsReachAppLogWithoutStartingNative() = runTest {
        Fixture().use { fixture ->
            fixture.browserVerify = {
                throw openFluxBrowserReplyFailure("Failed", null, "tls_untrusted", "forbidden", null, "hdrc")
            }
            val failure = assertIs<OpenFluxBrowserException>(runCatching { fixture.engine.start() }.exceptionOrNull())
            assertEquals(OpenFluxBrowserDiagnosticStage.TlsUntrusted, failure.diagnosticStage)
            assertEquals(OpenFluxBrowserTlsResource.Hdrc, failure.tlsResource)
            assertEquals(listOf("OpenFlux: Browser verification diagnostic (reason=Failed, stage=tls_untrusted, resource=hdrc, http=forbidden)"), fixture.logs)
            assertEquals(0, fixture.processStartCount)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun stoppingCancelsPendingPreflightBeforeAnyNativeFilesOrProcess() = runTest {
        Fixture().use { fixture ->
            var cancelled = false
            fixture.browserVerify = {
                try { awaitCancellation() } finally { cancelled = true }
            }
            val startup = launch { fixture.engine.start() }
            runCurrent()
            assertEquals(0, fixture.processStartCount)
            assertTrue(fixture.configFiles().isEmpty())
            fixture.engine.stop()
            runCurrent()

            assertTrue(cancelled)
            assertTrue(startup.isCancelled)
            assertEquals("", fixture.process.stdinText())
            assertEquals(0, fixture.processStartCount)
            assertEquals(1, fixture.browserCalls)
        }
    }

    @Test
    fun preflightTimeoutIsBoundedIndependentlyOfNativeStartup() = runTest {
        Fixture().use { fixture ->
            fixture.browserVerify = { awaitCancellation() }
            val startup = async { runCatching { fixture.engine.start() } }
            runCurrent()
            advanceTimeBy(119_999)
            runCurrent()
            assertFalse(startup.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(OpenFluxBrowserFailure.Timeout, assertIs<OpenFluxBrowserException>(startup.await().exceptionOrNull()).reason)
            assertEquals(0, fixture.processStartCount)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun blockedOptionalTlsResourceDoesNotTurnAStalledPreflightIntoSuccess() = runTest {
        Fixture().use { fixture ->
            val diagnostic = assertNotNull(openFluxBrowserBlockedTlsLogLine("tls_untrusted", "hdrc"))
            fixture.browserVerify = {
                fixture.logs += diagnostic
                awaitCancellation()
            }
            val startup = async { runCatching { fixture.engine.start() } }
            runCurrent()
            assertEquals(listOf(diagnostic), fixture.logs)
            assertEquals(0, fixture.processStartCount)
            advanceTimeBy(OPENFLUX_BROWSER_PREFLIGHT_TIMEOUT_MS)
            runCurrent()

            assertEquals(OpenFluxBrowserFailure.Timeout,
                assertIs<OpenFluxBrowserException>(startup.await().exceptionOrNull()).reason)
            assertEquals(0, fixture.processStartCount)
            assertTrue(fixture.configFiles().isEmpty())
            assertEquals("", fixture.process.stdinText())
        }
    }

    @Test
    fun blockedOptionalTlsResourceDoesNotInterfereWithUserCancellation() = runTest {
        Fixture().use { fixture ->
            val diagnostic = assertNotNull(openFluxBrowserBlockedTlsLogLine("tls_expired", "mdd"))
            var cancelled = false
            fixture.browserVerify = {
                fixture.logs += diagnostic
                try { awaitCancellation() } finally { cancelled = true }
            }
            val startup = launch { fixture.engine.start() }
            runCurrent()
            fixture.engine.stop()
            runCurrent()

            assertTrue(cancelled)
            assertTrue(startup.isCancelled)
            assertEquals(listOf(diagnostic), fixture.logs)
            assertEquals(0, fixture.processStartCount)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun blockedOptionalTlsResourceStillRequiresCompletedPreflightAndEncryptedReadiness() = runTest {
        Fixture(autoReady = false).use { fixture ->
            val approved = CompletableDeferred<Unit>()
            val session = fixture.browserVerify
            fixture.browserVerify = { documentUrl ->
                fixture.logs += assertNotNull(openFluxBrowserBlockedTlsLogLine("tls_untrusted", "hdrc"))
                approved.await()
                session(documentUrl)
            }
            val startup = async { fixture.engine.start() }
            runCurrent()
            assertEquals(0, fixture.processStartCount)
            assertFalse(fixture.engine.isRunning)
            approved.complete(Unit)
            runCurrent()
            assertEquals(1, fixture.processStartCount)
            assertFalse(startup.isCompleted)
            assertFalse(fixture.engine.isRunning)

            fixture.onOutput("OPENFLUX_BROWSER_VERIFY $TEST_REQUEST_ID")
            runCurrent()
            assertContains(fixture.process.stdinText(), TEST_COOKIE)
            assertFalse(startup.isCompleted)
            fixture.onOutput("OPENFLUX_READY")
            advanceTimeBy(150)
            runCurrent()
            startup.await()
            assertTrue(fixture.engine.isRunning)
            assertFalse(fixture.logs.any { TEST_COOKIE in it || TEST_DOCUMENT_URL in it })
        }
    }

    @Test
    fun nativeRequestsConsumeExactlyOnePreflightWithoutLaterRefresh() = runTest {
        Fixture().use { fixture ->
            fixture.engine.start()
            repeat(3) {
                fixture.onOutput("OPENFLUX_BROWSER_VERIFY ${it.toString().repeat(32)}")
                runCurrent()
            }
            assertEquals(1, fixture.browserCalls)
            assertContains(fixture.process.stdinText().lineSequence().first(), TEST_COOKIE)
            assertContains(fixture.process.stdinText().trim().lineSequence().last(), "verification_failed")

            fixture.browserClock = 300_000
            fixture.onOutput("OPENFLUX_BROWSER_VERIFY ${"3".repeat(32)}")
            runCurrent()
            assertEquals(1, fixture.browserCalls)
        }
    }

    @Test
    fun invalidBrowserMarkersNeverReachVerifierOrExposeTheirSuffix() = runTest {
        Fixture().use { fixture ->
            fixture.engine.start()
            fixture.onOutput("OPENFLUX_BROWSER_VERIFY invalid-$TEST_COOKIE")
            runCurrent()

            assertEquals(1, fixture.browserCalls)
            assertEquals("", fixture.process.stdinText())
            assertFalse(fixture.logs.any { TEST_COOKIE in it })
        }
    }

    @Test
    fun concurrentRequestsCannotCreateTwoBrowserSessions() = runTest {
        Fixture().use { fixture ->
            fixture.engine.start()
            fixture.onOutput("OPENFLUX_BROWSER_VERIFY $TEST_REQUEST_ID")
            fixture.onOutput("OPENFLUX_BROWSER_VERIFY ${"f".repeat(32)}")
            runCurrent()

            assertEquals(1, fixture.browserCalls)
            val lines = fixture.process.stdinText().trim().lines()
            assertEquals(2, lines.size)
            assertContains(lines.first(), TEST_COOKIE)
            assertEquals(openFluxBrowserResponse("f".repeat(32), null), lines.last())
        }
    }

    @Test
    fun unsupportedVerificationFailsClearlyBeforeNativeStartup() = runTest {
        Fixture().use { fixture ->
            fixture.browserVerify = { throw OpenFluxBrowserException(OpenFluxBrowserFailure.Unsupported) }
            assertEquals(OpenFluxBrowserFailure.Unsupported.diagnostic, runCatching { fixture.engine.start() }.exceptionOrNull()?.message)
            assertEquals(1, fixture.browserCalls)
            assertEquals(0, fixture.processStartCount)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun legacyNativeOnlyModeNeverCallsPreflightAndFailsBrowserChallengesClearly() = runTest {
        Fixture(browserPreflightSupported = false).use { fixture ->
            fixture.browserVerify = { error("Legacy mode must not instantiate WebView") }
            fixture.engine.start()
            assertEquals(0, fixture.browserCalls)
            assertEquals(1, fixture.processStartCount)
            assertTrue(fixture.engine.isRunning)
            fixture.onOutput("OPENFLUX_BROWSER_VERIFY $TEST_REQUEST_ID")
            runCurrent()
            assertEquals(openFluxBrowserResponse(TEST_REQUEST_ID, null) + "\n", fixture.process.stdinText())
            assertEquals(0, fixture.browserCalls)
            assertTrue(fixture.logs.any { OpenFluxBrowserFailure.Unsupported.diagnostic in it })
        }
    }

    @Test
    fun legacyBrowserChallengeReasonSurvivesNativeStartupExit() = runTest {
        Fixture(autoReady = false, browserPreflightSupported = false).use { fixture ->
            val startup = async { runCatching { fixture.engine.start() } }
            runCurrent()
            fixture.onOutput("OPENFLUX_BROWSER_VERIFY $TEST_REQUEST_ID")
            runCurrent()
            fixture.process.alive = false
            advanceUntilIdle()
            assertEquals(OpenFluxBrowserFailure.Unsupported.diagnostic, startup.await().exceptionOrNull()?.message)
            assertEquals(0, fixture.browserCalls)
            assertTrue(fixture.configFiles().isEmpty())
        }
    }

    @Test
    fun nativeStartupGetsItsOwnDeadlineAfterVisiblePreflight() = runTest {
        Fixture(autoReady = false).use { fixture ->
            val session = fixture.browserVerify
            fixture.browserVerify = { url -> delay(90_000); session(url) }
            val startup = async { fixture.engine.start() }
            runCurrent()
            assertEquals(0, fixture.processStartCount)
            advanceTimeBy(90_000)
            runCurrent()
            assertEquals(1, fixture.processStartCount)
            advanceTimeBy(45_000)
            fixture.onOutput("OPENFLUX_BROWSER_VERIFY $TEST_REQUEST_ID")
            runCurrent()
            assertContains(fixture.process.stdinText(), TEST_COOKIE)
            fixture.onOutput("OPENFLUX_READY")
            advanceTimeBy(150)
            runCurrent()
            startup.await()
            assertTrue(fixture.engine.isRunning)
        }
    }

    @Test
    fun networkOrProfileChangeInvalidatesTheCachedResultBeforeNativeConsumption() = runTest {
        Fixture().use { fixture ->
            fixture.engine.start()
            fixture.logs.clear()
            fixture.browserCurrent = false
            fixture.onOutput("OPENFLUX_BROWSER_VERIFY $TEST_REQUEST_ID")
            runCurrent()
            assertEquals(openFluxBrowserResponse(TEST_REQUEST_ID, null) + "\n", fixture.process.stdinText())
            assertEquals(listOf("OpenFlux: ${OpenFluxBrowserFailure.Expired.diagnostic}"), fixture.logs)
            assertFalse(fixture.logs.any { TEST_COOKIE in it })
        }
    }

    @Test
    fun metadataRevalidationFailureLogsItsFixedStageWithoutDeliveringSecretsToNative() = runTest {
        Fixture().use { fixture ->
            fixture.browserVerify = { documentUrl ->
                val classicDocument = OpenFluxClassicDocument.parse(buildJsonObject {
                    put("source_url", documentUrl)
                    put("captured_at", 0)
                    put("balancer_url", "https://editor.yandex.ru")
                    put("token", "synthetic-private-editor-token")
                    put("document_key", "synthetic-private-document-key")
                    put("file_type", "docx")
                    put("download_url", "https://download.yandex.net/synthetic-private-document")
                    put("title", "synthetic-private-title")
                    put("edit", true)
                }.toString(), documentUrl, nowSeconds = 0)
                OpenFluxBrowserSession(parseOpenFluxBrowserCookies("spravka=$TEST_COOKIE", documentUrl),
                    "SyntheticBrowser/1.0", classicDocument)
            }
            fixture.engine.start()
            fixture.logs.clear()
            fixture.onOutput("OPENFLUX_BROWSER_VERIFY $TEST_REQUEST_ID")
            runCurrent()

            assertEquals(listOf(
                "OpenFlux: Browser metadata validation failed (stage=classic_timestamp)",
                "OpenFlux: ${OpenFluxBrowserFailure.Failed.diagnostic}"
            ), fixture.logs)
            assertEquals(openFluxBrowserResponse(TEST_REQUEST_ID, null) + "\n", fixture.process.stdinText())
            listOf(TEST_DOCUMENT_URL, TEST_COOKIE, "synthetic-private-", TEST_REQUEST_ID).forEach { secret ->
                assertFalse(fixture.logs.any { secret in it })
            }
            assertFalse(fixture.process.stdinText().contains(TEST_COOKIE))
            assertFalse(fixture.process.stdinText().contains("synthetic-private-"))
        }
    }

    @Test
    fun nativeDoesNotStartUntilVisiblePreflightCompletes() = runTest {
        Fixture().use { fixture ->
            val approved = CompletableDeferred<Unit>()
            val session = fixture.browserVerify
            fixture.browserVerify = { url -> approved.await(); session(url) }
            val startup = async { fixture.engine.start() }
            runCurrent()
            assertEquals(0, fixture.processStartCount)
            assertTrue(fixture.configFiles().isEmpty())
            assertFalse(fixture.engine.isRunning)
            approved.complete(Unit)
            runCurrent()
            startup.await()
            assertEquals(1, fixture.processStartCount)
        }
    }

    private class Fixture(
        autoReady: Boolean = true,
        browserPreflightSupported: Boolean = true,
        config: OpenFluxProfileConfig = TEST_CONFIG
    ) : AutoCloseable {
        val directory: File = Files.createTempDirectory("openflux-android-test-").toFile()
        val process = FakeProcess()
        var command: List<String> = emptyList()
        var processStartCount = 0
        var socksReady = true
        var onSocksProbe: (() -> Unit)? = null
        var startFailure: IOException? = null
        var onOutput: (String) -> Unit = {}
        val logs = mutableListOf<String>()
        var browserCalls = 0
        var browserClock = 0L
        var browserCurrent = true
        var browserVerify: suspend (String) -> OpenFluxBrowserSession = {
            OpenFluxBrowserSession(parseOpenFluxBrowserCookies("spravka=$TEST_COOKIE", it), "SyntheticBrowser/1.0")
        }
        val engine = OpenFluxVpnEngine(
            profile = VpnProfileConfig(
                type = VpnProfileConfig.TYPE_OPENFLUX,
                rawConfig = config.toJson()
            ),
            socksPort = 23841,
            username = TEST_USERNAME,
            password = TEST_PASSWORD,
            log = logs::add,
            prepareRuntime = {
                OpenFluxRuntimeFiles(
                    File(directory, "libopenflux.so"),
                    directory,
                    File.createTempFile("client-", ".json", directory)
                )
            },
            startProcess = { arguments, workDirectory ->
                assertEquals(directory, workDirectory)
                command = arguments
                startFailure?.let { throw it }
                processStartCount += 1
                process
            },
            socksReadyProbe = {
                onSocksProbe?.invoke()
                socksReady
            },
            startOutputReader = { _, consume ->
                onOutput = consume
                if (autoReady) consume("OPENFLUX_READY")
                null
            },
            browserPreflightSupported = browserPreflightSupported,
            prepareBrowser = { documentUrl ->
                assertEquals(TEST_DOCUMENT_URL, documentUrl)
                browserCalls += 1
                OpenFluxBrowserLease(browserVerify(documentUrl), documentUrl, { browserCurrent }, { browserClock })
            }
        )

        fun configFiles(): List<File> = directory.listFiles().orEmpty().filter { it.extension == "json" }

        override fun close() {
            process.ignoresForceDestroy = false
            engine.stop()
            directory.deleteRecursively()
        }
    }

    private class FakeProcess : Process() {
        var alive = true
        var ignoresDestroy = false
        var ignoresForceDestroy = false
        var destroyCount = 0
        var forceDestroyCount = 0
        private val input = ByteArrayInputStream(byteArrayOf())
        private val error = ByteArrayInputStream(byteArrayOf())
        private val output = ByteArrayOutputStream()

        override fun getOutputStream() = output
        fun stdinText(): String = output.toString(Charsets.UTF_8.name())
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun isAlive() = alive
        override fun waitFor(): Int = exitValue()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0

        override fun destroy() {
            destroyCount += 1
            if (!ignoresDestroy) alive = false
        }

        override fun destroyForcibly(): Process {
            forceDestroyCount += 1
            if (!ignoresForceDestroy) alive = false
            return this
        }
    }

    private companion object {
        const val TEST_DOCUMENT_URL = "https://docs.yandex.ru/docs/view?url=synthetic-openflux-test"
        const val TEST_KEY = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        const val TEST_USERNAME = "synthetic-socks-user"
        const val TEST_PASSWORD = "synthetic-socks-password"
        const val TEST_COOKIE = "synthetic-browser-proof"
        const val TEST_REQUEST_ID = "0123456789abcdef0123456789abcdef"
        val TEST_CONFIG = OpenFluxProfileConfig(TEST_DOCUMENT_URL, TEST_KEY)
    }
}
