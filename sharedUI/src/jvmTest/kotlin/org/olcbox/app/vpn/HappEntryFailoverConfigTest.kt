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
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.olcbox.app.data.model.VpnEntryEndpoint
import org.olcbox.app.data.model.VpnProfileConfig
import kotlin.test.*

class HappEntryFailoverConfigTest {
    private val uuid = "00000000-0000-4000-8000-000000000000"
    private fun profile(transport: String = "ws") = VpnProfileConfig(type = "vless",
        uri = "vless://$uuid@entry.test:443?type=$transport&security=tls&sni=entry.test&host=original-http.test&path=%2Fsynthetic&fp=chrome&alpn=http%2F1.1")
    private val primary = VpnEntryEndpoint("192.0.2.1", 443)
    private val backup = VpnEntryEndpoint("192.0.2.2", 8443)

    @Test fun standaloneProfilePreservesIdentityAndAlwaysRoutesThroughTheOrderedPair() {
        val config = Json.parseToJsonElement(HappEntryFailoverConfig.build(profile(), primary, backup)).jsonObject
        val entries = config.getValue("outbounds").jsonArray.drop(1).map { it.jsonObject }
        val peers = entries.map { it.getValue("settings").jsonObject.getValue("vnext").jsonArray.single().jsonObject }
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), peers.map { it.getValue("address").jsonPrimitive.content })
        assertEquals(listOf(443, 8443), peers.map { it.getValue("port").jsonPrimitive.int })
        assertEquals(peers[0].getValue("users"), peers[1].getValue("users"))
        assertEquals(entries[0].getValue("streamSettings"), entries[1].getValue("streamSettings"))
        val stream = entries.first().getValue("streamSettings").jsonObject
        assertEquals("entry.test", stream.getValue("tlsSettings").jsonObject.getValue("serverName").jsonPrimitive.content)
        assertEquals("original-http.test", stream.getValue("wsSettings").jsonObject.getValue("host").jsonPrimitive.content)
        assertEquals("/synthetic", stream.getValue("wsSettings").jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(listOf(10808, 10809), config.getValue("inbounds").jsonArray.map { it.jsonObject.getValue("port").jsonPrimitive.int })
        assertTrue(config.getValue("inbounds").jsonArray.all { it.jsonObject.getValue("listen").jsonPrimitive.content == "127.0.0.1" })
        val routing = config.getValue("routing").jsonObject
        val rule = routing.getValue("rules").jsonArray.single().jsonObject
        assertNull(rule["inboundTag"], "A Happ-supplied TUN inbound must use the same balancer")
        assertEquals("tcp,udp", rule.getValue("network").jsonPrimitive.content)
        assertEquals("entry-auto", rule.getValue("balancerTag").jsonPrimitive.content)
        val balancer = routing.getValue("balancers").jsonArray.single().jsonObject
        assertEquals(listOf("entry-primary"), balancer.getValue("selector").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("entry-backup", balancer.getValue("fallbackTag").jsonPrimitive.content)
        val observer = config.getValue("observatory").jsonObject
        assertEquals(listOf("entry-primary"), observer.getValue("subjectSelector").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("10s", observer.getValue("probeInterval").jsonPrimitive.content)
        assertEquals("blackhole", config.getValue("outbounds").jsonArray.first().jsonObject.getValue("protocol").jsonPrimitive.content)
        assertEquals(setOf("blackhole", "vless"), config.getValue("outbounds").jsonArray.map { it.jsonObject.getValue("protocol").jsonPrimitive.content }.toSet())
        assertNull(config["api"]); assertNull(config["stats"]); assertNull(config["dns"])
    }

    @Test fun rejectsUnverifiedTransportAndNonHttpsProbe() {
        for (url in listOf("http://health.test/", "https://user:pass@health.test/", "https://health.test/#fragment")) {
            assertFailsWith<IllegalArgumentException> { HappEntryFailoverConfig.build(profile(), primary, backup, probeUrl = url) }
        }
        assertFailsWith<IllegalArgumentException> { HappEntryFailoverConfig.build(profile(), primary, primary) }
        assertFailsWith<IllegalArgumentException> { HappEntryFailoverConfig.build(profile(), primary, backup, socksPort = 10809) }
        assertFailsWith<IllegalArgumentException> {
            HappEntryFailoverConfig.build(profile().copy(uri = profile().uri!!.replace("security=tls", "security=none")), primary, backup)
        }
    }

    @Test fun nativeObservatoryFailsOverAndBackWithoutBreakingHealthyBackupFlows() {
        val binary = System.getenv("XRAY_BINARY") ?: return
        val directory = Files.createTempDirectory("happ-entry-native-")
        val processes = mutableListOf<Process>()
        val servers = mutableListOf<HttpServer>()
        val tunnels = mutableListOf<Tunnel>()
        var functionalFailure: Throwable? = null
        try {
            val certificate = certificate(directory, processes)
            fun http(marker: String): Int {
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/") { exchange ->
                    val body = marker.toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                server.start(); servers += server
                return server.address.port
            }
            fun launch(name: String, config: String): Process {
                val file = directory.resolve("$name.json"); Files.writeString(file, config)
                val process = ProcessBuilder(binary, "run", "-config", file.toString()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("$name.log").toFile()).start()
                processes += process; return process
            }
            val primaryPort = port(); val backupPort = port(); val socksPort = port(); val httpPort = port()
            val primaryConfig = backend(primaryPort, http("primary"), certificate)
            val backupProcess = launch("backup", backend(backupPort, http("backup"), certificate))
            var primaryProcess = launch("primary", primaryConfig)
            awaitPort(primaryPort); awaitPort(backupPort)
            val built = Json.parseToJsonElement(HappEntryFailoverConfig.build(profile("tcp"),
                VpnEntryEndpoint("127.0.0.1", primaryPort), VpnEntryEndpoint("127.0.0.1", backupPort),
                socksPort, httpPort, probeIntervalSeconds = 1)).jsonObject
            // Only this isolated fixture trusts its ephemeral certificate. Real profiles keep public PKI verification.
            val entries = built.getValue("outbounds").jsonArray.map { element ->
                val outbound = element.jsonObject
                if (outbound["protocol"] != JsonPrimitive("vless")) element else {
                    val stream = outbound.getValue("streamSettings").jsonObject
                    val tls = stream.getValue("tlsSettings").jsonObject
                    JsonObject(outbound + ("streamSettings" to JsonObject(stream + ("tlsSettings" to
                        JsonObject(tls + ("pinnedPeerCertSha256" to JsonPrimitive(certificate.sha256)))))))
                }
            }
            // Local health target makes the integration deterministic; it still traverses each verified VLESS/TLS entry.
            val observer = built.getValue("observatory").jsonObject + ("probeURL" to JsonPrimitive("http://health.invalid/"))
            launch("front", JsonObject(built + mapOf("outbounds" to JsonArray(entries), "observatory" to JsonObject(observer))).toString())
            awaitPort(socksPort)
            awaitMarker(socksPort, "primary")
            stop(primaryProcess)
            awaitMarker(socksPort, "backup")
            val heldBackup = Tunnel.open(socksPort).also { tunnels += it }
            assertEquals("backup", heldBackup.request())
            primaryProcess = launch("primary-restored", primaryConfig)
            awaitPort(primaryPort)
            awaitMarker(socksPort, "primary")
            assertEquals("backup", heldBackup.request(), "Native failback must preserve an existing healthy backup flow")
            stop(primaryProcess); stop(backupProcess)
            assertFails { Tunnel.open(socksPort).use { it.request() } }
        } catch (failure: Throwable) {
            functionalFailure = failure
            throw failure
        } finally {
            cleanupPreserving(functionalFailure,
                tunnels.map { { it.close() } } + processes.asReversed().map { { stop(it) } } +
                    servers.map { { it.stop(0) } } + listOf({ deleteTreeWithRetry(directory) }))
        }
    }

    private data class Certificate(val pem: Path, val key: Path, val sha256: String)
    private fun certificate(directory: Path, processes: MutableList<Process>): Certificate {
        val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").contains("Windows")) "keytool.exe" else "keytool")
        val store = directory.resolve("synthetic.p12")
        val password = "synthetic-test-password"
        val log = directory.resolve("keytool.log")
        val command = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "proof", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=entry.test", "-ext", "SAN=dns:entry.test", "-validity", "2", "-storetype", "PKCS12",
            "-keystore", store.toString(), "-storepass", password, "-keypass", password, "-noprompt")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        processes += command
        assertTrue(command.waitFor(20, TimeUnit.SECONDS), "Synthetic certificate generation timed out")
        assertEquals(0, command.exitValue(), Files.readString(log))
        val keyStore = KeyStore.getInstance("PKCS12")
        Files.newInputStream(store).use { keyStore.load(it, password.toCharArray()) }
        val cert = keyStore.getCertificate("proof").encoded
        fun pem(type: String, bytes: ByteArray) = "-----BEGIN $type-----\n" +
            Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes) + "\n-----END $type-----\n"
        val certPath = directory.resolve("synthetic-cert.pem"); Files.writeString(certPath, pem("CERTIFICATE", cert))
        val keyPath = directory.resolve("synthetic-key.pem"); Files.writeString(keyPath, pem("PRIVATE KEY", keyStore.getKey("proof", password.toCharArray()).encoded))
        return Certificate(certPath, keyPath, MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private fun backend(port: Int, markerPort: Int, certificate: Certificate): String = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", "error") })
        put("inbounds", buildJsonArray { add(buildJsonObject {
            put("listen", "127.0.0.1"); put("port", port); put("protocol", "vless")
            put("settings", buildJsonObject { put("decryption", "none"); put("clients", buildJsonArray { add(buildJsonObject { put("id", uuid) }) }) })
            put("streamSettings", buildJsonObject {
                put("network", "raw"); put("security", "tls")
                put("tlsSettings", buildJsonObject {
                    put("alpn", buildJsonArray { add(JsonPrimitive("http/1.1")) })
                    put("certificates", buildJsonArray { add(buildJsonObject {
                        put("certificateFile", certificate.pem.toString()); put("keyFile", certificate.key.toString())
                    }) })
                })
            })
        }) })
        put("outbounds", buildJsonArray { add(buildJsonObject {
            put("protocol", "freedom"); put("settings", buildJsonObject {
                put("redirect", "127.0.0.1:$markerPort")
                put("finalRules", buildJsonArray { add(buildJsonObject {
                    put("action", "allow"); put("network", "tcp"); put("ip", "127.0.0.1"); put("port", "$markerPort")
                }) })
            })
        }) })
    }.toString()
    private fun port() = ServerSocket(0).use { it.localPort }
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
    private fun awaitPort(port: Int) {
        repeat(60) { if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return; Thread.sleep(50) }
        error("Isolated Xray listener did not start")
    }
    private fun awaitMarker(port: Int, marker: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (runCatching { Tunnel.open(port).use { it.request() } }.getOrNull() == marker) return
            Thread.sleep(100)
        }
        fail("Native observer did not select $marker within 15 seconds")
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
            fun open(port: Int): Tunnel {
                val socket = Socket("127.0.0.1", port).apply { soTimeout = 1500 }
                try {
                    val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
                    output.write(byteArrayOf(5, 1, 0)); output.flush()
                    check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
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
