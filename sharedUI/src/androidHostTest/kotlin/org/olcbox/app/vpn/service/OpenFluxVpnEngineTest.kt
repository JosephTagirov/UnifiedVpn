package org.olcbox.app.vpn.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun keepsSecretsOutOfArgumentsAndDeletesConfigurationOnStop() = runTest {
        Fixture().use { fixture ->
            fixture.engine.start()

            assertEquals(3, fixture.command.size)
            assertEquals("--config", fixture.command[1])
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
            assertContains(failure.message.orEmpty(), "90 seconds")
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

    private class Fixture(autoReady: Boolean = true) : AutoCloseable {
        val directory: File = Files.createTempDirectory("openflux-android-test-").toFile()
        val process = FakeProcess()
        var command: List<String> = emptyList()
        var processStartCount = 0
        var socksReady = true
        var onSocksProbe: (() -> Unit)? = null
        var startFailure: IOException? = null
        var onOutput: (String) -> Unit = {}
        private val logs = mutableListOf<String>()
        val engine = OpenFluxVpnEngine(
            profile = VpnProfileConfig(
                type = VpnProfileConfig.TYPE_OPENFLUX,
                rawConfig = TEST_CONFIG.toJson()
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
        val TEST_CONFIG = OpenFluxProfileConfig(TEST_DOCUMENT_URL, TEST_KEY)
    }
}
