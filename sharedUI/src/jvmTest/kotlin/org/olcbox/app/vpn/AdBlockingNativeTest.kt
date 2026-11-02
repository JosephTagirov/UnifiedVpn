package org.olcbox.app.vpn

import kotlinx.coroutines.runBlocking
import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class AdBlockingNativeTest {
    @Test fun preferencesMigrateToEnabledAndPersistAnOptOut() = runBlocking {
        val dir = Files.createTempDirectory("adblock-settings-")
        val path = dir.resolve("settings.json")
        Files.writeString(path, """{"port":12000}""")
        val store = JvmDesktopSocksProxySettingsStore(path)
        val settings = store.load()
        assertTrue(settings.blockAds)
        store.save(settings.copy(blockAds = false))
        assertFalse(store.load().blockAds)
        assertEquals(12000, store.load().port)
    }

    @Test fun nativeFilterBlocksAdsAndDnsWithoutContactingTheUpstream(): Unit = runBlocking {
        val binary = System.getenv("UNIFIEDVPN_SING_BOX_TEST_BINARY") ?: return@runBlocking
        val directory = Files.createTempDirectory("adblock-native-").toFile()
        val rules = AdBlockingRules.install(directory)
        assertFailsWith<IllegalArgumentException> { AdBlockingRules.installBytes(directory, byteArrayOf(1)) }
        assertTrue(rules.isFile)
        FakeUpstream().use { upstream ->
            val port = ServerSocket(0).use { it.localPort }
            val config = File(directory,"filter.json")
            config.writeText(AdBlockingConfig.build("127.0.0.1",port,"","","127.0.0.1",upstream.port,rules.absolutePath))
            val checker = ProcessBuilder(binary,"check","-c",config.absolutePath).redirectErrorStream(true).start()
            val checkOutput = checker.inputStream.bufferedReader().readText()
            assertTrue(checker.waitFor(15,TimeUnit.SECONDS)); assertEquals(0,checker.exitValue(),checkOutput)
            val process = ProcessBuilder(binary,"run","-c",config.absolutePath).redirectErrorStream(true)
                .redirectOutput(File(directory,"native.log")).start()
            try {
                val deadline = System.currentTimeMillis()+8000
                while (runCatching { Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress("127.0.0.1",port),200) } }.isFailure) {
                    check(process.isAlive) { File(directory,"native.log").readText() }
                    check(System.currentTimeMillis()<deadline); Thread.sleep(50)
                }
                for (domain in listOf("g.doubleclick.net","ads.g.doubleclick.net")) {
                    connect(port,domain,80).use { socket ->
                        socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: $domain\r\n\r\n".toByteArray())
                        assertTrue(runCatching { socket.getInputStream().read() }.getOrDefault(-1) < 0)
                    }
                }
                assertTrue(upstream.destinations.isEmpty(), "Blocked domains escaped to upstream")
                connect(port,"8.8.8.8",53).use { socket ->
                    val question = dnsQuestion("g.doubleclick.net")
                    val output = DataOutputStream(socket.getOutputStream())
                    output.writeShort(question.size); output.write(question); output.flush()
                    val input = DataInputStream(socket.getInputStream())
                    val response = ByteArray(input.readUnsignedShort()); input.readFully(response)
                    assertEquals(0x1234, ((response[0].toInt() and 255) shl 8) or (response[1].toInt() and 255))
                    assertEquals(3,response[3].toInt() and 15,"Expected NXDOMAIN")
                }
                assertTrue(upstream.destinations.isEmpty(),"Blocked DNS escaped to upstream")
                for (domain in listOf("example.com","notg.doubleclick.net")) {
                    connect(port,domain,80).use { socket ->
                        socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: $domain\r\n\r\n".toByteArray())
                        assertEquals('H'.code,socket.getInputStream().read(),"Allowed content must use the original tunnel")
                    }
                }
                assertEquals(listOf("example.com","notg.doubleclick.net"),upstream.destinations.toList())
            } finally {
                process.destroy(); if (!process.waitFor(3,TimeUnit.SECONDS)) process.destroyForcibly()
            }
            val stoppedDeadline = System.currentTimeMillis() + 5000
            while (runCatching { Socket(Proxy.NO_PROXY).use {
                it.connect(InetSocketAddress("127.0.0.1",port),200)
            } }.isSuccess && System.currentTimeMillis() < stoppedDeadline) Thread.sleep(50)
            assertFails { Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress("127.0.0.1",port),200) } }
        }
    }

    private fun connect(port: Int, domain: String, targetPort: Int): Socket {
        val socket = Socket(Proxy.NO_PROXY); socket.connect(InetSocketAddress("127.0.0.1",port),1000); socket.soTimeout=5000
        val input=DataInputStream(socket.getInputStream()); val output=DataOutputStream(socket.getOutputStream())
        output.write(byteArrayOf(5,1,0)); output.flush(); assertEquals(5,input.readUnsignedByte()); assertEquals(0,input.readUnsignedByte())
        val host=domain.toByteArray(); output.write(byteArrayOf(5,1,0,3,host.size.toByte())); output.write(host); output.writeShort(targetPort);output.flush()
        assertEquals(5,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte());input.readUnsignedByte()
        when(input.readUnsignedByte()) { 1 -> input.readFully(ByteArray(4)); 4 -> input.readFully(ByteArray(16)); 3 -> input.readFully(ByteArray(input.readUnsignedByte())) }
        input.readUnsignedShort();return socket
    }

    private fun dnsQuestion(domain: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        val output=DataOutputStream(bytes);output.writeShort(0x1234);output.writeShort(0x100)
        output.writeShort(1);repeat(3){output.writeShort(0)}
        domain.split('.').forEach { output.writeByte(it.length);output.writeBytes(it) }
        output.writeByte(0);output.writeShort(1);output.writeShort(1)
    }.toByteArray()

    private class FakeUpstream : AutoCloseable {
        private val server=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
        val port=server.localPort
        val destinations=CopyOnWriteArrayList<String>()
        private val listener=thread(isDaemon=true,name="FakeSocks") {
            while(!server.isClosed) {
                val socket=runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon=true) { runCatching { socket.use {
                    it.soTimeout=5000
                    val input=DataInputStream(it.getInputStream());val output=DataOutputStream(it.getOutputStream())
                    check(input.readUnsignedByte()==5); input.readFully(ByteArray(input.readUnsignedByte()))
                    output.write(byteArrayOf(5,0));output.flush()
                    check(input.readUnsignedByte()==5);check(input.readUnsignedByte()==1);input.readUnsignedByte()
                    val host=when(input.readUnsignedByte()) {
                        3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                        1 -> ByteArray(4).also(input::readFully).joinToString("."){b->(b.toInt() and 255).toString()}
                        else -> error("Unexpected upstream address")
                    }
                    input.readUnsignedShort();destinations.add(host)
                    output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0));output.flush()
                    input.read();output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray());output.flush()
                } } }
            }
        }
        override fun close(){server.close();listener.join(1000)}
    }
}
