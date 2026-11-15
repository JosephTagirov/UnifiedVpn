package org.olcbox.app.vpn.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.olcbox.app.data.model.VpnEntryEndpoint
import org.olcbox.app.data.model.VpnEntryFailoverConfig
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.ClientDirectRoutingConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.EntryFailoverPolicy
import org.olcbox.app.vpn.VpnEntryChoice
import org.olcbox.app.vpn.VpnPingUnavailableException
import org.olcbox.app.vpn.XrayEntryFailoverConfig
import org.olcbox.app.vpn.XraySocksSettings

class DesktopEntryFailoverProcessTest {
    private val entries = VpnEntryFailoverConfig(VpnEntryEndpoint("185.11.247.237", 443, "RuWeb"),
        VpnEntryEndpoint("48.103.218.29", 443, "Justhost"))
    private fun vless() = VpnProfileConfig(type = VpnProfileConfig.TYPE_VLESS,
        uri = "vless://bcbf7260-efb2-4e46-9f04-9742826b457c@ru-kvn.meesharr.space:443?type=ws&security=tls&sni=ru-kvn.meesharr.space&host=ru-kvn.meesharr.space&path=%2Ffixture#fixture")
    private fun awg() = VpnProfileConfig(type = VpnProfileConfig.TYPE_AMNEZIA_WG, rawConfig = """
        [Interface]
        Address = 10.8.1.2/32
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        DNS = 1.1.1.1
        [Peer]
        PublicKey = vgKFDTXvNqWd2VsJWBBqqJHN1o420gQisA9067eKFxs=
        AllowedIPs = 0.0.0.0/0
        Endpoint = ru-kvn.meesharr.space:55424
    """.trimIndent())
    private val awgEntries get() = entries.copy(primary = entries.primary.copy(port = 55424), backup = entries.backup.copy(port = 55424))
    private fun settings() = DesktopSocksProxySettings(port = LocalPortAllocator.select("127.0.0.1", 11940))
    private fun fastPolicy(now: Long) = EntryFailoverPolicy(now, minimumBackupMillis = 0)

    @Test fun mainListenerCompatibilityDoesNotWeakenPrivateProbesOrDirectBinding() {
        val base = XrayEntryFailoverConfig.buildVless(vless(), XraySocksSettings(port = 11940, username = "main", password = "secret"),
            entries.primary, entries.backup, XraySocksSettings(port = 11941, username = "probe1", password = "secret1"),
            XraySocksSettings(port = 11942, username = "probe2", password = "secret2"), "/fixture/a.sock")
        for (settings in listOf(DesktopSocksProxySettings(port = 11940),
            DesktopSocksProxySettings(host = "0.0.0.0", port = 11940, username = "configured-user", password = "configured-password"))) {
            val config = Json.parseToJsonElement(DesktopEntryFailoverProcess.configureDesktopFront(base, settings, "Ethernet Fixture")).jsonObject
            val inbounds = config.getValue("inbounds").jsonArray.map { it.jsonObject }
            val main = inbounds.first { it["tag"] == JsonPrimitive("entry-main") }
            assertEquals(settings.host, main.getValue("listen").jsonPrimitive.content)
            assertEquals(if (settings.username.isBlank()) "noauth" else "password", main.getValue("settings").jsonObject.getValue("auth").jsonPrimitive.content)
            for (probe in inbounds.filter { it.getValue("tag").jsonPrimitive.content.startsWith("entry-probe-") }) {
                assertEquals("127.0.0.1", probe.getValue("listen").jsonPrimitive.content)
                assertEquals("password", probe.getValue("settings").jsonObject.getValue("auth").jsonPrimitive.content)
                assertFalse(probe.containsKey("sniffing"))
            }
            val outbounds = config.getValue("outbounds").jsonArray.map { it.jsonObject }
            assertEquals(listOf("185.11.247.237", "48.103.218.29"), outbounds.filter { it["protocol"] == JsonPrimitive("vless") }
                .map { it.getValue("settings").jsonObject.getValue("vnext").jsonArray.single().jsonObject.getValue("address").jsonPrimitive.content })
            for (outbound in outbounds.filter { it["protocol"] == JsonPrimitive("vless") ||
                it["tag"] == JsonPrimitive(ClientDirectRoutingConfig.DIRECT_TAG) }) {
                assertEquals("Ethernet Fixture", outbound.getValue("streamSettings").jsonObject.getValue("sockopt").jsonObject.getValue("interface").jsonPrimitive.content)
            }
            val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray
            assertEquals("entry-primary", rules[0].jsonObject.getValue("outboundTag").jsonPrimitive.content)
            assertEquals("entry-backup", rules[1].jsonObject.getValue("outboundTag").jsonPrimitive.content)
        }
    }

    @Test fun switchesAndReturnsWithoutReplacingTheStableFront() = runBlocking {
        val directory = Files.createTempDirectory("ef-")
        val processes = CopyOnWriteArrayList<StubProcess>()
        val targets = CopyOnWriteArrayList<String>()
        val primary = AtomicBoolean(true)
        val backup = AtomicBoolean(true)
        val primaryPort = AtomicInteger()
        val owner = DesktopEntryFailoverProcess(processStarter = { command, _ ->
            primaryPort.set(primaryProbePort(command))
            StubProcess().also { processes += it }
        },
            nativeBinary = { directory.resolve("fixture-core") },
            listenerReady = { _, _, _ -> },
            healthProbe = { proxy -> if (proxy.port == primaryPort.get()) primary.get() else backup.get() },
            routingOverride = { targets += it; true }, policyFactory = ::fastPolicy, probeIntervalMillis = 10)
        try {
            val front = owner.start(vless(), settings(), entries, "Fixture NIC", { true }, { _, _ -> }, runtimeDirectory = directory)
            await { owner.selectedEntry == VpnEntryChoice.PRIMARY }
            assertEquals(VpnEntryChoice.PRIMARY, owner.selectedEntry)
            primary.set(false)
            await { owner.selectedEntry == VpnEntryChoice.BACKUP }
            primary.set(true)
            await { targets.count { it == "entry-primary" } == 2 }
            assertSame(front, owner.front)
            assertEquals(listOf("entry-primary", "entry-backup", "entry-primary"), targets.toList())
            assertEquals(1, processes.size)
            primary.set(false); backup.set(false)
            await { targets.lastOrNull() == "entry-block" && owner.selectedEntry == null }
            assertNull(owner.selectedEntry)
            assertSame(front, owner.front)
            backup.set(true)
            await { owner.selectedEntry == VpnEntryChoice.BACKUP }
            assertSame(front, owner.front)
            owner.close()
            assertFalse(front.isAlive)
            assertTrue(Files.list(directory).use { it.findAny().isEmpty })
        } finally { owner.close(); Files.deleteIfExists(directory) }
    }

    @Test fun rejectedRoutingAcknowledgementKeepsOnlyDirectAvailableUntilConfirmed() = runBlocking {
        val directory = Files.createTempDirectory("ef-")
        val processes = CopyOnWriteArrayList<StubProcess>()
        val acknowledged = AtomicBoolean(false)
        val attempts = AtomicInteger()
        val owner = DesktopEntryFailoverProcess(processStarter = { _, _ -> StubProcess().also { processes += it } },
            nativeBinary = { directory.resolve("fixture-core") }, listenerReady = { _, _, _ -> },
            healthProbe = { true }, routingOverride = { attempts.incrementAndGet(); acknowledged.get() }, probeIntervalMillis = 10)
        try {
            owner.start(vless(), settings(), entries, "Fixture NIC", { true }, { _, _ -> }, runtimeDirectory = directory)
            await { attempts.get() >= 2 }
            assertTrue(owner.isRunning)
            assertNull(owner.selectedEntry)
            acknowledged.set(true)
            await { owner.selectedEntry == VpnEntryChoice.PRIMARY }
            owner.close()
            assertTrue(processes.none { it.isAlive })
            assertTrue(Files.list(directory).use { it.findAny().isEmpty })
        } finally { owner.close(); Files.deleteIfExists(directory) }
    }

    @Test fun coldStartDoesNotWaitForEitherUnavailableEntry() = runBlocking {
        val directory = Files.createTempDirectory("ef-offline-")
        val health = CompletableDeferred<Boolean>()
        val probes = AtomicInteger()
        val owner = DesktopEntryFailoverProcess(processStarter = { _, _ -> StubProcess() },
            nativeBinary = { directory.resolve("fixture-core") }, listenerReady = { _, _, _ -> },
            healthProbe = { probes.incrementAndGet(); health.await() }, routingOverride = { true }, probeIntervalMillis = 10)
        try {
            val front = withTimeout(1_000) {
                owner.start(vless(), settings(), entries, "Fixture NIC", { true }, { _, _ -> }, runtimeDirectory = directory)
            }
            await { probes.get() >= 2 }
            assertTrue(front.isAlive && owner.isRunning)
            assertNull(owner.selectedEntry)
            health.complete(false)
            await { probes.get() >= 4 }
            assertTrue(owner.isRunning)
            assertNull(owner.selectedEntry)
        } finally { owner.close(); Files.deleteIfExists(directory) }
    }

    @Test fun oneAwgChildDeathUsesBackupThenRestartsAndRecoversWithoutReplacingFront() = runBlocking {
        val directory = Files.createTempDirectory("ef-")
        val processes = CopyOnWriteArrayList<Pair<String, StubProcess>>()
        val time = AtomicLong(100)
        val binaryResolutions = CopyOnWriteArrayList<String>()
        val owner = DesktopEntryFailoverProcess(processStarter = { command, _ -> StubProcess().also { processes += command.first() to it } },
            nativeBinary = { binaryResolutions += it; directory.resolve(it) }, listenerReady = { _, _, _ -> }, healthProbe = { true },
            routingOverride = { true }, policyFactory = ::fastPolicy, clockMillis = time::get, probeIntervalMillis = 10)
        try {
            val front = owner.start(awg(), settings(), awgEntries, "Fixture NIC", { true }, { _, _ -> }, runtimeDirectory = directory)
            await { owner.children.size == 2 && owner.selectedEntry == VpnEntryChoice.PRIMARY }
            assertEquals(2, owner.children.size)
            val first = owner.children.first()
            first.destroy()
            await { owner.selectedEntry == VpnEntryChoice.BACKUP }
            assertTrue(owner.isRunning)
            assertSame(front, owner.front)
            time.addAndGet(10_001)
            await { owner.children.size == 2 && owner.children.first() !== first && owner.selectedEntry == VpnEntryChoice.PRIMARY }
            assertEquals(1, processes.count { it.first.endsWith("xray") })
            assertEquals(3, processes.count { it.first.endsWith("awg") })
            repeat(5) {
                val previous = owner.children.first()
                previous.destroy()
                time.addAndGet(10_001)
                await { owner.children.size == 2 && owner.children.first() !== previous }
                val session = Files.list(directory).use { it.findFirst().orElseThrow() }
                assertEquals(3L, Files.list(session).use { paths -> paths.filter { path -> path.fileName.toString().endsWith(".json") }.count() },
                    "Repeated child recovery must retain only two backend configs and one stable front")
                assertEquals(3, processes.count { it.second.isAlive })
            }
            assertEquals(listOf("xray", "awg"), binaryResolutions.toList(),
                "The second child and restarts must reuse the already running Windows executable")
            owner.close()
            assertTrue(processes.none { it.second.isAlive })
            assertTrue(Files.list(directory).use { it.findAny().isEmpty })
        } finally { owner.close(); Files.deleteIfExists(directory) }
    }

    @Test fun pairedAwgSessionCannotBeStolenAndIsReleasedAfterStop() = runBlocking {
        val directory = Files.createTempDirectory("ef-")
        val launches = AtomicInteger()
        fun owner() = DesktopEntryFailoverProcess(processStarter = { _, _ -> launches.incrementAndGet(); StubProcess() },
            nativeBinary = { directory.resolve(it) }, listenerReady = { _, _, _ -> }, healthProbe = { true },
            routingOverride = { true }, probeIntervalMillis = 10)
        val first = owner(); val second = owner(); val third = owner()
        try {
            first.start(awg(), settings(), awgEntries, "Fixture NIC", { true }, { _, _ -> }, runtimeDirectory = directory)
            await { launches.get() == 3 }
            assertFailsWith<VpnPingUnavailableException> {
                second.start(awg(), settings(), awgEntries, "Fixture NIC", { true }, { _, _ -> }, runtimeDirectory = directory)
            }
            assertEquals(3, launches.get())
            first.close()
            third.start(awg(), settings(), awgEntries, "Fixture NIC", { true }, { _, _ -> }, runtimeDirectory = directory)
            await { launches.get() == 6 }
            assertEquals(6, launches.get())
        } finally { first.close(); second.close(); third.close(); Files.deleteIfExists(directory) }
    }

    @Test fun stopOwnsAProcessCreatedDuringCancellationAndPreventsSecondChild() {
        val directory = Files.createTempDirectory("ef-")
        val creating = CountDownLatch(1); val release = CountDownLatch(1); val stopped = CountDownLatch(1)
        val launches = AtomicInteger(); val startupFailure = AtomicReference<Throwable?>(); val stopFailure = AtomicReference<Throwable?>()
        val process = StubProcess()
        val owner = DesktopEntryFailoverProcess(processStarter = { _, _ ->
            launches.incrementAndGet(); creating.countDown(); check(release.await(3, TimeUnit.SECONDS)); process
        }, nativeBinary = { directory.resolve(it) }, listenerReady = { _, _, _ -> }, healthProbe = { true }, routingOverride = { true })
        val starter = thread(isDaemon = true) {
            try { runBlocking { owner.start(awg(), settings(), awgEntries, "Fixture NIC", { true }, { _, _ -> }, runtimeDirectory = directory) } }
            catch (failure: Throwable) { startupFailure.set(failure) }
        }
        var stopper: Thread? = null
        try {
            assertTrue(creating.await(3, TimeUnit.SECONDS))
            stopper = thread(isDaemon = true) { try { owner.close() } catch (failure: Throwable) { stopFailure.set(failure) }; stopped.countDown() }
            assertFalse(stopped.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            starter.join(3_000)
            assertFalse(starter.isAlive)
            assertNotNull(startupFailure.get())
            assertNull(stopFailure.get())
            assertEquals(1, launches.get())
            assertFalse(process.isAlive)
            assertTrue(Files.list(directory).use { it.findAny().isEmpty })
        } finally { release.countDown(); starter.join(3_000); stopper?.join(3_000); owner.close(); Files.deleteIfExists(directory) }
    }

    @Test fun stoppedOwnerNeverLaunches() = runBlocking {
        var launches = 0
        val owner = DesktopEntryFailoverProcess(processStarter = { _, _ -> launches++; StubProcess() })
        owner.close()
        assertFailsWith<CancellationException> {
            owner.start(vless(), settings(), entries, "Fixture NIC", { true }, { _, _ -> })
        }
        assertEquals(0, launches)
    }

    @Test fun nativeWindowsUnixApiAcknowledgesFailoverAndFailbackOnTheSameFront() = runBlocking {
        val directory = System.getenv("DESKTOP_ENTRY_FAILOVER_TEST_ROOT")?.let(Path::of) ?: return@runBlocking
        val primaryPort = AtomicInteger(); val primaryHealthy = AtomicBoolean(true)
        val owner = DesktopEntryFailoverProcess(processStarter = { command, workingDirectory ->
            if (command.getOrNull(1) == "run") primaryPort.set(primaryProbePort(command))
            ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true).start()
        }, healthProbe = { proxy ->
            proxy.port != primaryPort.get() || primaryHealthy.get()
        }, policyFactory = ::fastPolicy, probeIntervalMillis = 30)
        var front: Process? = null
        try {
            front = owner.start(vless(), settings(), entries, DesktopPhysicalInterface.currentWindows(), { true }, { _, _ -> }, runtimeDirectory = directory)
            await { owner.selectedEntry == VpnEntryChoice.PRIMARY }
            assertEquals(VpnEntryChoice.PRIMARY, owner.selectedEntry)
            primaryHealthy.set(false)
            await { owner.selectedEntry == VpnEntryChoice.BACKUP }
            primaryHealthy.set(true)
            await { owner.selectedEntry == VpnEntryChoice.PRIMARY }
            assertSame(front, owner.front)
            assertTrue(owner.isRunning)
        } finally { owner.close(); front?.let { assertFalse(it.isAlive) } }
    }

    @Test fun nativeAwgPairAcceptsExactInterfaceBindingAndStopsBothChildren() = runBlocking {
        val directory = System.getenv("DESKTOP_ENTRY_FAILOVER_TEST_ROOT")?.let(Path::of) ?: return@runBlocking
        val owner = DesktopEntryFailoverProcess(healthProbe = { true }, probeIntervalMillis = 30)
        val filter = DesktopAdBlockingProxy()
        var front: Process? = null
        var filterProcess: Process? = null
        var children = emptyList<Process>()
        try {
            val settings = settings()
            front = owner.start(awg(), settings, awgEntries, DesktopPhysicalInterface.currentWindows(), { true }, { _, _ -> }, runtimeDirectory = directory)
            await { owner.children.size == 2 && owner.selectedEntry != null }
            children = owner.children
            assertEquals(2, children.size)
            assertTrue(children.all { it.isAlive })
            assertTrue(owner.isRunning)
            val filteredSettings = settings.copy(port = LocalPortAllocator.select("127.0.0.1", 11970,
                excludedPorts = setOf(settings.port)))
            filter.start(filteredSettings, settings, preserveUpstreamDns = true)
            filterProcess = assertNotNull(filter.process)
            assertTrue(filterProcess.isAlive && children.all { it.isAlive } && owner.isRunning,
                "Default ad blocking must coexist with both AWG children using the same bundled executable")
        } finally {
            filter.close()
            owner.close()
            filterProcess?.let { assertFalse(it.isAlive) }
            front?.let { assertFalse(it.isAlive) }
            assertTrue(children.none { it.isAlive })
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

    private fun primaryProbePort(command: List<String>): Int = Json.parseToJsonElement(Files.readString(Path.of(command.last())))
        .jsonObject.getValue("inbounds").jsonArray.map { it.jsonObject }
        .first { it["tag"] == JsonPrimitive("entry-probe-primary") }.getValue("port").jsonPrimitive.int

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
}
