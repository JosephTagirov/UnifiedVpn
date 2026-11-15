package org.olcbox.app.vpn

import kotlinx.serialization.json.*
import org.olcbox.app.data.model.VpnEntryEndpoint
import org.olcbox.app.data.model.VpnProfileConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

class ClientDirectRoutingConfigTest {
    private val rules = listOf("domain:white.test", "full:single.test", "regexp:^stun[0-9]+\\.calls\\.test$")
    private fun socks(port: Int) = XraySocksSettings(port = port, username = "synthetic-user", password = "synthetic-password")
    private fun profile(type: String = "ws", extra: String = "") = VpnProfileConfig(type = "vless", uri =
        "vless://00000000-0000-4000-8000-000000000000@entry.test:443?type=$type&security=tls&sni=entry.test&host=entry.test&path=%2Fsynthetic$extra")
    private fun parse(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun config() = XrayVlessConfig.build(profile(), socks(14080), bootstrapDnsServers = listOf("1.1.1.1", "8.8.8.8"))
    private fun JsonObject.outbounds() = getValue("outbounds").jsonArray.map { it.jsonObject }
    private fun JsonObject.routeRules() = getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
    private fun JsonObject.servers() = getValue("dns").jsonObject.getValue("servers").jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.list(key: String) = get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun JsonObject.outbound(tag: String) = outbounds().single { it.text("tag") == tag }
    private fun JsonObject.sockopt() = getValue("streamSettings").jsonObject.getValue("sockopt").jsonObject

    @Test fun nonwhiteTrafficKeepsItsVpnFallbackAndOldBroadDnsEscapeIsRemoved() {
        val original = parse(config())
        val output = parse(ClientDirectRoutingConfig.applyWithRules(original.toString(), rules))
        assertEquals(original.outbound("proxy"), output.outbound("proxy"))
        val userTags = output.getValue("inbounds").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content }
        val userRules = output.routeRules().filter { it.list("inboundTag").any(userTags::contains) }
        assertEquals("proxy", userRules.last().text("outboundTag"))
        assertNull(userRules.last()["domain"])
        assertEquals(rules, userRules.single { it.text("outboundTag") == ClientDirectRoutingConfig.DIRECT_TAG }.list("domain"))
        assertEquals("AsIs", output.getValue("routing").jsonObject.text("domainStrategy"))
        // Client DNS enters the policy's DNS module instead of matching the previous IP:53 freedom rule.
        assertEquals("dns", output.outbound(userRules.single { it.text("port") == "53" }.text("outboundTag")!!).text("protocol"))
        assertTrue(output.routeRules().none { rule -> rule.text("port") == "53" && rule["inboundTag"] == null })
    }

    @Test fun fixedHealthProbesPrecedePolicyAndBalancerRemainsTheUserDefault() {
        val source = XrayEntryFailoverConfig.buildVless(profile(), socks(14080),
            VpnEntryEndpoint("192.0.2.1", 443), VpnEntryEndpoint("192.0.2.2", 443),
            socks(14081), socks(14082), "/private/test/api.sock")
        val original = parse(source)
        val output = parse(ClientDirectRoutingConfig.applyWithRules(source, rules + "domain:google.com"))
        assertEquals(original["api"], output["api"])
        assertEquals(original.getValue("routing").jsonObject["balancers"], output.getValue("routing").jsonObject["balancers"])
        for ((tag, outbound) in listOf("entry-probe-primary" to "entry-primary", "entry-probe-backup" to "entry-backup")) {
            val before = original.getValue("inbounds").jsonArray.single { it.jsonObject.text("tag") == tag }
            assertEquals(before, output.getValue("inbounds").jsonArray.single { it.jsonObject.text("tag") == tag })
            assertEquals(outbound, output.routeRules().first { tag in it.list("inboundTag") }.text("outboundTag"))
        }
        assertEquals(listOf("entry-probe-primary"), output.routeRules()[0].list("inboundTag"))
        assertEquals(listOf("entry-probe-backup"), output.routeRules()[1].list("inboundTag"))
        assertEquals("entry-auto", output.routeRules().last { "entry-main" in it.list("inboundTag") }.text("balancerTag"))
        assertTrue(output.routeRules().filter { it.text("outboundTag") == ClientDirectRoutingConfig.DIRECT_TAG }
            .none { it.list("inboundTag").any { tag -> tag.startsWith("entry-probe-") } })
    }

    @Test fun endpointAndXhttpDownloadDnsOnlyUseScopedPrivateBootstrap() {
        val extra = URLEncoder.encode("""{"downloadSettings":{"address":"download.test","port":443,"network":"xhttp"}}""", "UTF-8")
        val source = XrayVlessConfig.build(profile("xhttp", "&extra=$extra"), socks(14080))
        val output = parse(ClientDirectRoutingConfig.applyWithRules(source, rules))
        val bootstrap = output.servers().filter { it.text("tag") == "policy-dns-bootstrap" }
        assertEquals(setOf("1.1.1.1", "8.8.8.8"), bootstrap.map { it.text("address") }.toSet())
        assertTrue(bootstrap.all { it.list("domains").toSet() == setOf("full:entry.test", "full:download.test") &&
            it["skipFallback"] == JsonPrimitive(true) })
        val route = output.routeRules().single { it.text("outboundTag") == "policy-bootstrap-direct" }
        assertEquals(listOf("policy-dns-bootstrap"), route.list("inboundTag"))
        assertEquals("53", route.text("port"))
        assertEquals(setOf("1.1.1.1", "8.8.8.8"), route.list("ip").toSet())
        assertTrue(output.getValue("inbounds").jsonArray.none { it.jsonObject.text("tag") == "policy-dns-bootstrap" })
    }

    @Test fun fakeDnsIsLimitedToTheAllowlistAndCannotCollideWithTunAddresses() {
        val output = parse(ClientDirectRoutingConfig.applyWithRules(config(), rules))
        val fake = output.servers().single { it.text("address") == "fakedns" }
        assertEquals(rules, fake.list("domains"))
        assertEquals(JsonPrimitive(true), fake["skipFallback"])
        assertEquals("UseIPv4", output.getValue("dns").jsonObject.text("queryStrategy"))
        val pool = output.getValue("fakedns").jsonArray.single().jsonObject.text("ipPool")!!
        assertTrue(containsIp(pool, "198.19.0.10"))
        assertFalse(containsIp(pool, "198.18.0.1"))
        assertFalse(containsIp(pool, "198.18.0.2"))
        val sniffing = output.getValue("inbounds").jsonArray.single().jsonObject.getValue("sniffing").jsonObject
        assertEquals(JsonPrimitive(false), sniffing["routeOnly"])
        assertTrue("fakedns" in sniffing.list("destOverride"))
        val guard = output.routeRules().single { "198.18.0.0/15" in it.list("ip") }
        assertEquals("blackhole", output.outbound(guard.text("outboundTag")!!).text("protocol"))
    }

    @Test fun domesticAndRemoteDnsPinsAndEgressStaySeparate() {
        val output = parse(ClientDirectRoutingConfig.applyWithRules(config(), rules))
        val dns = output.getValue("dns").jsonObject
        assertEquals(buildJsonObject { put("cloudflare-dns.com", "1.1.1.1"); put("dns.google", "8.8.8.8") }, dns["hosts"])
        val domestic = output.servers().single { it.text("address") == "https://dns.google/dns-query" }
        val remote = output.servers().single { it.text("address") == "https://cloudflare-dns.com/dns-query" }
        assertEquals(rules, domestic.list("domains"))
        assertTrue(remote.list("domains").isEmpty())
        val localRoute = output.routeRules().single { it.list("inboundTag") == listOf(domestic.text("tag")) && it.text("port") == "443" }
        assertEquals(listOf("full:dns.google"), localRoute.list("domain"))
        assertEquals("freedom", output.outbound(localRoute.text("outboundTag")!!).text("protocol"))
        val remoteRoute = output.routeRules().single { it.list("inboundTag") == listOf(remote.text("tag")) && it.text("port") == "443" }
        val pin = output.outbound(remoteRoute.text("outboundTag")!!)
        assertEquals("ForceIPv4", pin.sockopt().text("domainStrategy"))
        val loop = output.outbound(pin.sockopt().text("dialerProxy")!!)
        assertEquals("loopback", loop.text("protocol"))
        val reentryTag = loop.getValue("settings").jsonObject.text("inboundTag")!!
        val hop = output.routeRules().single { it.list("inboundTag") == listOf(reentryTag) }
        assertEquals(listOf("1.1.1.1"), hop.list("ip"))
        assertEquals("proxy", hop.text("outboundTag"))
    }

    @Test fun physicalInterfaceOnlyBindsPhysicalEgressAndIdempotenceRejectsAnotherUplink() {
        val rendered = ClientDirectRoutingConfig.applyWithRules(config(), rules, "Synthetic Ethernet")
        val output = parse(rendered)
        for (tag in listOf("policy-direct", "policy-dns-domestic", "policy-bootstrap-direct")) {
            assertEquals("Synthetic Ethernet", output.outbound(tag).sockopt().text("interface"))
        }
        assertNull(output.outbound("policy-dns-remote-pin").sockopt()["interface"])
        assertEquals(rendered, ClientDirectRoutingConfig.applyWithRules(rendered, rules, "Synthetic Ethernet"))
        assertFailsWith<IllegalArgumentException> { ClientDirectRoutingConfig.applyWithRules(rendered, rules, "Other Ethernet") }
        assertFailsWith<IllegalArgumentException> { ClientDirectRoutingConfig.applyWithRules(config(), rules, "bad\ninterface") }
    }

    @Test fun nativeCoreAcceptsStandaloneAndFailoverPolicyConfigurations() {
        val binary = System.getenv("XRAY_BINARY") ?: return
        val examples = listOf(
            ClientDirectRoutingConfig.applyWithRules(config(), rules),
            ClientDirectRoutingConfig.buildSocksFront(socks(14080), socks(14081)),
            ClientDirectRoutingConfig.applyWithRules(XrayEntryFailoverConfig.buildVless(profile(), socks(14080),
                VpnEntryEndpoint("192.0.2.1", 443), VpnEntryEndpoint("192.0.2.2", 443), socks(14081), socks(14082), "/private/test/api.sock"), rules)
        )
        val config = Files.createTempFile("direct-policy-validate-", ".json")
        val diagnostic = Files.createTempFile("direct-policy-validate-", ".log")
        try {
            for (example in examples) {
                Files.writeString(config, example)
                val process = ProcessBuilder(binary, "run", "-test", "-config", config.toString()).redirectErrorStream(true)
                    .redirectOutput(diagnostic.toFile()).start()
                try {
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Native config validation timed out")
                    assertEquals(0, process.exitValue(), Files.readString(diagnostic))
                } finally { stop(process) }
            }
        } finally { deleteTemporary(config); deleteTemporary(diagnostic) }
    }

    @Test fun nativeFakeIpRoutesRawUdpDirectAndNeverFallsBackToDirectForANonwhiteIp() {
        val binary = System.getenv("XRAY_BINARY") ?: return
        LoopbackDns().use { dns -> UdpMarker().use { marker -> RejectingSocks().use { upstream ->
            val main = socks(dualFreePort())
            val backend = socks(upstream.port)
            val template = parse(ClientDirectRoutingConfig.buildSocksFront(main, backend))
            val rendered = parse(ClientDirectRoutingConfig.applyWithRules(removePolicy(template).toString(), listOf("full:white.test")))
            val localFixture = localDnsFixture(rendered, dns.port)
            val config = Files.createTempFile("direct-policy-udp-", ".json")
            val diagnostic = Files.createTempFile("direct-policy-udp-", ".log")
            var process: Process? = null
            try {
                Files.writeString(config, localFixture.toString())
                process = ProcessBuilder(binary, "run", "-config", config.toString()).redirectErrorStream(true)
                    .redirectOutput(diagnostic.toFile()).start()
                awaitPort(main.port, process, diagnostic)
                val fake = SocksUdp(main).use { client ->
                    val answer = client.exchange("1.1.1.1", 53, dnsQuery("white.test"))
                    dnsA(answer)
                }
                assertTrue(containsIp(ClientDirectRoutingConfig.FAKE_POOL, fake), fake)
                assertEquals(0, dns.queries.get(), "The client DNS query must use FakeDNS, not a real upstream lookup")
                SocksUdp(main).use { client ->
                    assertEquals("DIRECT:white", client.exchange(fake, marker.port, "white".toByteArray()).toString(Charsets.UTF_8))
                    assertTrue(dns.queries.get() > 0, "The direct dial must resolve the real address while skipping FakeDNS")
                    assertEquals(0, upstream.accepted.get(), "Allowlisted raw UDP must bypass the VPN backend")
                }
                SocksUdp(main).use { client ->
                    client.send("127.0.0.1", marker.port, "nonwhite".toByteArray())
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (upstream.accepted.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20)
                    assertTrue(upstream.accepted.get() > 0, "A nonwhite literal IP must select the VPN backend")
                    assertEquals(listOf("white"), marker.received.toList(), "Nonwhite UDP must never reach the direct destination")
                }
            } finally { process?.let(::stop); deleteTemporary(config); deleteTemporary(diagnostic) }
        } } }
    }

    // Rebuild a local test base, keeping the original SOCKS endpoint and its one authenticated VPN backend.
    private fun removePolicy(template: JsonObject): JsonObject = JsonObject(template.filterKeys { it in setOf("log", "inbounds") } +
        ("outbounds" to JsonArray(template.outbounds().filter { it.text("protocol") == "socks" })))

    private fun localDnsFixture(config: JsonObject, port: Int): JsonObject {
        val dns = config.getValue("dns").jsonObject
        val servers = dns.getValue("servers").jsonArray.map { server ->
            val value = server.jsonObject
            if (value.text("tag") == ClientDirectRoutingConfig.DNS_DIRECT_TAG)
                JsonObject(value + ("address" to JsonPrimitive("tcp://127.0.0.1:$port"))) else value
        }
        val route = config.getValue("routing").jsonObject
        val rules = config.routeRules().map { rule ->
            if (rule.text("outboundTag") == ClientDirectRoutingConfig.DNS_DIRECT_TAG && rule.text("port") == "443")
                JsonObject((rule - "domain") + mapOf("ip" to JsonArray(listOf(JsonPrimitive("127.0.0.1"))), "port" to JsonPrimitive(port.toString()))) else rule
        }
        return JsonObject(config + mapOf("dns" to JsonObject(dns + ("servers" to JsonArray(servers))),
            "routing" to JsonObject(route + ("rules" to JsonArray(rules)))))
    }

    private fun containsIp(cidr: String, value: String): Boolean {
        fun ipv4(address: String) = InetAddress.getByName(address).address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toInt() and 255).toLong() }
        val bits = cidr.substringAfter('/').toInt()
        val mask = (0xffff_ffffL shl (32 - bits)) and 0xffff_ffffL
        return (ipv4(cidr.substringBefore('/')) and mask) == (ipv4(value) and mask)
    }

    private fun awaitPort(port: Int, process: Process, diagnostic: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            assertTrue(process.isAlive, Files.readString(diagnostic))
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) } }.isSuccess) return
            Thread.sleep(25)
        }
        fail("Native SOCKS did not become ready: ${Files.readString(diagnostic)}")
    }

    private fun dualFreePort(): Int {
        repeat(32) {
            ServerSocket(0).use { tcp ->
                if (runCatching { DatagramSocket(tcp.localPort, InetAddress.getByName("127.0.0.1")).close() }.isSuccess)
                    return tcp.localPort
            }
        }
        error("No shared TCP/UDP test port is available")
    }

    private fun stop(process: Process) {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
        runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }; runCatching { process.outputStream.close() }
    }

    private fun deleteTemporary(path: Path) {
        repeat(20) {
            if (runCatching { Files.deleteIfExists(path) }.isSuccess) return
            Thread.sleep(25)
        }
        path.toFile().deleteOnExit()
    }

    private class SocksUdp(settings: XraySocksSettings) : AutoCloseable {
        private val datagram = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 6_000 }
        private val control = Socket("127.0.0.1", settings.port).apply { soTimeout = 6_000 }
        private val relay: InetSocketAddress
        init {
            val input = DataInputStream(control.getInputStream()); val output = control.getOutputStream()
            output.write(byteArrayOf(5, 1, 2)); output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2)
            val user = settings.username.toByteArray(); val pass = settings.password.toByteArray()
            output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass); output.flush()
            check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
            // Match native HEV: an association with an unspecified client endpoint, one association per UDP flow.
            output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0 && input.readUnsignedByte() == 0)
            val address = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully))
                4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully))
                else -> error("Expected an IP UDP relay")
            }
            relay = InetSocketAddress(if (address.isAnyLocalAddress) InetAddress.getByName("127.0.0.1") else address, input.readUnsignedShort())
        }
        fun send(host: String, port: Int, body: ByteArray) {
            val packet = byteArrayOf(0, 0, 0, 1) + InetAddress.getByName(host).address + byteArrayOf((port ushr 8).toByte(), port.toByte()) + body
            datagram.send(DatagramPacket(packet, packet.size, relay))
        }
        fun exchange(host: String, port: Int, body: ByteArray): ByteArray {
            send(host, port, body)
            val packet = DatagramPacket(ByteArray(8192), 8192); datagram.receive(packet)
            val bytes = packet.data.copyOf(packet.length)
            check(bytes.size >= 10 && bytes[2].toInt() == 0)
            val addressLength = when (bytes[3].toInt()) { 1 -> 4; 4 -> 16; 3 -> 1 + (bytes[4].toInt() and 255); else -> error("Invalid UDP relay response") }
            return bytes.copyOfRange(4 + addressLength + 2, bytes.size)
        }
        override fun close() { datagram.close(); control.close() }
    }

    private class UdpMarker : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        val received = ConcurrentLinkedQueue<String>()
        private val worker = thread(isDaemon = true, name = "DirectPolicyUdpMarker") {
            try { while (!socket.isClosed) {
                val packet = DatagramPacket(ByteArray(2048), 2048); socket.receive(packet)
                val text = packet.data.copyOf(packet.length).toString(Charsets.UTF_8); received.add(text)
                val reply = "DIRECT:$text".toByteArray(); socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
            } } catch (_: SocketException) { }
        }
        override fun close() { socket.close(); worker.join(500) }
    }

    private class RejectingSocks : AutoCloseable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val accepted = AtomicInteger()
        private val worker = thread(isDaemon = true, name = "DirectPolicyVpnRejector") {
            try { while (!server.isClosed) server.accept().use { socket ->
                accepted.incrementAndGet(); socket.soTimeout = 2_000
                val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
                if (input.readUnsignedByte() != 5) return@use
                input.readFully(ByteArray(input.readUnsignedByte())); output.write(byteArrayOf(5, 2)); output.flush()
                if (input.readUnsignedByte() != 1) return@use
                input.readFully(ByteArray(input.readUnsignedByte())); input.readFully(ByteArray(input.readUnsignedByte()))
                output.write(byteArrayOf(1, 0)); output.flush()
                input.readUnsignedByte(); input.readUnsignedByte(); input.readUnsignedByte()
                val size = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> 0 }
                input.readFully(ByteArray(size + 2)); output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
            } } catch (_: Exception) { }
        }
        override fun close() { server.close(); worker.join(2_500) }
    }

    private class LoopbackDns : AutoCloseable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val clients = ConcurrentLinkedQueue<Socket>()
        val port = server.localPort
        val queries = AtomicInteger()
        private val worker = thread(isDaemon = true, name = "DirectPolicyDnsMock") {
            try { while (!server.isClosed) {
                val client = server.accept(); clients.add(client)
                thread(isDaemon = true, name = "DirectPolicyDnsReply") {
                    try { client.use { socket ->
                        val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
                        while (!socket.isClosed) {
                            val packet = ByteArray(input.readUnsignedShort()); input.readFully(packet); queries.incrementAndGet()
                            val end = nameEnd(packet, 12) + 4
                            val a = u16(packet, end - 4) == 1
                            val reply = packet.copyOfRange(0, 2) + byteArrayOf(-127, -128, 0, 1, 0, (if (a) 1 else 0).toByte(), 0, 0, 0, 0) +
                                packet.copyOfRange(12, end) + if (a) byteArrayOf(-64, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 127, 0, 0, 1) else byteArrayOf()
                            output.write(byteArrayOf((reply.size ushr 8).toByte(), reply.size.toByte()) + reply); output.flush()
                        }
                    } } catch (_: Exception) { } finally { clients.remove(client) }
                }
            } } catch (_: SocketException) { }
        }
        override fun close() { server.close(); clients.forEach { runCatching { it.close() } }; worker.join(500) }
    }

    private companion object {
        fun u16(bytes: ByteArray, offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        fun nameEnd(bytes: ByteArray, start: Int): Int {
            var cursor = start
            repeat(128) {
                val size = bytes[cursor].toInt() and 255
                if (size == 0) return cursor + 1
                if (size and 192 == 192) return cursor + 2
                cursor += size + 1
            }
            error("Invalid DNS name")
        }
        fun dnsQuery(host: String): ByteArray {
            val output = ByteArrayOutputStream(); output.write(byteArrayOf(18, 52, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            host.split('.').forEach { label -> output.write(label.length); output.write(label.toByteArray()) }
            output.write(byteArrayOf(0, 0, 1, 0, 1)); return output.toByteArray()
        }
        fun dnsA(bytes: ByteArray): String {
            check(u16(bytes, 0) == 0x1234 && u16(bytes, 6) >= 1)
            var cursor = nameEnd(bytes, 12) + 4
            repeat(u16(bytes, 6)) {
                cursor = nameEnd(bytes, cursor)
                val type = u16(bytes, cursor); val length = u16(bytes, cursor + 8); cursor += 10
                if (type == 1 && length == 4) return InetAddress.getByAddress(bytes.copyOfRange(cursor, cursor + 4)).hostAddress
                cursor += length
            }
            error("No A record in fake DNS response")
        }
    }
}
