package org.olcbox.app.vpn.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.model.LocationConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class VkCallsVpnEngineTest {
    @Test fun requiresReadySignalAndAuthenticatedProbeThenCleansUp() = runTest {
        Fixture().use { f ->
            val start = async { f.engine.start() }
            runCurrent()
            assertFalse(start.isCompleted)
            assertFalse(f.engine.isRunning)
            f.output("SOCKS5 server listening on 127.0.0.1:19871")
            advanceTimeBy(200)
            runCurrent()
            assertFalse(start.isCompleted)
            f.probe = true
            advanceTimeBy(200)
            runCurrent()
            start.await()
            assertTrue(f.engine.isRunning)
            assertEquals(2, f.args.size)
            assertFalse(f.args.any { it.contains(f.location.key) || it.contains(f.location.id) })
            f.engine.stop()
            assertFalse(f.process.isAlive)
            assertFalse(f.config.exists())
            assertFalse(f.engine.isRunning)
        }
    }

    @Test fun cancellationStopsTheProcessAndDeletesItsSecretFile() = runTest {
        Fixture().use { f ->
            val start = async { f.engine.start() }
            runCurrent()
            start.cancelAndJoin()
            assertFalse(f.process.isAlive)
            assertFalse(f.config.exists())
            assertFalse(f.engine.isRunning)
        }
    }

    @Test fun stopDuringStartCannotBecomeConnectedFromLateOutput() = runTest {
        Fixture().use { f ->
            val start = async { runCatching { f.engine.start() } }
            runCurrent()
            f.engine.stop()
            f.probe = true
            f.output("SOCKS5 server listening on 127.0.0.1:19871")
            advanceTimeBy(200)
            runCurrent()
            assertIs<kotlinx.coroutines.CancellationException>(start.await().exceptionOrNull())
            assertFalse(f.engine.isRunning)
            assertFalse(f.config.exists())
        }
    }

    @Test fun nativeCertificateFailureIsNotRetriedAndRemainsRedacted() = runTest {
        Fixture().use { f ->
            val start = async { runCatching { f.engine.start() } }
            runCurrent()
            f.output("tls: certificate signed by unknown authority Get \"https://edge.vk.ru/?token=test-secret\"")
            f.process.destroy()
            advanceTimeBy(200)
            runCurrent()
            val failure = assertIs<IllegalStateException>(start.await().exceptionOrNull())
            assertTrue(failure.message.orEmpty().contains("unknown authority"))
            assertFalse(failure.message.orEmpty().contains("test-secret"))
            assertEquals(1, f.launches)
            assertFalse(f.config.exists())
        }
    }

    @Test fun readinessTimeoutHasBoundedRetriesAndCleansEveryAttempt() = runTest {
        Fixture().use { f ->
            val start = async { runCatching { f.engine.start() } }
            advanceUntilIdle()
            assertIs<IllegalStateException>(start.await().exceptionOrNull())
            assertEquals(4, f.launches)
            assertFalse(f.engine.isRunning)
            assertFalse(f.config.exists())
            assertTrue(f.processes.all { !it.isAlive })
        }
    }

    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("vk-engine-test").toFile()
        val config = File(directory, "client.json")
        val location = LocationConfig(bypassProvider = "vkcalls", id = "https://vk.ru/call/join/test", key = "ab".repeat(32))
        var process = FakeProcess()
        val processes = mutableListOf<FakeProcess>()
        var launches = 0
        var probe = false
        var args = emptyList<String>()
        var output: (String) -> Unit = {}
        val engine = VkCallsVpnEngine(location, 19871, "test-user", "test-pass", {},
            prepareRuntime = { config.createNewFile(); VkCallsRuntimeFiles(File(directory, "native"), directory, config) },
            launchProcess = { command, _ ->
                args = command
                launches++
                process = FakeProcess().also { processes.add(it) }
                process
            },
            readyProbe = { probe }, outputReader = { _, consume -> output = consume; null })
        override fun close() { engine.stop(); directory.deleteRecursively() }
    }

    private class FakeProcess : Process() {
        private var alive = true
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun isAlive() = alive
        override fun waitFor(): Int { alive = false; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = !alive
        override fun exitValue(): Int { check(!alive); return 0 }
        override fun destroy() { alive = false }
        override fun destroyForcibly(): Process { alive = false; return this }
    }
}
