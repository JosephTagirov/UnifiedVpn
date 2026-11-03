package org.olcbox.app.vpn

import kotlinx.serialization.json.*
import org.olcbox.app.data.model.VpnProfileConfig
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class XrayVlessConfigTest {
    @Test fun androidAndDesktopUseTheSameTransportOptions() {
        for (type in listOf("tcp", "raw", "websocket", "ws", "grpc", "httpupgrade", "xhttp", "splithttp")) {
            val profile = profile(type)
            val desktop = Json.parseToJsonElement(XrayVlessConfig.build(profile, XraySocksSettings(port=12080))).jsonObject
            val android = Json.parseToJsonElement(XrayVlessConfig.build(profile, XraySocksSettings(port=12080),
                bootstrapDnsServers=listOf("1.1.1.1","8.8.8.8"))).jsonObject
            val before = desktop["outbounds"]!!.jsonArray[0].jsonObject["streamSettings"]!!.jsonObject
            val after = android["outbounds"]!!.jsonArray[0].jsonObject["streamSettings"]!!.jsonObject
            assertEquals(before, JsonObject(after - "sockopt"), type)
            assertEquals("UseIPv4", after["sockopt"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)
            val rule = android["routing"]!!.jsonObject["rules"]!!.jsonArray.single().jsonObject
            assertEquals("direct", rule["outboundTag"]!!.jsonPrimitive.content)
            assertEquals("53", rule["port"]!!.jsonPrimitive.content)
            assertEquals(listOf("1.1.1.1","8.8.8.8"), rule["ip"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("vpn.example.com", after["tlsSettings"]!!.jsonObject["serverName"]!!.jsonPrimitive.content)
        }
    }

    @Test fun nativeXrayAcceptsAndroidConfigurationForEverySupportedTransport() {
        val binary=System.getenv("XRAY_BINARY") ?: return
        val config=Files.createTempFile("unified-xray-android-", ".json")
        val diagnostic=Files.createTempFile("unified-xray-android-", ".txt")
        try {
            for(type in listOf("tcp","raw","websocket","ws","grpc","httpupgrade","xhttp","splithttp")) {
                Files.writeString(config,XrayVlessConfig.build(profile(type),XraySocksSettings(port=12080),
                    bootstrapDnsServers=listOf("1.1.1.1","8.8.8.8")))
                val process=ProcessBuilder(binary,"run","-test","-config",config.toString())
                    .redirectErrorStream(true).redirectOutput(diagnostic.toFile()).start()
                try {
                    assertTrue(process.waitFor(15,TimeUnit.SECONDS))
                    assertEquals(0,process.exitValue(),"Xray rejected Android $type: ${Files.readString(diagnostic)}")
                } finally { if(process.isAlive) process.destroyForcibly() }
            }
        } finally { Files.deleteIfExists(config);Files.deleteIfExists(diagnostic) }
    }

    private fun profile(type:String)=VpnProfileConfig(type=VpnProfileConfig.TYPE_VLESS,
        uri="vless://00000000-0000-4000-8000-000000000000@vpn.example.com:443?type=$type&security=tls&sni=vpn.example.com&fp=chrome&path=%2Ftest&serviceName=test&encryption=")
}
