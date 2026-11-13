package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.*
import org.olcbox.app.data.model.VpnProfileConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class XrayProxyConfigTest {
 private fun vmess()=VpnProfileConfig(type="vmess",uri="vmess://"+Base64.getEncoder().encodeToString("""{"add":"vpn.example.test","port":443,"id":"11111111-1111-4111-8111-111111111111","aid":0,"net":"ws","tls":"tls","sni":"tls.example.test","host":"http.example.test","path":"/ws"}""".toByteArray()))
 private fun hy2()=VpnProfileConfig(type="hysteria2",uri="hysteria2://private-auth@vpn.example.test:54443/?sni=tls.example.test")
 @Test fun buildsRealProtocolsAndPreservesTlsIdentityWhenPinned() {
  for(p in listOf(vmess(),hy2())) {
   assertTrue(DesktopXrayConfig.supports(p));assertEquals(listOf("vpn.example.test"),DesktopXrayConfig.endpointHosts(p))
   val ob=Json.parseToJsonElement(DesktopXrayConfig.build(p,DesktopSocksProxySettings(),endpointAddress="203.0.113.10")).jsonObject.getValue("outbounds").jsonArray.first().jsonObject
   val stream=ob.getValue("streamSettings").jsonObject;val tls=stream.getValue("tlsSettings").jsonObject
   assertEquals("tls.example.test",tls.getValue("serverName").jsonPrimitive.content)
   assertFalse(tls.getValue("allowInsecure").jsonPrimitive.boolean)
   if(p.type=="vmess") {
    assertEquals("vmess",ob.getValue("protocol").jsonPrimitive.content)
    assertEquals("203.0.113.10",ob.getValue("settings").jsonObject.getValue("vnext").jsonArray.first().jsonObject.getValue("address").jsonPrimitive.content)
    assertEquals("http.example.test",stream.getValue("wsSettings").jsonObject.getValue("host").jsonPrimitive.content)
   } else {
    assertEquals("hysteria",ob.getValue("protocol").jsonPrimitive.content)
    assertEquals(2,ob.getValue("settings").jsonObject.getValue("version").jsonPrimitive.int)
    assertEquals("203.0.113.10",ob.getValue("settings").jsonObject.getValue("address").jsonPrimitive.content)
    assertEquals("private-auth",stream.getValue("hysteriaSettings").jsonObject.getValue("auth").jsonPrimitive.content)
   }
  }
 }
 @Test fun nativeXrayAcceptsBothGeneratedConfigurations() {
  val binary=System.getenv("UNIFIEDVPN_XRAY_TEST_BINARY")?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) } ?: return
  val vmessProfiles=listOf("raw","ws","grpc","httpupgrade","xhttp").map { transport ->
   vmess().copy(rawConfig="""{"add":"vpn.example.test","port":443,"id":"11111111-1111-4111-8111-111111111111","net":"$transport","tls":"tls","sni":"tls.example.test","path":"/test"}""")
  }
  for(p in vmessProfiles+hy2()) {
   val process=ProcessBuilder(binary.toString(),"run","-test","-config","stdin:").redirectErrorStream(true).start()
   process.outputStream.use { it.write(DesktopXrayConfig.build(p,DesktopSocksProxySettings(port=29817)).toByteArray()) }
   assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"Native core rejected generated ${p.type}")
  }
 }

 @Test fun nativeVmessWebSocketRelaysHttpOverLoopbackWithoutChangingSystemProxy() {
  val binary=System.getenv("UNIFIEDVPN_XRAY_TEST_BINARY")?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) } ?: return
  val loopback=InetAddress.getByName("127.0.0.1")
  fun unusedPort()=ServerSocket(0,1,loopback).use { it.localPort }
  fun start(config:String)=ProcessBuilder(binary.toString(),"run","-config","stdin:").redirectErrorStream(true).start().also { process ->
   process.outputStream.use { it.write(config.toByteArray()) }
  }
  fun awaitListener(port:Int,process:Process) {
   repeat(100) {
    check(process.isAlive) { "Native loopback test core exited" }
    if(runCatching { Socket().use { it.connect(InetSocketAddress(loopback,port),100) } }.isSuccess)return
    Thread.sleep(20)
   }
   error("Native loopback test listener timed out")
  }
  ServerSocket(0,1,loopback).use { http ->
   http.soTimeout=5000
   val response=thread(isDaemon=true,name="vmess-loopback-http") {
    http.accept().use { socket ->
     socket.soTimeout=5000
     val reader=socket.getInputStream().bufferedReader()
     while(!reader.readLine().isNullOrEmpty()) { /* Consume HTTP headers. */ }
     socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 13\r\nConnection: close\r\n\r\nvmess-relayed".toByteArray())
    }
   }
   val serverPort=unusedPort()
   val clientPort=unusedPort()
   // The core blocks private targets by default; allow only this test's HTTP listener.
   val serverConfig="""{"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":$serverPort,"protocol":"vmess","settings":{"clients":[{"id":"11111111-1111-4111-8111-111111111111","alterId":0}]},"streamSettings":{"network":"ws","wsSettings":{"path":"/test"}}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.1/32"],"port":${http.localPort}}]}}]}"""
   val share="""{"add":"127.0.0.1","port":$serverPort,"id":"11111111-1111-4111-8111-111111111111","net":"ws","path":"/test","tls":"none"}"""
   val profile=VpnProfileConfig(type="vmess",uri="vmess://"+Base64.getEncoder().encodeToString(share.toByteArray()))
   val server=start(serverConfig)
   var client:Process?=null
   try {
    awaitListener(serverPort,server)
    val process=start(DesktopXrayConfig.build(profile,DesktopSocksProxySettings(port=clientPort)))
    client=process
    awaitListener(clientPort,process)
    Socket(Proxy(Proxy.Type.SOCKS,InetSocketAddress(loopback,clientPort))).use { socket ->
     socket.soTimeout=5000
     socket.connect(InetSocketAddress(loopback,http.localPort),5000)
     socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
     val reader=socket.getInputStream().bufferedReader()
     assertEquals("HTTP/1.1 200 OK",reader.readLine())
     while(!reader.readLine().isNullOrEmpty()) { /* Consume HTTP headers. */ }
     val body=CharArray(13)
     var offset=0
     while(offset<body.size) {
      val count=reader.read(body,offset,body.size-offset)
      check(count>0) { "Native VMess response ended before the full body" }
      offset+=count
     }
     assertEquals("vmess-relayed",body.concatToString())
    }
    response.join(5000)
    assertFalse(response.isAlive)
   } catch (error: Exception) {
    client?.destroyForcibly()?.waitFor(5,TimeUnit.SECONDS)
    server.destroyForcibly().waitFor(5,TimeUnit.SECONDS)
    throw AssertionError("Native loopback failure. Client: ${client?.inputStream?.bufferedReader()?.readText()} Server: ${server.inputStream.bufferedReader().readText()}",error)
   } finally {
    client?.destroyForcibly()?.waitFor(5,TimeUnit.SECONDS)
    server.destroyForcibly().waitFor(5,TimeUnit.SECONDS)
   }
  }
 }
}
