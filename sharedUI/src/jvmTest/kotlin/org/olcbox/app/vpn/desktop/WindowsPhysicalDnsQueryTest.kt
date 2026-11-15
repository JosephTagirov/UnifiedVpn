package org.olcbox.app.vpn.desktop

import kotlin.test.assertContains

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths

class WindowsPhysicalDnsQueryTest {
    private val upstream = PhysicalUpstream("Ethernet", 258, "192.168.0.10")
    private val request = query()

    private class FakeApi : PhysicalDnsSocketApi {
        val events = mutableListOf<String>()
        val options = mutableMapOf<Pair<Int, Int>, ByteArray>()
        var failure: String? = null
        var bound = PhysicalDnsBoundAddress("192.168.0.10", 54321)
        var changedIndex = false
        var throwAfterSend = false
        var sent = false
        var afterSend: () -> Unit = {}
        private fun success(name: String): Boolean { events += name; return failure != name }
        override fun startup(): Boolean = success("startup")
        override fun createUdpSocket(): Long? = if (success("create")) 79L else null
        override fun setOption(socket: Long, level: Int, option: Int, value: ByteArray): Boolean {
            check(socket == 79L)
            if (!success("set:$option")) return false
            options[level to option] = value.copyOf(); return true
        }
        override fun getOption(socket: Long, level: Int, option: Int): ByteArray? {
            if (!success("get:$option")) return null
            if (changedIndex) return ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(7).array()
            val setValue = options[level to option] ?: return null
            val index = ByteBuffer.wrap(setValue).order(ByteOrder.BIG_ENDIAN).int
            return ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(index).array()
        }
        override fun bind(socket: Long, source: String): Boolean { check(source == "192.168.0.10"); return success("bind") }
        override fun localAddress(socket: Long): PhysicalDnsBoundAddress? = if (success("local")) bound else null
        override fun connect(socket: Long, server: String, port: Int): Boolean {
            check(server == "1.1.1.1" && port == 53); return success("connect")
        }
        override fun send(socket: Long, request: ByteArray): Int {
            if (!success("send")) return -1
            sent = true; afterSend()
            if (throwAfterSend) error("simulated send failure")
            return request.size
        }
        override fun receive(socket: Long, maximum: Int): ByteArray? = if (success("receive")) ByteArray(12) { 1 } else null
        override fun close(socket: Long) { check(socket == 79L); events += "close" }
        override fun cleanup() { events += "cleanup" }
    }

    @Test fun pinsInterfaceInNetworkOrderAndBindsSourceBeforeAnyDnsDatagram() {
        val api = FakeApi()
        assertNotNull(WindowsPhysicalDnsQuery.exchange(upstream, "1.1.1.1", request, api))
        assertContentEquals(byteArrayOf(0, 0, 1, 2), api.options[0 to 31])
        assertTrue(api.events.indexOf("set:31") < api.events.indexOf("bind"))
        assertTrue(api.events.indexOf("bind") < api.events.indexOf("connect"))
        assertTrue(api.events.indexOf("connect") < api.events.indexOf("send"))
        assertEquals(2, api.events.count { it == "get:31" })
        assertEquals(2, api.events.count { it == "local" })
        assertEquals(listOf("close", "cleanup"), api.events.takeLast(2))
    }

    @Test fun everyFailureClosesOnlyAnActuallyCreatedSocketAndBalancesWinsockStartup() {
        for (failure in listOf("startup", "create", "set:31", "get:31", "bind", "local", "connect", "set:4101", "send", "set:4102", "receive")) {
            val api = FakeApi().apply { this.failure = failure }
            assertNull(WindowsPhysicalDnsQuery.exchange(upstream, "1.1.1.1", request, api), failure)
            assertEquals(if (failure in setOf("startup", "create")) 0 else 1, api.events.count { it == "close" }, failure)
            assertEquals(if (failure == "startup") 0 else 1, api.events.count { it == "cleanup" }, failure)
        }
        val api = FakeApi().apply { throwAfterSend = true }
        assertNull(WindowsPhysicalDnsQuery.exchange(upstream, "1.1.1.1", request, api))
        assertEquals(listOf("close", "cleanup"), api.events.takeLast(2))
    }

    @Test fun changedInterfaceOrCapturedSourceReadbackFailsBeforeSending() {
        for (api in listOf(FakeApi().apply { changedIndex = true }, FakeApi().apply { bound = PhysicalDnsBoundAddress("198.18.0.1", 54321) },
            FakeApi().apply { bound = PhysicalDnsBoundAddress("192.168.0.10", 0) })) {
            assertNull(WindowsPhysicalDnsQuery.exchange(upstream, "1.1.1.1", request, api))
            assertFalse(api.sent)
            assertEquals(listOf("close", "cleanup"), api.events.takeLast(2))
        }
    }

    @Test fun sendAndReceiveShareOneAbsoluteDeadlineRatherThanTwoFullTimeouts() {
        var now = 0L
        val api = FakeApi().apply { afterSend = { now = 800_000_000L } }
        assertNotNull(WindowsPhysicalDnsQuery.exchange(upstream, "1.1.1.1", request, api) { now })
        fun timeout(option: Int) = ByteBuffer.wrap(api.options.getValue(0xffff to option)).order(ByteOrder.nativeOrder()).int
        assertEquals(1200, timeout(0x1005)); assertEquals(400, timeout(0x1006))
        val expired = FakeApi().apply { afterSend = { now = 1_300_000_000L } }; now = 0
        assertNull(WindowsPhysicalDnsQuery.exchange(upstream, "1.1.1.1", request, expired) { now })
        assertFalse("receive" in expired.events)
        assertEquals(listOf("close", "cleanup"), expired.events.takeLast(2))
    }

    @Test fun invalidProviderSourceOrInterruptedThreadNeverCreatesASocket() {
        for ((network, server, bytes) in listOf(Triple(upstream, "untrusted.example", request),
            Triple(upstream.copy(index = 0), "1.1.1.1", request),
            Triple(upstream.copy(ipv4Address = "127.0.0.1"), "1.1.1.1", request),
            Triple(upstream, "1.1.1.1", ByteArray(11)))) {
            val api = FakeApi(); assertNull(WindowsPhysicalDnsQuery.exchange(network, server, bytes, api)); assertTrue(api.events.isEmpty())
        }
        val api = FakeApi(); Thread.currentThread().interrupt()
        try { assertNull(WindowsPhysicalDnsQuery.exchange(upstream, "1.1.1.1", request, api)); assertTrue(api.events.isEmpty()) }
        finally { Thread.interrupted() }
    }

    @Test fun nativeWindowsPhysicalSocketWorksWithoutChangingTheActiveVpn() {
        if (DesktopPaths.os != DesktopOs.Windows || System.getenv("UNIFIEDVPN_WINDOWS_PHYSICAL_DNS_TEST") != "1") return
        val physical = DesktopPhysicalInterface.currentWindowsNetwork()
        val ownedQuery = org.olcbox.app.vpn.ManagedEntryDns.query(0x5263)
        val addresses = listOf("1.1.1.1", "8.8.8.8").flatMap { provider ->
            WindowsPhysicalDnsQuery.exchange(physical, provider, ownedQuery)?.let {
                org.olcbox.app.vpn.ManagedEntryDns.addresses(ownedQuery, it)
            }.orEmpty()
        }
        if (addresses.isEmpty()) {
            val fallback = org.olcbox.app.vpn.ManagedEntryDns.resolve { provider, query ->
                WindowsPhysicalDnsQuery.exchange(physical, provider, query)
            }
            assertEquals("178.208.70.181", fallback)
            println("Physical UDP DNS unavailable: verified independent last-known entry fallback; no positive DNS reply claimed")
        } else assertContains(addresses, "178.208.70.181")
    }

    @Test fun nativeWindowsOwnedPhysicalUdpFixtureVerifiesTheActualSocketAbi() {
        if (DesktopPaths.os != DesktopOs.Windows || System.getenv("UNIFIEDVPN_WINDOWS_PHYSICAL_DNS_TEST") != "1") return
        val physical = DesktopPhysicalInterface.currentWindowsNetwork()
        val workerFailure = AtomicReference<Throwable?>()
        DatagramSocket(InetSocketAddress(physical.ipv4Address, 0)).use { server ->
            server.soTimeout = 2000
            val worker = thread(isDaemon = true, name = "physical-dns-fixture") {
                runCatching {
                    val packet = DatagramPacket(ByteArray(512), 512)
                    server.receive(packet)
                    check(packet.address.hostAddress == physical.ipv4Address)
                    val response = packet.data.copyOf(packet.length)
                    response[2] = (response[2].toInt() or 0x80).toByte()
                    server.send(DatagramPacket(response, response.size, packet.socketAddress))
                }.onFailure(workerFailure::set)
            }
            val api = NativePhysicalDnsSocketApi()
            assertTrue(api.startup())
            var owned: Long? = null
            try {
                val socket = assertNotNull(api.createUdpSocket()); owned = socket
                val setValue = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(physical.index).array()
                val getValue = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(physical.index).array()
                assertTrue(api.setOption(socket, 0, 31, setValue))
                assertContentEquals(getValue, api.getOption(socket, 0, 31))
                assertTrue(api.bind(socket, physical.ipv4Address))
                assertEquals(physical.ipv4Address, api.localAddress(socket)?.ipv4Address)
                assertTrue(api.connect(socket, physical.ipv4Address, server.localPort))
                val timeout = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(1200).array()
                assertTrue(api.setOption(socket, 0xffff, 0x1005, timeout))
                assertTrue(api.setOption(socket, 0xffff, 0x1006, timeout))
                assertEquals(request.size, api.send(socket, request))
                val response = assertNotNull(api.receive(socket, 512))
                assertContentEquals(request.copyOfRange(0, 2), response.copyOfRange(0, 2))
                assertTrue((response[2].toInt() and 0x80) != 0)
                assertContentEquals(getValue, api.getOption(socket, 0, 31))
            } finally {
                owned?.let(api::close)
                api.cleanup()
                worker.join(2500)
            }
            workerFailure.get()?.let { throw AssertionError("Owned physical UDP fixture failed", it) }
        }
    }

    private fun query(): ByteArray = byteArrayOf(0x52, 0x63, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1)
}
