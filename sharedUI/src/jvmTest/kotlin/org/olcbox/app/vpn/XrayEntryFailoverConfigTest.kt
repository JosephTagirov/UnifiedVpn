package org.olcbox.app.vpn

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.olcbox.app.data.model.VpnEntryEndpoint
import org.olcbox.app.data.model.VpnProfileConfig
import kotlin.test.*

class XrayEntryFailoverConfigTest {
    private val credentials = "synthetic-user" to "synthetic-password"
    private fun socks(port: Int) = XraySocksSettings(port = port, username = credentials.first, password = credentials.second)
    private fun profile() = VpnProfileConfig(type = "vless",
        uri = "vless://00000000-0000-4000-8000-000000000000@ru-kvn.meesharr.space:443?type=ws&security=tls&sni=ru-kvn.meesharr.space&host=ru-kvn.meesharr.space&path=%2Fsynthetic&fp=chrome&alpn=http%2F1.1")

    @Test fun vlessChangesDialTargetsWithoutChangingCredentialsOrTlsIdentity() {
        val config = Json.parseToJsonElement(XrayEntryFailoverConfig.buildVless(profile(), socks(12000),
            VpnEntryEndpoint("192.0.2.1", 443), VpnEntryEndpoint("192.0.2.2", 8443),
            socks(12001), socks(12002), "/private/app/entry/api.sock")).jsonObject
        val entries = config.getValue("outbounds").jsonArray.drop(1).map(JsonElement::jsonObject)
        assertEquals(listOf("entry-primary", "entry-backup"), entries.map { it.getValue("tag").jsonPrimitive.content })
        val peers = entries.map { it.getValue("settings").jsonObject.getValue("vnext").jsonArray.single().jsonObject }
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), peers.map { it.getValue("address").jsonPrimitive.content })
        assertEquals(listOf(443, 8443), peers.map { it.getValue("port").jsonPrimitive.int })
        assertEquals(peers[0].getValue("users"), peers[1].getValue("users"))
        assertEquals(entries[0].getValue("streamSettings"), entries[1].getValue("streamSettings"))
        val stream = entries[1].getValue("streamSettings").jsonObject
        assertEquals("ru-kvn.meesharr.space", stream.getValue("tlsSettings").jsonObject.getValue("serverName").jsonPrimitive.content)
        assertEquals("ru-kvn.meesharr.space", stream.getValue("wsSettings").jsonObject.getValue("host").jsonPrimitive.content)
        assertEquals("/synthetic", stream.getValue("wsSettings").jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals("entry-primary", config.getValue("routing").jsonObject.getValue("rules").jsonArray[0].jsonObject.getValue("outboundTag").jsonPrimitive.content)
        assertEquals("entry-backup", config.getValue("routing").jsonObject.getValue("rules").jsonArray[1].jsonObject.getValue("outboundTag").jsonPrimitive.content)
        assertSafeFront(config, 3)
        assertNull(config["dns"], "Literal entry addresses must not add a bootstrap escape route")
        assertEquals(3, config.getValue("outbounds").jsonArray.size)
    }

    @Test fun hostnameBootstrapIsRestrictedToTheCoresPrivateDnsTagAndResolverPort() {
        val config = Json.parseToJsonElement(XrayEntryFailoverConfig.buildVless(profile(), socks(12000),
            VpnEntryEndpoint("ru-entry.test", 443), VpnEntryEndpoint("backup-entry.test", 443),
            socks(12001), socks(12002), "/private/app/entry/api.sock")).jsonObject
        val dns = config.getValue("dns").jsonObject
        assertEquals("entry-bootstrap", dns.getValue("tag").jsonPrimitive.content)
        assertEquals("UseIPv4", dns.getValue("queryStrategy").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), dns["disableFallback"])
        val resolvers = dns.getValue("servers").jsonArray.map { it.jsonObject }
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), resolvers.map { it.getValue("address").jsonPrimitive.content })
        assertTrue(resolvers.all {
            it.getValue("port").jsonPrimitive.int == 53 && it.getValue("skipFallback").jsonPrimitive.boolean &&
                it.getValue("domains").jsonArray.map { domain -> domain.jsonPrimitive.content } ==
                listOf("full:ru-entry.test", "full:backup-entry.test")
        })
        val outbounds = config.getValue("outbounds").jsonArray.map { it.jsonObject }
        assertEquals("blackhole", outbounds.first().getValue("protocol").jsonPrimitive.content)
        assertEquals(listOf("entry-bootstrap-dns"), outbounds.filter { it["protocol"] == JsonPrimitive("freedom") }
            .map { it.getValue("tag").jsonPrimitive.content })
        for (entry in outbounds.filter { it["protocol"] == JsonPrimitive("vless") }) {
            assertEquals("UseIPv4", entry.getValue("streamSettings").jsonObject.getValue("sockopt").jsonObject
                .getValue("domainStrategy").jsonPrimitive.content)
        }
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        val bootstrap = rules.single { it["outboundTag"] == JsonPrimitive("entry-bootstrap-dns") }
        assertEquals(setOf("type", "inboundTag", "ip", "port", "network", "outboundTag"), bootstrap.keys)
        assertEquals(listOf("entry-bootstrap"), bootstrap.getValue("inboundTag").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), bootstrap.getValue("ip").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("53", bootstrap.getValue("port").jsonPrimitive.content)
        assertEquals("tcp,udp", bootstrap.getValue("network").jsonPrimitive.content)
        assertTrue(config.getValue("inbounds").jsonArray.none { it.jsonObject["tag"] == JsonPrimitive("entry-bootstrap") })
        assertEquals(listOf("entry-probe-primary", "entry-probe-backup", "entry-main"), rules.filter { it != bootstrap }
            .map { it.getValue("inboundTag").jsonArray.single().jsonPrimitive.content })
        // Every user/probe packet has its fixed inbound tag; none can enter the bootstrap freedom rule.
        assertEquals("entry-auto", rules.last().getValue("balancerTag").jsonPrimitive.content)
        assertEquals(listOf("entry-block"), config.getValue("routing").jsonObject.getValue("balancers").jsonArray.single()
            .jsonObject.getValue("selector").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun nativeCoreAcceptsScopedHostnameBootstrap() {
        val binary = System.getenv("XRAY_BINARY") ?: return
        val config = Files.createTempFile("entry-hostname-bootstrap-", ".json")
        val diagnostic = Files.createTempFile("entry-hostname-bootstrap-", ".log")
        try {
            Files.writeString(config, XrayEntryFailoverConfig.buildVless(profile(), socks(12000),
                VpnEntryEndpoint("ru-entry.test", 443), VpnEntryEndpoint("backup-entry.test", 443),
                socks(12001), socks(12002), "/private/app/entry/api.sock"))
            val process = ProcessBuilder(binary, "run", "-test", "-config", config.toString()).redirectErrorStream(true)
                .redirectOutput(diagnostic.toFile()).start()
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Native config validation timed out")
                assertEquals(0, process.exitValue(), Files.readString(diagnostic))
            } finally { if (process.isAlive) process.destroyForcibly() }
        } finally { Files.deleteIfExists(config); Files.deleteIfExists(diagnostic) }
    }

    @Test fun socksFrontIsBlockedUntilPolicyAcknowledgesAHealthyEntry() {
        val config = Json.parseToJsonElement(XrayEntryFailoverConfig.buildSocks(socks(12000), socks(12001),
            socks(12002), "/private/app/entry/api.sock")).jsonObject
        assertSafeFront(config, 1)
        assertNull(config["dns"], "An AWG child front must never add direct DNS")
        val servers = config.getValue("outbounds").jsonArray.drop(1).map {
            it.jsonObject.getValue("settings").jsonObject.getValue("servers").jsonArray.single().jsonObject
        }
        assertEquals(listOf(12001, 12002), servers.map { it.getValue("port").jsonPrimitive.int })
        assertTrue(servers.all { it.getValue("users").jsonArray.single().jsonObject.getValue("user").jsonPrimitive.content == credentials.first })
    }

    @Test fun publicOrUnauthenticatedControlAndSocksAreRejected() {
        for (path in listOf("@shared-api", "127.0.0.1:19999", "relative.sock", "/private/../shared.sock", "/private/a.sock,0666", "/" + "x".repeat(101))) {
            assertFailsWith<IllegalArgumentException> {
                XrayEntryFailoverConfig.buildSocks(socks(12000), socks(12001), socks(12002), path)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            XrayEntryFailoverConfig.buildSocks(socks(12000), socks(12000), socks(12002), "/private/api.sock")
        }
        assertFailsWith<IllegalArgumentException> {
            XrayEntryFailoverConfig.buildSocks(socks(12000), socks(12001).copy(username = ""), socks(12002), "/private/api.sock")
        }
        assertFailsWith<IllegalArgumentException> {
            XrayEntryFailoverConfig.buildSocks(socks(12000).copy(host = "0.0.0.0"), socks(12001), socks(12002), "/private/api.sock")
        }
    }

    @Test fun nativePrivateApiSwitchesNewConnectionsAndPreservesExistingHealthyFlows() {
        val binary = System.getenv("XRAY_BINARY") ?: return
        val directory = Files.createTempDirectory("entry-front-")
        val socket = directory.resolve("a.sock")
        val unixPath = socket.toAbsolutePath().toString().replace('\\', '/').replace(Regex("^[A-Za-z]:"), "")
        val processes = mutableListOf<Process>()
        val servers = mutableListOf<HttpServer>()
        val tunnels = mutableListOf<Tunnel>()
        var functionalFailure: Throwable? = null
        try {
            fun http(marker: String): Int {
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/") { exchange ->
                    val bytes = marker.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                server.start(); servers += server
                return server.address.port
            }
            val primary = socks(reservePort()); val backup = socks(reservePort()); val main = socks(reservePort())
            fun launch(name: String, config: String): Process {
                val file = directory.resolve("$name.json"); Files.writeString(file, config)
                val output = directory.resolve("$name.log")
                val process = ProcessBuilder(binary, "run", "-config", file.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start()
                processes += process
                return process
            }
            launch("primary", backend(primary, http("primary")))
            launch("backup", backend(backup, http("backup")))
            awaitPort(primary.port); awaitPort(backup.port)
            launch("front", XrayEntryFailoverConfig.buildSocks(main, primary, backup, unixPath))
            awaitPort(main.port)
            repeat(40) { if (!Files.exists(socket)) Thread.sleep(50) }
            assertTrue(Files.exists(socket), "Private API socket was not created")
            fun select(tag: String) {
                val output = directory.resolve("cli.log")
                val command = ProcessBuilder(binary, "api", "bo", "--server=unix://$unixPath", "--timeout=3", "-b", "entry-auto", tag)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start()
                processes += command
                assertTrue(command.waitFor(5, TimeUnit.SECONDS), "Private API call timed out")
                assertEquals(0, command.exitValue(), Files.readString(output))
            }
            assertFails { Tunnel.open(main).use { it.request() } }
            select("entry-primary")
            val heldPrimary = Tunnel.open(main).also { tunnels += it }
            assertEquals("primary", heldPrimary.request())
            select("entry-backup")
            val heldBackup = Tunnel.open(main).also { tunnels += it }
            assertEquals("backup", heldBackup.request())
            assertEquals("primary", heldPrimary.request(), "Changing priority must preserve an existing primary flow")
            select("entry-primary")
            Tunnel.open(main).use { assertEquals("primary", it.request()) }
            assertEquals("backup", heldBackup.request(), "Failback must preserve an existing healthy backup flow")
        } catch (failure: Throwable) {
            functionalFailure = failure
            throw failure
        } finally {
            cleanupPreserving(functionalFailure,
                tunnels.map { { it.close() } } + processes.asReversed().map { { stop(it) } } +
                    servers.map { { it.stop(0) } } + listOf({ deleteTreeWithRetry(directory) }))
        }
    }

    private fun stop(process: Process) {
        try {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    assertTrue(process.waitFor(3, TimeUnit.SECONDS), "Fixture process ${process.pid()} did not terminate")
                }
            }
            assertFalse(process.isAlive, "Fixture process ${process.pid()} is still alive")
        } finally {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    private fun cleanupPreserving(original: Throwable?, actions: List<() -> Unit>) {
        var cleanupFailure: Throwable? = null
        for (action in actions) try { action() } catch (failure: Throwable) {
            if (original != null) original.addSuppressed(failure)
            else if (cleanupFailure == null) cleanupFailure = failure
            else cleanupFailure.addSuppressed(failure)
        }
        if (original == null) cleanupFailure?.let { throw it }
    }

    private fun deleteTreeWithRetry(directory: Path) {
        val root = directory.toAbsolutePath().normalize()
        require(root.parent != null && root != root.root) { "Fixture cleanup requires its private temporary directory" }
        if (!Files.exists(root)) return
        // Close the walk before deleting; Windows may also briefly retain native/AV log handles after exit.
        val paths = Files.walk(root).use { it.sorted(Comparator.reverseOrder()).toList() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        for (path in paths) {
            require(path.toAbsolutePath().normalize().startsWith(root)) { "Fixture cleanup escaped its directory" }
            while (true) try { Files.deleteIfExists(path); break } catch (locked: IOException) {
                if (System.nanoTime() >= deadline) throw locked
                Thread.sleep(100)
            }
        }
    }

    private fun assertSafeFront(config: JsonObject, inboundCount: Int) {
        val outbounds = config.getValue("outbounds").jsonArray
        assertEquals("blackhole", outbounds.first().jsonObject.getValue("protocol").jsonPrimitive.content)
        assertFalse(outbounds.any { it.jsonObject.getValue("protocol").jsonPrimitive.content == "freedom" })
        assertEquals(setOf("RoutingService"), config.getValue("api").jsonObject.getValue("services").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("/private/app/entry/api.sock,0600", config.getValue("api").jsonObject.getValue("listen").jsonPrimitive.content)
        assertTrue(config.getValue("stats").jsonObject.isEmpty())
        assertEquals(inboundCount, config.getValue("inbounds").jsonArray.size)
        assertTrue(config.getValue("inbounds").jsonArray.all {
            it.jsonObject.getValue("listen").jsonPrimitive.content == "127.0.0.1" &&
                it.jsonObject.getValue("settings").jsonObject.getValue("auth").jsonPrimitive.content == "password"
        })
        val balancer = config.getValue("routing").jsonObject.getValue("balancers").jsonArray.single().jsonObject
        assertEquals(listOf("entry-block"), balancer.getValue("selector").jsonArray.map { it.jsonPrimitive.content })
        assertNull(balancer["fallbackTag"])
        assertNull(config["observatory"])
    }

    private fun backend(socks: XraySocksSettings, httpPort: Int): String = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", "error") })
        put("inbounds", buildJsonArray { add(buildJsonObject {
            put("listen", "127.0.0.1"); put("port", socks.port); put("protocol", "socks")
            put("settings", buildJsonObject { put("auth", "password"); put("accounts", buildJsonArray { add(buildJsonObject {
                put("user", socks.username); put("pass", socks.password)
            }) }) })
        }) })
        put("outbounds", buildJsonArray { add(buildJsonObject {
            put("protocol", "freedom"); put("settings", buildJsonObject {
                put("redirect", "127.0.0.1:$httpPort")
                put("finalRules", buildJsonArray { add(buildJsonObject {
                    put("action", "allow"); put("network", "tcp"); put("ip", "127.0.0.1"); put("port", "$httpPort")
                }) })
            })
        }) })
    }.toString()

    private fun reservePort() = ServerSocket(0).use { it.localPort }
    private fun awaitPort(port: Int) {
        repeat(60) { if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return; Thread.sleep(50) }
        error("Isolated Xray listener did not start")
    }

    private class Tunnel(private val socket: Socket) : AutoCloseable {
        private val input = DataInputStream(socket.getInputStream())
        fun request(): String {
            socket.getOutputStream().apply { write("GET / HTTP/1.1\r\nHost: proof.invalid\r\nConnection: keep-alive\r\n\r\n".toByteArray()); flush() }
            val headers = ByteArrayOutputStream()
            while (headers.size() < 16384) {
                headers.write(input.readUnsignedByte())
                if (headers.toString(Charsets.US_ASCII).endsWith("\r\n\r\n")) break
            }
            val text = headers.toString(Charsets.US_ASCII)
            assertTrue(text.startsWith("HTTP/1.1 200"))
            val length = Regex("(?im)^Content-Length: *(\\d+)").find(text)!!.groupValues[1].toInt()
            require(length in 1..32)
            return ByteArray(length).also(input::readFully).decodeToString()
        }
        override fun close() = socket.close()
        companion object {
            fun open(settings: XraySocksSettings): Tunnel {
                val socket = Socket("127.0.0.1", settings.port).apply { soTimeout = 3000 }
                try {
                    val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
                    output.write(byteArrayOf(5, 1, 2)); output.flush()
                    check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2)
                    val user = settings.username.toByteArray(); val pass = settings.password.toByteArray()
                    output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass); output.flush()
                    check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
                    val target = "proof.invalid".toByteArray()
                    output.write(byteArrayOf(5, 1, 0, 3, target.size.toByte()) + target + byteArrayOf(0, 80)); output.flush()
                    check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
                    input.readUnsignedByte()
                    val size = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("SOCKS address") }
                    input.readNBytes(size + 2)
                    return Tunnel(socket)
                } catch (error: Throwable) { socket.close(); throw error }
            }
        }
    }
}
