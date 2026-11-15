package org.olcbox.app.vpn.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopRoutedAwgProcessTest {
    private fun profile() = VpnProfileConfig(type = VpnProfileConfig.TYPE_AMNEZIA_WG, rawConfig = """
        [Interface]
        Address = 10.8.1.2/32
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        DNS = 1.1.1.1
        [Peer]
        PublicKey = vgKFDTXvNqWd2VsJWBBqqJHN1o420gQisA9067eKFxs=
        AllowedIPs = 0.0.0.0/0
        Endpoint = 203.0.113.42:55424
    """.trimIndent())

    @Test fun stopWaitsForInFlightCreationAndOwnsItsLateProcess() {
        val directory = Files.createTempDirectory("routed-publication-")
        val enteredStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopComplete = CountDownLatch(1)
        val launches = AtomicInteger()
        val startupFailure = AtomicReference<Throwable?>()
        val stopFailure = AtomicReference<Throwable?>()
        val process = StubProcess()
        val owner = DesktopRoutedAwgProcess(processStarter = {
            launches.incrementAndGet()
            enteredStart.countDown()
            check(releaseStart.await(3, TimeUnit.SECONDS))
            process
        }, nativeBinary = { directory.resolve("synthetic-core") })
        val starter = thread(isDaemon = true, name = "routed-owner-start-test") {
            try {
                runBlocking { owner.start(profile(), DesktopSocksProxySettings(port = 11920), "203.0.113.42", "Synthetic NIC",
                    shouldContinue = { true }, log = { _, _ -> }, runtimeDirectory = directory) }
            } catch (failure: Throwable) { startupFailure.set(failure) }
        }
        var stopper: Thread? = null
        try {
            assertTrue(enteredStart.await(3, TimeUnit.SECONDS))
            stopper = thread(isDaemon = true, name = "routed-owner-stop-test") {
                stopStarted.countDown()
                try { owner.close() } catch (failure: Throwable) { stopFailure.set(failure) }
                finally { stopComplete.countDown() }
            }
            assertTrue(stopStarted.await(1, TimeUnit.SECONDS))
            assertFalse(stopComplete.await(100, TimeUnit.MILLISECONDS), "Stop must wait for creation and publication")
            releaseStart.countDown()
            assertTrue(stopComplete.await(3, TimeUnit.SECONDS))
            starter.join(3_000)
            assertFalse(starter.isAlive)
            assertEquals(null, stopFailure.get())
            assertNotNull(startupFailure.get())
            assertEquals(1, launches.get(), "A stopped owner must not create the Xray front")
            assertFalse(process.isAlive)
            assertTrue(Files.list(directory).use { it.findAny().isEmpty })
        } finally {
            releaseStart.countDown()
            starter.join(3_000)
            stopper?.join(3_000)
            owner.close()
            Files.deleteIfExists(directory)
        }
    }

    @Test fun stoppedOwnerCannotCreateAProcessLater() = runBlocking {
        var launches = 0
        val owner = DesktopRoutedAwgProcess(processStarter = { launches++; StubProcess() },
            nativeBinary = { error("Closed owner must not resolve a native binary") })
        owner.close()
        assertFailsWith<CancellationException> {
            owner.start(profile(), DesktopSocksProxySettings(port = 11920), "203.0.113.42", "Synthetic NIC",
                shouldContinue = { true }, log = { _, _ -> })
        }
        assertEquals(0, launches)
    }

    private class StubProcess : Process() {
        @Volatile private var alive = true
        private val input = ByteArrayInputStream(byteArrayOf())
        private val output = ByteArrayOutputStream()
        override fun getOutputStream() = output
        override fun getInputStream() = input
        override fun getErrorStream() = input
        override fun isAlive() = alive
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = !alive
        override fun exitValue() = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() { alive = false }
    }

    @Test fun nativeOwnerStopsBothChildrenAndDeletesBothPrivateConfigs() = runBlocking {
        val root = System.getenv("DESKTOP_ROUTED_AWG_TEST_ROOT")?.let(Path::of) ?: return@runBlocking
        val directory = Files.createTempDirectory(root, "routed-owner-")
        val owner = DesktopRoutedAwgProcess()
        try {
            val settings = DesktopSocksProxySettings(port = LocalPortAllocator.select("127.0.0.1", 11920),
                username = "synthetic-user", password = "synthetic-password")
            val front = owner.start(profile(), settings, "203.0.113.42", DesktopPhysicalInterface.currentWindows(),
                shouldContinue = { true }, log = { _, _ -> }, runtimeDirectory = directory)
            val child = assertNotNull(owner.child)
            assertTrue(front.isAlive && child.isAlive)
            assertTrue(Files.list(directory).use { it.count() == 2L })
            owner.close()
            assertFalse(front.isAlive); assertFalse(child.isAlive)
            assertTrue(Files.list(directory).use { it.findAny().isEmpty })
        } finally { owner.close(); Files.deleteIfExists(directory) }
    }

    @Test fun cancellationAfterChildCreationCleansUpWithoutStartingAnotherEngine() = runBlocking {
        val root = System.getenv("DESKTOP_ROUTED_AWG_TEST_ROOT")?.let(Path::of) ?: return@runBlocking
        val directory = Files.createTempDirectory(root, "routed-cancel-")
        val owner = DesktopRoutedAwgProcess()
        var child: Process? = null
        try {
            val settings = DesktopSocksProxySettings(port = LocalPortAllocator.select("127.0.0.1", 11920))
            assertFailsWith<CancellationException> {
                owner.start(profile(), settings, "203.0.113.42", DesktopPhysicalInterface.currentWindows(),
                    shouldContinue = { owner.child?.let { child = it; false } ?: true },
                    log = { _, _ -> }, runtimeDirectory = directory)
            }
            assertFalse(assertNotNull(child).isAlive)
            assertTrue(Files.list(directory).use { it.findAny().isEmpty })
        } finally { owner.close(); Files.deleteIfExists(directory) }
    }
}
