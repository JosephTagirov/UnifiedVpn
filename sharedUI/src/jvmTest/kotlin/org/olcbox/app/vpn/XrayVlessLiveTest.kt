package org.olcbox.app.vpn

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.desktop.DesktopAdBlockingProxy
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Explicit private fixture only. Tests both platform configurations through loopback. */
class XrayVlessLiveTest {
    @Test fun bothPlatformPoliciesPassHttpsWithAndWithoutAdBlocking(): Unit = runBlocking {
        val fixture=System.getenv("UNIFIEDVPN_PRIVATE_VLESS_PROFILE") ?: return@runBlocking
        val binary=System.getenv("XRAY_BINARY") ?: return@runBlocking
        val root=Path.of(System.getenv("UNIFIEDVPN_TEST_APPDATA_ROOT")).toAbsolutePath().normalize()
        require(Path.of(System.getenv("APPDATA")).toAbsolutePath().normalize().startsWith(root))
        val raw=Files.readString(Path.of(fixture)).trim()
        val profile=VpnProfileConfig(type=VpnProfileConfig.TYPE_VLESS,uri=raw,rawConfig=raw)
        val failures=mutableListOf<Throwable>()
        for(androidPolicy in listOf(false,true)) for(blockAds in listOf(true,false)) {
            val port=ServerSocket(0).use { it.localPort }
            val front=ServerSocket(0).use { it.localPort }
            val config=Files.createTempFile(root,"xray-policy-", ".private.json")
            val log=Files.createTempFile(root,"xray-policy-", ".private.log")
            Files.writeString(config,XrayVlessConfig.build(profile,XraySocksSettings(port=port),
                bootstrapDnsServers=if(androidPolicy) listOf("1.1.1.1","8.8.8.8") else emptyList()))
            val process=ProcessBuilder(binary,"run","-config",config.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            val filter=DesktopAdBlockingProxy()
            try {
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
                while(!accepts(port)) {
                    check(process.isAlive && System.nanoTime()<deadline) { "Native Xray did not start; diagnostic retained privately" }
                    delay(50)
                }
                if(blockAds) filter.start(DesktopSocksProxySettings(port=front),
                    DesktopSocksProxySettings(port=port),preserveUpstreamDns=false)
                val selected=if(blockAds) front else port
                for(host in listOf("www.wikipedia.org","www.instagram.com","telegram.org")) {
                    val response=curl(selected,"https://$host")
                    assertEquals(0,response.first,"HTTPS failed for $host with Android policy=$androidPolicy filter=$blockAds")
                    assertTrue(response.second.trim().toIntOrNull() in 200..499,"No HTTPS response for $host")
                }
                if(blockAds) assertNotEquals(0,curl(selected,"https://googleads.g.doubleclick.net",5).first,
                    "Ad domain passed the enabled filter")
            } catch(error:Throwable) {
                failures.add(error)
                println("VLESS live policy=${if(androidPolicy) "Android" else "Windows"} adblock=$blockAds failed=${error.javaClass.simpleName}")
            } finally {
                val start=System.nanoTime()
                filter.close()
                process.toHandle().destroy()
                if(!process.waitFor(1000,TimeUnit.MILLISECONDS)) {
                    process.toHandle().destroyForcibly();assertTrue(process.waitFor(1000,TimeUnit.MILLISECONDS))
                }
                val releaseDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
                while((accepts(port) || blockAds && accepts(front)) && System.nanoTime()<releaseDeadline) delay(50)
                Files.deleteIfExists(config)
                println("VLESS live policy=${if(androidPolicy) "Android" else "Windows"} adblock=$blockAds stoppedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)}")
            }
            assertFalse(accepts(port),"Xray port remains open")
            if(blockAds) assertFalse(accepts(front),"Filter port remains open")
        }
        if(failures.isNotEmpty()) throw AssertionError("Live VLESS cases failed: ${failures.size}").apply {
            failures.forEach(::addSuppressed)
        }
    }

    private fun accepts(port:Int)=runCatching { Socket(Proxy.NO_PROXY).use {
        it.connect(InetSocketAddress("127.0.0.1",port),150)
    } }.isSuccess

    private fun curl(port:Int,url:String,timeout:Int=25):Pair<Int,String> {
        val root=Path.of(System.getenv("UNIFIEDVPN_TEST_APPDATA_ROOT"))
        val diagnostic=Files.createTempFile(root,"vless-curl-", ".private.log")
        // Test TLS and the HTTP response without waiting for a site's streaming page body to end.
        val process=ProcessBuilder("C:/Windows/System32/curl.exe","--silent","--show-error","--verbose","--head","--max-time",timeout.toString(),
            "--noproxy","","--socks5-hostname","127.0.0.1:$port","--output","NUL","--write-out","%{http_code}",url)
            .redirectError(diagnostic.toFile()).start()
        try {
            if(!process.waitFor((timeout+3).toLong(),TimeUnit.SECONDS)) return -1 to ""
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally { if(process.isAlive) process.destroyForcibly() }
    }
}
